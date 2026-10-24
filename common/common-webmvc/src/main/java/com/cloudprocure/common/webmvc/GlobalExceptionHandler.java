package com.cloudprocure.common.webmvc;

import com.cloudprocure.common.http.BusinessException;
import com.cloudprocure.common.http.CommonErrorCode;
import com.cloudprocure.common.http.Problems;
import com.cloudprocure.common.http.TraceIds;
import com.cloudprocure.common.http.Violation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import java.util.List;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** 复用框架的异常分类和Allow等响应头，仅统一安全的错误正文。 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Object> business(BusinessException exception, WebRequest request) {
        var servlet = ((ServletWebRequest) request).getRequest();
        var problem = Problems.create(exception.errorCode(), servlet.getRequestURI(), traceId(request));
        return ResponseEntity.status(exception.errorCode().status()).body(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> constraint(ConstraintViolationException exception, WebRequest request) {
        if (exception.getConstraintViolations().stream().anyMatch(value ->
                StreamSupport.stream(value.getPropertyPath().spliterator(), false)
                        .anyMatch(node -> node.getKind() == ElementKind.RETURN_VALUE))) {
            return handleExceptionInternal(exception, null, new HttpHeaders(),
                    CommonErrorCode.INTERNAL_ERROR.status(), request);
        }
        var violations = exception.getConstraintViolations().stream()
                .map(value -> new Violation(value.getPropertyPath().toString(), value.getMessage())).toList();
        return validation(exception, new HttpHeaders(), CommonErrorCode.VALIDATION_FAILED.status(), request, violations);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception exception, WebRequest request) throws Exception {
        // 方法级安全异常仍由安全过滤链处理，不能被兜底逻辑误改成500。
        if (exception instanceof AccessDeniedException || exception instanceof AuthenticationException) {
            throw exception;
        }
        return handleExceptionInternal(exception, null, new HttpHeaders(),
                CommonErrorCode.INTERNAL_ERROR.status(), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var violations = exception.getBindingResult().getAllErrors().stream()
                .map(error -> new Violation(error instanceof FieldError field ? field.getField() : "request",
                        error instanceof FieldError field && field.isBindingFailure()
                                ? "字段类型不正确" : error.getDefaultMessage())).toList();
        return validation(exception, headers, status, request, violations);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (exception.isForReturnValue()) {
            return handleExceptionInternal(exception, null, headers, status, request);
        }
        var violations = exception.getParameterValidationResults().stream().flatMap(result ->
                result.getResolvableErrors().stream().map(error -> new Violation(
                        error instanceof FieldError field ? field.getField() :
                                result.getMethodParameter().getParameterName(), error.getDefaultMessage()))).toList();
        return validation(exception, headers, status, request, violations);
    }

    private ResponseEntity<Object> validation(Exception exception, HttpHeaders headers, HttpStatusCode status,
            WebRequest request, List<Violation> violations) {
        var servlet = ((ServletWebRequest) request).getRequest();
        var problem = Problems.create(status, CommonErrorCode.VALIDATION_FAILED, servlet.getRequestURI(),
                traceId(request), violations);
        return super.handleExceptionInternal(exception, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            LOG.error("HTTP请求失败 traceId={}", traceId(request), exception);
        }
        var servlet = ((ServletWebRequest) request).getRequest();
        // 不复用框架异常的detail，解析器和数据库异常可能包含输入原文或内部结构。
        return super.handleExceptionInternal(exception,
                Problems.create(status, servlet.getRequestURI(), traceId(request)), headers, status, request);
    }

    private String traceId(WebRequest request) {
        return (String) request.getAttribute(TraceIds.ATTRIBUTE, WebRequest.SCOPE_REQUEST);
    }
}
