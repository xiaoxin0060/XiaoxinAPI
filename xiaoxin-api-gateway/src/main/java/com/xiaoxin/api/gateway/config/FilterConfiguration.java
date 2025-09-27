package com.xiaoxin.api.gateway.config;

import com.xiaoxin.api.gateway.filter.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 过滤器配置类 - 双层开关控制
 * 
 * 架构开关(启动期) + 功能开关(运行期)
 */
@Slf4j
@Configuration
@ConditionalOnProperty(
    name = "xiaoxin.gateway.pipeline.enabled", 
    havingValue = "true",
    matchIfMissing = false  // 默认不启用过滤器链路，保证向后兼容
)
public class FilterConfiguration {

    public FilterConfiguration() {
        log.info("🚀 过滤器链路已启用");
    }

    /** 日志过滤器 */
    @Bean
    public LoggingFilter loggingFilter() {
        log.info("✅ LoggingFilter(-100)");
        return new LoggingFilter();
    }

    /** 安全过滤器 */
    @Bean
    public SecurityFilter securityFilter() {
        log.info("✅ SecurityFilter(-90)");
        return new SecurityFilter();
    }

    /** 认证过滤器 */
    @Bean
    public AuthenticationFilter authenticationFilter() {
        log.info("✅ AuthenticationFilter(-80)");
        return new AuthenticationFilter();
    }

    /** 接口过滤器 */
    @Bean
    public InterfaceFilter interfaceFilter() {
        log.info("✅ InterfaceFilter(-70)");
        return new InterfaceFilter();
    }

    /** 限流过滤器 */
    @Bean
    public RateLimitFilter rateLimitFilter() {
        log.info("✅ RateLimitFilter(-60)");
        return new RateLimitFilter();
    }

    /** 配额过滤器 */
    @Bean
    public QuotaFilter quotaFilter() {
        log.info("✅ QuotaFilter(-50)");
        return new QuotaFilter();
    }

    /** 代理过滤器 */
    @Bean
    public ProxyFilter proxyFilter() {
        log.info("✅ ProxyFilter(-40)");
        return new ProxyFilter();
    }

    /** 响应过滤器 */
    @Bean
    public ResponseFilter responseFilter() {
        log.info("✅ ResponseFilter(-30)");
        return new ResponseFilter();
    }
}
