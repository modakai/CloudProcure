package com.cloudprocure.common.http;

import io.micrometer.tracing.Tracer;
import java.util.UUID;

/** 本地关联标识只关联当前请求；完整跨服务传播由后续Micrometer链路配置负责。 */
public final class TraceIds {
    public static final String ATTRIBUTE = TraceIds.class.getName() + ".traceId";
    public static final String HEADER = "X-Trace-Id";

    private TraceIds() {
    }

    public static String currentOrCreate(Tracer tracer) {
        // 不直接信任客户端X-Trace-Id，已有链路上下文必须来自观测组件。
        var span = tracer == null ? null : tracer.currentSpan();
        String traceId = span == null ? null : span.context().traceId();
        // NOOP Tracer仍会返回Span，但它没有可用的关联标识。
        return traceId == null || traceId.isBlank() ? UUID.randomUUID().toString().replace("-", "") : traceId;
    }
}
