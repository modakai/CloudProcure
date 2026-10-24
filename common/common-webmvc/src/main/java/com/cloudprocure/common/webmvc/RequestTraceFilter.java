package com.cloudprocure.common.webmvc;

import com.cloudprocure.common.http.TraceIds;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** 在安全过滤器之前建立关联标识，保证未认证请求也可定位。 */
final class RequestTraceFilter extends OncePerRequestFilter {
    private final Tracer tracer;

    RequestTraceFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = (String) request.getAttribute(TraceIds.ATTRIBUTE);
        if (traceId == null) {
            traceId = TraceIds.currentOrCreate(tracer);
            request.setAttribute(TraceIds.ATTRIBUTE, traceId);
        }
        String previous = MDC.get("traceId");
        MDC.put("traceId", traceId);
        response.setHeader(TraceIds.HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Servlet线程会复用，必须恢复原上下文而不是把标识留给下一个请求。
            if (previous == null) {
                MDC.remove("traceId");
            } else {
                MDC.put("traceId", previous);
            }
        }
    }
}
