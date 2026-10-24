package com.cloudprocure.identity.account;

import com.cloudprocure.common.http.CommonErrorCode;
import com.cloudprocure.common.http.Problems;
import com.cloudprocure.common.http.TraceIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/** 仅限制表单密码登录，不改变 OAuth/OIDC 协议端点的响应。 */
public final class LoginAttemptFilter extends OncePerRequestFilter {
    private final LoginAttemptLimiter limiter;
    private final ObjectMapper mapper;
    private final RequestMatcher login = PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.POST, "/login");

    public LoginAttemptFilter(LoginAttemptLimiter limiter, ObjectMapper mapper) {
        this.limiter = limiter;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !login.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String username = request.getParameter("username");
        // 当前为直连部署，只用连接来源；不能把用户可伪造的转发头当作可信地址。
        long retryAfter = limiter.reserve(username == null ? "" : username, request.getRemoteAddr());
        if (retryAfter == 0) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(CommonErrorCode.TOO_MANY_REQUESTS.status().value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // 不暴露命中的维度、账号是否存在、用户名或密码。
        mapper.writeValue(response.getOutputStream(), Problems.create(CommonErrorCode.TOO_MANY_REQUESTS,
                request.getRequestURI(), (String) request.getAttribute(TraceIds.ATTRIBUTE)));
    }
}
