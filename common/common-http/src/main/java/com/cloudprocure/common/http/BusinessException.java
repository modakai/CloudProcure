package com.cloudprocure.common.http;

import java.util.Objects;

/** 只携带可公开的业务错误码；技术异常的原始消息不能直接进入响应。 */
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(Objects.requireNonNull(errorCode, "errorCode不能为空").message());
        if (!errorCode.status().isError() || errorCode.code() == null || errorCode.code().isBlank()) {
            throw new IllegalArgumentException("业务错误必须具有错误HTTP状态和非空code");
        }
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
