package com.cloudprocure.identity;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DefaultController {

    @GetMapping("/login")
    public String loginPage() {
        return "login"; // 对应 templates/login.html (Thymeleaf) 或视图解析器配置
    }
}
