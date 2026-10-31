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

/**
 * 登录限速过滤器：只对“表单登录”这一个入口计数。
 *
 * <p>它不是 Spring Security 内置过滤器，而是普通 Servlet Filter，被手动插进安全链
 * （见 IdentitySecurityConfiguration）。选择 OncePerRequestFilter 是因为它保证一次请求只执行一次
 * （forward/include 不会重复计数）。
 *
 * <p>协议端点（/oauth2/token 等）不经过这里：客户端凭据被暴力破解属于另一类风险，
 * 与用户密码登录不能混用同一个额度，否则会互相误伤。
 */
public final class LoginAttemptFilter extends OncePerRequestFilter {
    private final LoginAttemptLimiter limiter;
    private final ObjectMapper mapper;
    // 只认 POST /login：GET /login 只是打开登录页，不需要计数。
    // PathPatternRequestMatcher 用的是 Spring 的路径模式，和 MVC 路由写法一致。
    private final RequestMatcher login = PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.POST, "/login");

    public LoginAttemptFilter(LoginAttemptLimiter limiter, ObjectMapper mapper) {
        this.limiter = limiter;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 返回 true 表示跳过本过滤器：非登录请求完全不进入限速逻辑。
        return !login.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String username = request.getParameter("username");
        // 当前为直连部署，只用连接来源；不能把用户可伪造的转发头当作可信地址。
        // 若将来放到网关后面，这里会退化成网关 IP，需要改成受信任的转发地址解析。
        long retryAfter = limiter.reserve(username == null ? "" : username, request.getRemoteAddr());
        // 返回 0 表示允许；非 0 是“建议等待的秒数”。
        if (retryAfter == 0) {
            chain.doFilter(request, response);
            return;
        }
        // 超限：429 + Retry-After，响应体使用 Problem JSON。
        // 不说明命中的是账号维度还是来源维度，也不回显用户名，避免攻击者据此探测账号是否存在。
        response.setStatus(CommonErrorCode.TOO_MANY_REQUESTS.status().value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), Problems.create(CommonErrorCode.TOO_MANY_REQUESTS,
                request.getRequestURI(), (String) request.getAttribute(TraceIds.ATTRIBUTE)));
    }
}
