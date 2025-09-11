package com.xiaoxin.api.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClient配置类
 * 
 * 职责：
 * - 配置全局的WebClient实例
 * - 统一设置连接池、超时、编码等参数
 * - 为过滤器提供可注入的WebClient Bean
 * - 支持自定义配置和扩展
 * 
 * 技术特性：
 * - 响应式HTTP客户端配置
 * - 内存缓冲区大小限制
 * - 连接复用和资源管理
 * - 生产级配置优化
 * 
 * 使用场景：
 * - ProxyFilter中的动态代理调用
 * - 其他需要HTTP调用的组件
 * - 第三方API接口调用
 * 
 * 配置说明：
 * - maxInMemorySize: 限制内存中缓存的最大响应体大小
 * - 默认1MB，防止大响应体导致内存溢出
 * - 可根据实际业务需求调整
 * 
 * @author xiaoxin
 * @since 1.0.0
 */
@Configuration
public class WebClientConfig {

    /**
     * 配置全局WebClient Bean
     * 
     * 配置特性：
     * - 最大内存缓冲区：1MB
     * - 自动连接池管理
     * - 支持各种HTTP方法
     * - 响应式流处理
     * 
     * 性能优化：
     * - 连接复用减少握手开销
     * - 内存限制防止OOM
     * - 异步非阻塞处理
     * - 自动资源释放
     * 
     * 扩展点：
     * - 可添加全局过滤器
     * - 可配置认证信息
     * - 可设置全局头部
     * - 可配置重试策略
     * 
     * @return 配置好的WebClient实例
     */
    @Bean
    public WebClient webClient() {
        return WebClient.builder()
            // 设置编解码器配置
            .codecs(configurer -> {
                // 限制内存中缓存的最大响应体大小为1MB
                // 防止大响应体导致内存溢出，提高系统稳定性
                configurer.defaultCodecs().maxInMemorySize(1024 * 1024);
            })
            // 可以在这里添加更多全局配置
            // .defaultHeader("User-Agent", "XiaoXin-API-Gateway/1.0")
            // .defaultHeader("Accept", "application/json")
            // .filter(ExchangeFilterFunction.ofRequestProcessor(request -> {...}))
            .build();
    }
}
