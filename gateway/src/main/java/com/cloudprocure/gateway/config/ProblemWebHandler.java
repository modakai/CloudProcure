package com.cloudprocure.gateway.config;

import com.cloudprocure.common.http.BusinessException;
import com.cloudprocure.common.http.CommonErrorCode;
import com.cloudprocure.common.http.Problems;
import com.cloudprocure.common.http.TraceIds;
import com.cloudprocure.common.http.Violation;
import io.micrometer.tracing.Tracer;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/** WebFlux独立适配；不依赖Servlet，也不重新包装下游已返回的错误正文。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class ProblemWebHandler implements WebFilter {
    private static final Logger LOG = LoggerFactory.getLogger(ProblemWebHandler.class);
    private final ObjectMapper mapper;
    private final ObjectProvider<Tracer> tracer;

    public ProblemWebHandler(ObjectMapper mapper, ObjectProvider<Tracer> tracer) {
        this.mapper = mapper;
        this.tracer = tracer;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return Mono.defer(() -> {
            String traceId = TraceIds.currentOrCreate(tracer.getIfAvailable());
            exchange.getAttributes().put(TraceIds.ATTRIBUTE, traceId);
            exchange.getResponse().getHeaders().set(TraceIds.HEADER, traceId);
            // 仅捕获本次过滤链抛出的异常，下游普通4xx/5xx响应保持原协议。
            return chain.filter(exchange).onErrorResume(error -> handle(exchange, error));
        });
    }

    private Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(error);
        }
        HttpStatusCode status = CommonErrorCode.INTERNAL_ERROR.status();
        if (error instanceof BusinessException business) {
            status = business.errorCode().status();
        } else if (error instanceof ErrorResponse response) {
            status = response.getStatusCode();
            exchange.getResponse().getHeaders().putAll(response.getHeaders());
        } else if (error instanceof AuthenticationException) {
            status = CommonErrorCode.UNAUTHENTICATED.status();
        } else if (error instanceof AccessDeniedException) {
            status = CommonErrorCode.FORBIDDEN.status();
        } else if (error instanceof ConnectException || error instanceof UnknownHostException) {
            status = CommonErrorCode.DEPENDENCY_UNAVAILABLE.status();
        } else if (error instanceof TimeoutException) {
            status = CommonErrorCode.GATEWAY_TIMEOUT.status();
        }
        if (status.is5xxServerError()) {
            LOG.error("网关请求失败 traceId={}", exchange.getAttribute(TraceIds.ATTRIBUTE), error);
        }
        String path = exchange.getRequest().getURI().getRawPath();
        String traceId = exchange.getAttribute(TraceIds.ATTRIBUTE);
        ProblemDetail problem;
        if (error instanceof BusinessException business) {
            problem = Problems.create(business.errorCode(), path, traceId);
        } else if (error instanceof WebExchangeBindException validation) {
            List<Violation> violations = validation.getAllErrors().stream()
                    .map(value -> new Violation(value instanceof FieldError field ? field.getField() : "request",
                            value instanceof FieldError field && field.isBindingFailure()
                                    ? "字段类型不正确" : value.getDefaultMessage())).toList();
            problem = Problems.create(status, CommonErrorCode.VALIDATION_FAILED, path, traceId, violations);
        } else {
            problem = Problems.create(status, path, traceId);
        }
        return write(exchange, problem);
    }

    public Mono<Void> write(ServerWebExchange exchange, CommonErrorCode code) {
        return write(exchange, Problems.create(code, exchange.getRequest().getURI().getRawPath(),
                exchange.getAttribute(TraceIds.ATTRIBUTE)));
    }

    private Mono<Void> write(ServerWebExchange exchange, ProblemDetail problem) {
        return Mono.defer(() -> {
            if (exchange.getResponse().isCommitted()) {
                return Mono.empty();
            }
            byte[] bytes = mapper.writeValueAsBytes(problem);
            exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(problem.getStatus()));
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
        });
    }
}
