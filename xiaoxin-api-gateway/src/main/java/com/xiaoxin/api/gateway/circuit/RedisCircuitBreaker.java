package com.xiaoxin.api.gateway.circuit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Redis分布式断路器实现
 * 
 * 学习要点：
 * 1. 分布式系统中的状态共享机制
 * 2. Redis有序集合(ZSet)的实际应用
 * 3. 响应式编程中的异常处理
 * 4. 滑动窗口算法的实现
 * 
 * 业务价值：
 * - 防止服务雪崩，保护系统稳定性
 * - 快速故障检测和自动恢复
 * - 提升用户体验（快速失败vs长时间等待）
 * 
 * 技术特点：
 * - 基于Redis实现分布式状态共享
 * - 使用滑动窗口统计失败率
 * - 支持自动状态转换和恢复
 * - 异常安全设计，Redis故障时降级
 * 
 * @author xiaoxin
 * @since 1.0.0
 */
@Slf4j
@Component
public class RedisCircuitBreaker {
    
    /**
     * 断路器状态枚举
     */
    public enum CircuitState {
        CLOSED,    // 关闭状态：正常工作，允许请求通过
        OPEN,      // 开启状态：熔断保护，拒绝所有请求
        HALF_OPEN  // 半开状态：探测恢复，允许少量请求试探
    }
    
    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;
    
    @Autowired
    private CircuitBreakerProperties properties;
    
    /**
     * 记录服务调用失败
     * 
     * 算法原理：
     * 1. 使用Redis ZSet存储失败时间戳
     * 2. 时间戳作为score，便于按时间范围查询
     * 3. 自动清理过期数据，控制内存使用
     * 
     * 数据结构：
     * Key: xiaoxin:circuit:failures:api.user.service
     * Value: {
     *   "req-uuid-001": 1703123400000,  // 失败时间戳1
     *   "req-uuid-002": 1703123410000,  // 失败时间戳2
     * }
     * 
     * @param serviceKey 服务标识（如：api.user.service）
     * @return 操作完成的Mono
     */
    public Mono<Void> recordFailure(String serviceKey) {
        if (!properties.isEnabled()) {
            return Mono.empty();
        }
        
        String failuresKey = properties.getFailuresKey(serviceKey);
        long now = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();
        
        log.debug("记录服务失败 - 服务: {}, 时间: {}", serviceKey, now);
        
        return redisTemplate.opsForZSet()
            // 添加当前失败记录
            .add(failuresKey, requestId, now)
            .then(
                // 清理过期数据（超过统计窗口的记录）
                redisTemplate.opsForZSet().removeRangeByScore(
                    failuresKey, 
                    Range.closed(0.0, (double)(now - Duration.ofMinutes(properties.getWindowMinutes()).toMillis()))
                )
            )
            .then(
                // 设置key过期时间，防止内存泄漏
                redisTemplate.expire(failuresKey, Duration.ofMinutes(properties.getRedisKeyExpireMinutes()))
            )
            .then()
            .doOnSuccess(unused -> log.debug("失败记录已保存 - 服务: {}", serviceKey))
            .onErrorResume(error -> {
                // Redis异常时的降级处理：记录日志但不影响主流程
                log.error("记录失败信息异常 - 服务: {}, 错误: {}", serviceKey, error.getMessage());
                return Mono.empty();
            });
    }
    
    /**
     * 记录服务调用成功
     * 
     * 成功处理策略：
     * 1. 如果当前是HALF_OPEN状态，恢复为CLOSED
     * 2. 清除熔断相关的状态信息
     * 3. 可选：清除失败计数（当前实现保留，用于统计）
     * 
     * @param serviceKey 服务标识
     * @return 操作完成的Mono
     */
    public Mono<Void> recordSuccess(String serviceKey) {
        if (!properties.isEnabled()) {
            return Mono.empty();
        }
        
        log.debug("记录服务成功 - 服务: {}", serviceKey);
        
        return getCurrentState(serviceKey)
            .flatMap(currentState -> {
                if (currentState == CircuitState.HALF_OPEN) {
                    // 半开状态下成功，恢复为正常状态
                    return clearCircuitBreakerState(serviceKey)
                        .doOnSuccess(unused -> 
                            log.info("断路器恢复正常 - 服务: {}", serviceKey));
                }
                return Mono.empty();
            })
            .onErrorResume(error -> {
                log.error("记录成功信息异常 - 服务: {}, 错误: {}", serviceKey, error.getMessage());
                return Mono.empty();
            });
    }
    
    /**
     * 检查是否应该触发熔断
     * 
     * 判断逻辑：
     * 1. 统计窗口内的失败次数
     * 2. 超过阈值则应该熔断
     * 3. 使用Redis ZSet的count操作，高效统计
     * 
     * @param serviceKey 服务标识
     * @return true-应该熔断，false-正常工作
     */
    public Mono<Boolean> shouldTriggerCircuitBreak(String serviceKey) {
        if (!properties.isEnabled()) {
            return Mono.just(false);
        }
        
        String failuresKey = properties.getFailuresKey(serviceKey);
        long now = System.currentTimeMillis();
        long windowStart = now - Duration.ofMinutes(properties.getWindowMinutes()).toMillis();
        
        return redisTemplate.opsForZSet()
            .count(failuresKey, Range.closed((double)windowStart, (double)now))
            .map(failureCount -> {
                boolean shouldBreak = failureCount >= properties.getFailureThreshold();
                
                log.debug("熔断检查 - 服务: {}, 失败次数: {}, 阈值: {}, 是否熔断: {}", 
                         serviceKey, failureCount, properties.getFailureThreshold(), shouldBreak);
                
                return shouldBreak;
            })
            .onErrorResume(error -> {
                // Redis异常时的降级策略：不熔断，保证可用性
                log.error("熔断检查异常 - 服务: {}, 错误: {}", serviceKey, error.getMessage());
                return Mono.just(false);
            });
    }
    
    /**
     * 触发熔断
     * 
     * 操作步骤：
     * 1. 设置状态为OPEN
     * 2. 记录熔断开始时间
     * 3. 设置合理的过期时间
     * 
     * @param serviceKey 服务标识
     * @return 操作完成的Mono
     */
    public Mono<Void> triggerCircuitBreak(String serviceKey) {
        if (!properties.isEnabled()) {
            return Mono.empty();
        }
        
        String stateKey = properties.getStateKey(serviceKey);
        String openTimeKey = properties.getOpenTimeKey(serviceKey);
        long now = System.currentTimeMillis();
        
        log.warn("触发熔断 - 服务: {}, 时间: {}", serviceKey, now);
        
        return redisTemplate.opsForValue()
            // 设置熔断状态
            .set(stateKey, CircuitState.OPEN.name())
            .then(
                // 记录熔断开始时间
                redisTemplate.opsForValue().set(openTimeKey, String.valueOf(now))
            )
            .then(
                // 设置状态过期时间（防止永久熔断）
                redisTemplate.expire(stateKey, Duration.ofMinutes(properties.getRedisKeyExpireMinutes()))
            )
            .then(
                redisTemplate.expire(openTimeKey, Duration.ofMinutes(properties.getRedisKeyExpireMinutes()))
            )
            .then()
            .doOnSuccess(unused -> log.warn("熔断已激活 - 服务: {}", serviceKey))
            .onErrorResume(error -> {
                log.error("触发熔断异常 - 服务: {}, 错误: {}", serviceKey, error.getMessage());
                return Mono.empty();
            });
    }
    
    /**
     * 获取当前断路器状态
     * 
     * 状态判断逻辑：
     * 1. 查询Redis中的状态
     * 2. 如果是OPEN，检查是否到了探测时间
     * 3. 到了探测时间则转为HALF_OPEN
     * 4. 默认为CLOSED状态
     * 
     * @param serviceKey 服务标识
     * @return 当前状态
     */
    public Mono<CircuitState> getCurrentState(String serviceKey) {
        if (!properties.isEnabled()) {
            return Mono.just(CircuitState.CLOSED);
        }
        
        String stateKey = properties.getStateKey(serviceKey);
        String openTimeKey = properties.getOpenTimeKey(serviceKey);
        
        return redisTemplate.opsForValue()
            .get(stateKey)
            .flatMap(stateValue -> {
                if (CircuitState.OPEN.name().equals(stateValue)) {
                    // 检查是否到了探测时间
                    return redisTemplate.opsForValue()
                        .get(openTimeKey)
                        .map(openTimeStr -> {
                            try {
                                long openTime = Long.parseLong(openTimeStr);
                                long now = System.currentTimeMillis();
                                long timeoutMs = Duration.ofMinutes(properties.getOpenTimeoutMinutes()).toMillis();
                                
                                if (now - openTime >= timeoutMs) {
                                    // 到了探测时间，转为半开状态
                                    log.info("进入探测状态 - 服务: {}", serviceKey);
                                    return CircuitState.HALF_OPEN;
                                } else {
                                    // 仍在熔断期
                                    return CircuitState.OPEN;
                                }
                            } catch (NumberFormatException e) {
                                log.warn("熔断时间格式错误 - 服务: {}, 值: {}", serviceKey, openTimeStr);
                                return CircuitState.CLOSED;
                            }
                        });
                } else if (CircuitState.HALF_OPEN.name().equals(stateValue)) {
                    return Mono.just(CircuitState.HALF_OPEN);
                } else {
                    return Mono.just(CircuitState.CLOSED);
                }
            })
            .switchIfEmpty(Mono.just(CircuitState.CLOSED))
            .onErrorResume(error -> {
                log.error("获取断路器状态异常 - 服务: {}, 错误: {}", serviceKey, error.getMessage());
                // 异常时默认为正常状态，保证可用性
                return Mono.just(CircuitState.CLOSED);
            });
    }
    
    /**
     * 清除断路器状态（恢复为正常）
     * 
     * 清理操作：
     * 1. 删除熔断状态
     * 2. 删除熔断时间
     * 3. 可选：清除失败记录
     * 
     * @param serviceKey 服务标识
     * @return 操作完成的Mono
     */
    private Mono<Void> clearCircuitBreakerState(String serviceKey) {
        String stateKey = properties.getStateKey(serviceKey);
        String openTimeKey = properties.getOpenTimeKey(serviceKey);
        
        return redisTemplate.delete(stateKey)
            .then(redisTemplate.delete(openTimeKey))
            .then()
            .doOnSuccess(unused -> log.debug("断路器状态已清除 - 服务: {}", serviceKey));
    }
}
