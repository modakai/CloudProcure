package com.cloudprocure.fulfillment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 履约入口；扫描范围仅限当前服务，避免跨服务实体与配置串入。 */
@SpringBootApplication
public class FulfillmentApplication {

    /** 由运行配置决定使用无中间件骨架模式还是实际基础设施。 */
    public static void main(String[] args) {
        SpringApplication.run(FulfillmentApplication.class, args);
    }
}
