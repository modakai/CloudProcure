package com.cloudprocure.common.http;

import org.springframework.http.HttpStatus;

/** 这里只定义跨服务HTTP错误，库存、支付等业务错误留在所属服务。 */
public enum CommonErrorCode implements ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "请求参数不合法"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "请求参数校验失败"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "请提供有效身份凭证"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "无权执行此操作"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "请求的资源不存在"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "不支持请求的响应格式"),
    CONFLICT(HttpStatus.CONFLICT, "请求与当前资源状态冲突"),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "请求内容过大"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请求内容类型不支持"),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后重试"),
    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "依赖服务暂不可用，请稍后重试"),
    GATEWAY_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "依赖服务响应超时"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务暂时无法处理请求"),
    HTTP_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "请求处理失败");

    private final HttpStatus status;
    private final String message;

    CommonErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String message() {
        return message;
    }

    public static CommonErrorCode forStatus(int status) {
        // VALIDATION_FAILED由校验入口明确选择，不能把所有400都称作字段校验失败。
        for (CommonErrorCode code : values()) {
            if (code != VALIDATION_FAILED && code.status.value() == status) {
                return code;
            }
        }
        return HTTP_ERROR;
    }
}
