package com.xiaoxin.api.gateway.circuit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 断路器配置属性
 * 
 * 学习要点：
 * - Spring Boot配置绑定机制的使用
 * - 配置驱动的设计模式
 * - 业务参数的合理设置
 * 
 * 业务场景：
 * - 不同环境可以有不同的熔断策略
 * - 运维可以动态调整参数
 * - 便于A/B测试和灰度发布
 * 
 * @author xiaoxin
 * @since 1.0.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "xiaoxin.gateway.circuit-breaker")
public class CircuitBreakerProperties {
    
    /**
     * 是否启用断路器功能
     * 
     * 用途：
     * - 开发环境可能需要关闭，便于调试
     * - 生产环境故障时可以快速关闭
     */
    private boolean enabled = true;
    
    /**
     * 失败阈值 - 达到此次数后触发熔断
     * 
     * 业务考虑：
     * - 太小：容易误判，正常波动就熔断
     * - 太大：故障响应慢，用户体验差
     * - 推荐：3-10次，根据业务特点调整
     */
    private int failureThreshold = 5;
    
    /**
     * 统计窗口时间（分钟）
     * 
     * 算法说明：
     * - 统计最近N分钟内的失败次数
     * - 使用滑动窗口，而非固定窗口
     * - 例如：5分钟内失败5次就熔断
     */
    private int windowMinutes = 5;
    
    /**
     * 熔断持续时间（分钟）
     * 
     * 恢复策略：
     * - 熔断后多久允许探测请求
     * - 太短：可能还未恢复就开始探测
     * - 太长：影响业务恢复速度
     * - 推荐：1-5分钟
     */
    private int openTimeoutMinutes = 1;
    
    /**
     * Redis key前缀
     * 
     * 设计考虑：
     * - 避免与其他业务key冲突
     * - 便于运维监控和调试
     * - 支持多环境部署
     */
    private String redisKeyPrefix = "xiaoxin:circuit";
    
    /**
     * Redis key过期时间（分钟）
     * 
     * 内存管理：
     * - 避免Redis内存无限增长
     * - 设置为窗口时间的2-3倍
     * - 确保不会误删还需要的数据
     */
    private int redisKeyExpireMinutes = 15;
    
    /**
     * 获取失败记录的Redis key
     * 
     * Key格式：{prefix}:failures:{serviceKey}
     * 示例：xiaoxin:circuit:failures:api.user.service
     */
    public String getFailuresKey(String serviceKey) {
        return redisKeyPrefix + ":failures:" + serviceKey;
    }
    
    /**
     * 获取熔断状态的Redis key
     * 
     * Key格式：{prefix}:state:{serviceKey}
     * 示例：xiaoxin:circuit:state:api.user.service
     */
    public String getStateKey(String serviceKey) {
        return redisKeyPrefix + ":state:" + serviceKey;
    }
    
    /**
     * 获取熔断开始时间的Redis key
     * 
     * Key格式：{prefix}:open_time:{serviceKey}
     * 示例：xiaoxin:circuit:open_time:api.user.service
     */
    public String getOpenTimeKey(String serviceKey) {
        return redisKeyPrefix + ":open_time:" + serviceKey;
    }
}
