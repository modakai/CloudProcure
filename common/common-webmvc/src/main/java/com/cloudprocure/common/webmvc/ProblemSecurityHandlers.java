package com.cloudprocure.common.webmvc;

import com.cloudprocure.common.http.CommonErrorCode;
import com.cloudprocure.common.http.Problems;
import com.cloudprocure.common.http.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

/** 安全异常发生在MVC外；资源服务器先委托框架保留Bearer挑战头。 */
public final class ProblemSecurityHandlers {
    private final ObjectMapper mapper;

    public ProblemSecurityHandlers(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, exception) -> write(request, response, CommonErrorCode.UNAUTHENTICATED);
    }

    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, exception) -> write(request, response, CommonErrorCode.FORBIDDEN);
    }

    public AuthenticationEntryPoint bearerAuthenticationEntryPoint() {
        var delegate = new BearerTokenAuthenticationEntryPoint();
        return (request, response, exception) -> {
            delegate.commence(request, response, exception);
            // Bearer协议错误也可能是400，不能一律改成401。
            write(request, response, CommonErrorCode.forStatus(response.getStatus()));
        };
    }

    public AccessDeniedHandler bearerAccessDeniedHandler() {
        var delegate = new BearerTokenAccessDeniedHandler();
        return (request, response, exception) -> {
            delegate.handle(request, response, exception);
            write(request, response, CommonErrorCode.FORBIDDEN);
        };
    }

    private void write(HttpServletRequest request, HttpServletResponse response,
                       CommonErrorCode code) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), Problems.create(code, request.getRequestURI(),
                (String) request.getAttribute(TraceIds.ATTRIBUTE)));
    }
}
