package com.cloudprocure.common.http;

import java.util.Objects;

/** 仅供接口显式选择；不改变HTTP状态码，也不参与全局异常包装。 */
public record Result<T>(String code, String message, T data) {

    public Result {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code不能为空");
        }
        Objects.requireNonNull(message, "message不能为空");
    }

    public static <T> Result<T> success(T data) {
        return new Result<>("OK", "成功", data);
    }

    public static Result<Void> success() {
        return success(null);
    }

    public static <T> Result<T> of(String code, String message, T data) {
        return new Result<>(code, message, data);
    }
}
