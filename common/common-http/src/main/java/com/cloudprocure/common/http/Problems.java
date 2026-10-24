package com.cloudprocure.common.http;

import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** MVC、安全过滤器及网关共用同一契约；序列化交给各自的Web栈。 */
public final class Problems {
    private Problems() {
    }

    public static ProblemDetail create(ErrorCode code, String path, String traceId) {
        return create(code.status(), code, path, traceId, List.of());
    }

    public static ProblemDetail create(HttpStatusCode status, String path, String traceId) {
        return create(status, CommonErrorCode.forStatus(status.value()), path, traceId, List.of());
    }

    public static ProblemDetail create(HttpStatusCode status, ErrorCode code, String path,
                                       String traceId, List<Violation> violations) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, code.message());
        // about:blank沿用HTTP状态语义，避免制造没有说明文档的错误类型URL。
        problem.setInstance(URI.create(path));
        problem.setProperty("code", code.code());
        problem.setProperty("traceId", traceId);
        problem.setProperty("violations", List.copyOf(violations));
        return problem;
    }
}
