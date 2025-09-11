package com.xiaoxin.api.gateway.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关过滤器配置属性
 * 
 * 业务价值：
 * - 支持多环境差异化配置(dev/test/prod)
 * - 运行时动态调整过滤器参数
 * - 提供配置验证和默认值
 * - 消除硬编码配置，提升运维灵活性
 * 
 * 技术实现：
 * - @ConfigurationProperties: Spring Boot原生配置绑定机制
 * - 类型安全的配置映射，编译时验证
 * - 支持嵌套对象配置，结构化管理
 * - 自动类型转换和JSR-303验证
 * 
 * 调用链路：
 * Spring Boot启动 → 配置文件加载 → 属性绑定 → Bean注册 → 过滤器使用
 * 
 * 使用场景：
 * - 开发环境：宽松的IP白名单和限流策略
 * - 测试环境：模拟生产的安全配置
 * - 生产环境：严格的安全策略和性能优化
 * 
 * @author xiaoxin
 * @since 1.0.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "xiaoxin.gateway")
public class GatewayFilterProperties {
    
    /**
     * 过滤器开关配置
     * 
     * 业务场景：
     * - 故障排查：快速禁用特定过滤器
     * - 灰度发布：逐步启用新功能
     * - 性能优化：临时关闭非核心功能
     */
    private FilterSwitches filters = new FilterSwitches();
    
    /**
     * 安全相关配置
     * 
     * 涵盖IP白名单、签名验证、防重放攻击等安全机制
     */
    private SecurityConfig security = new SecurityConfig();
    
    /**
     * 限流相关配置
     * 
     * 基于滑动窗口的分布式限流策略
     */
    private RateLimitConfig rateLimit = new RateLimitConfig();
    
    /**
     * 代理相关配置
     * 
     * 动态代理转发的超时、重试、监控等参数
     */
    private ProxyConfig proxy = new ProxyConfig();
    
    /**
     * 过滤器开关配置类
     * 
     * 细粒度控制每个过滤器的启用状态
     */
    @Data
    public static class FilterSwitches {
        /**
         * 日志过滤器开关
         */
        private boolean logging = true;
        
        /**
         * 安全过滤器开关（IP白名单）
         */
        private boolean security = true;
        
        /**
         * 认证过滤器开关（签名验证）
         */
        private boolean authentication = true;
        
        /**
         * 接口验证过滤器开关
         */
        private boolean interfaceValidation = true;
        
        /**
         * 限流过滤器开关
         */
        private boolean rateLimit = true;
        
        /**
         * 配额过滤器开关
         */
        private boolean quota = true;
        
        /**
         * 代理过滤器开关
         */
        private boolean proxy = true;
        
        /**
         * 响应处理过滤器开关
         */
        private boolean response = true;
    }
    
    /**
     * 安全配置类
     * 
     * 涵盖网关安全相关的所有配置参数
     */
    @Data
    public static class SecurityConfig {
        /**
         * IP白名单配置
         * 
         * 支持格式：
         * - 单个IP: "192.168.1.100"
         * - CIDR网段: "192.168.0.0/16"
         * - IPv6地址: "::1"
         * 
         * 业务场景：
         * - 开发环境：允许内网访问
         * - 生产环境：限制特定IP访问
         * - 办公网络：配置公司网段
         */
        private List<String> ipWhitelist = new ArrayList<String>() {{
            add("127.0.0.1");
            add("0:0:0:0:0:0:0:1");
        }};
        
        /**
         * 签名有效期（秒）
         * 
         * 业务逻辑：
         * - 防止重放攻击的时间窗口
         * - 过短影响网络延迟容忍度
         * - 过长增加安全风险
         * 
         * 推荐值：
         * - 开发环境：600秒（10分钟）
         * - 生产环境：300秒（5分钟）
         */
        private long signatureTimeoutSeconds = 300;
        
        /**
         * nonce随机字符串长度
         * 
         * 技术要求：
         * - 客户端SDK固定生成16位
         * - 服务端验证长度必须匹配
         * - 字符集限制：[a-zA-Z0-9]
         */
        private int nonceLength = 16;
        
        /**
         * 是否启用时间戳验证
         * 
         * 安全机制：
         * - true: 验证请求时间戳在有效期内
         * - false: 跳过时间戳验证（调试模式）
         */
        private boolean enableTimestampValidation = true;
        
        /**
         * 是否启用防重放攻击
         * 
         * 技术实现：
         * - Redis存储nonce，过期时间等于签名有效期
         * - 同一nonce在有效期内只能使用一次
         * - 性能影响：每次请求需要Redis查询
         */
        private boolean enableReplayProtection = true;
    }
    
    /**
     * 限流配置类
     * 
     * 基于Redis滑动窗口实现的分布式限流
     */
    @Data
    public static class RateLimitConfig {
        /**
         * 限流功能总开关
         */
        private boolean enabled = true;
        
        /**
         * 滑动窗口大小（秒）
         * 
         * 算法说明：
         * - 60秒窗口：统计过去1分钟的请求数
         * - 窗口滑动：每秒都会移除过期的请求记录
         * - 精确控制：避免传统固定窗口的突发流量问题
         */
        private long windowSeconds = 60;
        
        /**
         * 默认限流阈值（次/窗口）
         * 
         * 业务规则：
         * - 接口可以自定义限流值
         * - 未配置时使用此默认值
         * - 0或负数表示不限流
         */
        private int defaultLimit = 1000;
        
        /**
         * Redis key前缀
         * 
         * Key格式：{prefix}:userId:interfaceId
         * 示例：xiaoxin:rate_limit:1001:2001
         */
        private String redisKeyPrefix = "xiaoxin:rate_limit";
        
        /**
         * Redis key过期时间（秒）
         * 
         * 性能优化：
         * - 设置比窗口大小稍长的过期时间
         * - 避免Redis内存积累过期key
         * - 75秒 = 60秒窗口 + 15秒缓冲
         */
        private long keyExpireSeconds = 75;
    }
    
    /**
     * 代理配置类
     * 
     * 动态代理转发相关的所有配置参数
     */
    @Data
    public static class ProxyConfig {
        /**
         * 默认超时时间（毫秒）
         * 
         * 业务场景：
         * - 快速接口：5-10秒
         * - 普通接口：30秒（默认）
         * - 复杂接口：60-120秒
         */
        private int defaultTimeoutMs = 30000;
        
        /**
         * 默认重试次数
         * 
         * 重试策略：
         * - 网络异常：自动重试
         * - 超时异常：自动重试
         * - 业务异常：不重试
         */
        private int defaultRetryCount = 3;
        
        /**
         * 是否启用指标收集
         * 
         * 监控数据：
         * - 接口调用次数和成功率
         * - 响应时间分布
         * - 错误类型统计
         */
        private boolean enableMetrics = true;
        
        /**
         * 是否启用请求日志
         * 
         * 日志内容：
         * - 代理目标URL
         * - 请求和响应大小
         * - 处理耗时
         */
        private boolean enableRequestLogging = true;
    }
}
