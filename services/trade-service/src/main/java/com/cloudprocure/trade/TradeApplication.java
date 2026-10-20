package com.cloudprocure.trade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 采购审批与交易入口；扫描范围仅限当前服务，避免跨服务实体与配置串入。 */
@SpringBootApplication
public class TradeApplication {

    /** 由运行配置决定使用无中间件骨架模式还是实际基础设施。 */
    public static void main(String[] args) {
        SpringApplication.run(TradeApplication.class, args);
    }
}
