package com.cloudprocure.common.http;

/** 不携带rejectedValue，避免密码、Token及其他企业数据通过校验错误回显。 */
public record Violation(String field, String message) {
}
