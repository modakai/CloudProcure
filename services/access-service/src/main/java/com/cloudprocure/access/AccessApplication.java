package com.cloudprocure.access;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 企业组织与权限入口；扫描范围仅限当前服务，避免跨服务实体与配置串入。 */
@SpringBootApplication
public class AccessApplication {

    /** 由运行配置决定使用无中间件骨架模式还是实际基础设施。 */
    public static void main(String[] args) {
        SpringApplication.run(AccessApplication.class, args);
    }
}
