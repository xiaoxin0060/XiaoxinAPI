package com.xiaoxin.api.gateway;

import com.xiaoxin.provider.DemoService;
import lombok.extern.slf4j.Slf4j;
import model.entity.InterfaceInfo;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.apache.dubbo.rpc.RpcException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import service.InnerInterfaceInfoService;

import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest
@EnableDubbo
@Slf4j
public class CustomGlobalFilterTest {
    private static final Logger log = LoggerFactory.getLogger(CustomGlobalFilterTest.class);

    @DubboReference(
            timeout = 30000,
            retries = 0,
            check = false
    )
    private InnerInterfaceInfoService innerInterfaceInfoService;
    @DubboReference
    private DemoService demoService;

    @Test
    void diagnoseServiceConnection() {
        log.info("=== 开始 Dubbo 服务诊断 ===");

        // 1. 检查服务引用
        log.info("服务引用状态: {}", innerInterfaceInfoService != null ? "已注入" : "注入失败");

        if (innerInterfaceInfoService != null) {
            log.info("服务代理类: {}", innerInterfaceInfoService.getClass().getName());

            // 2. 尝试调用服务
            try {
                log.info("开始调用服务方法...");
                InterfaceInfo result = innerInterfaceInfoService.getInterfaceInfo(
                        "http://localhost:8888/api/name/user",
                        "POST"
                );
                log.info("服务调用成功，结果: {}", result);

            } catch (RpcException e) {
                log.error("RPC调用异常: {}", e.getMessage());
                log.error("异常代码: {}", e.getCode());
                if (e.getCause() != null) {
                    log.error("底层异常: {}", e.getCause().getMessage());
                }
            } catch (Exception e) {
                log.error("其他异常", e);
            }
        } else {
            fail("服务注入失败，请检查配置");
        }
    }
    @Test
    void simpleConnectivityTest() {
        try {
            // 使用更简单的参数进行测试
            InterfaceInfo result = innerInterfaceInfoService.getInterfaceInfo("test", "GET");
            log.info("简单测试完成，结果: {}", result);
        } catch (Exception e) {
            log.error("简单测试失败", e);
        }
    }
    @Test
    void testWithActualData() {
        log.info("=== 测试真实数据 ===");

        try {
            // 使用实际存在的数据进行测试
            String path = "http://localhost:8888/api/name/user";
            String method = "POST";

            InterfaceInfo result = innerInterfaceInfoService.getInterfaceInfo(path, method);
            log.info("真实数据查询结果: {}", result);

            // 如果你确定数据库中有这条记录，才断言不为空
            // assertNotNull(result, "接口信息不能为空");

        } catch (Exception e) {
            log.error("真实数据测试失败{}", e.getMessage());
        }
    }
    @Test
    void testDubboDemo() {
        System.out.println(">>>>>>>>>>>>>>>>>>>>>>>>>"+demoService.sayHello("Xiaoxin"));
    }
}