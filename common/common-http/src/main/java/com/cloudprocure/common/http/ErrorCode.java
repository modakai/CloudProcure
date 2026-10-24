package com.cloudprocure.common.http;

import org.springframework.http.HttpStatus;

/** 各服务实现自己的业务错误码；message必须是允许对外公开的固定文案。 */
public interface ErrorCode {
    String code();

    HttpStatus status();

    String message();
}
