package com.cloudprocure.identity.account;

import com.cloudprocure.common.http.Result;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/identity/api/v1")
public class AccountController {

    @GetMapping("/me")
    public Result<Map<String, String>> me(Authentication authentication) {
        // 上一步已将认证主体名称设为稳定 subject，而不是登录名。
        return Result.success(Map.of("subject", authentication.getName()));
    }
}
