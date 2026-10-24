package com.cloudprocure.common.http;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** 游标含义和有效性由拥有查询条件的服务校验，公共层不伪造通用SQL游标。 */
public record CursorRequest(@Size(max = 2048) String cursor, @Min(1) @Max(100) Integer limit) {

    public CursorRequest {
        limit = limit == null ? 20 : limit;
    }
}
