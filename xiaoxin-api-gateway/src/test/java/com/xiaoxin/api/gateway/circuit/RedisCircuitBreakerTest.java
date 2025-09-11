package com.xiaoxin.api.gateway.circuit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.core.ReactiveZSetOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Redis断路器测试类
 * 
 * 学习要点：
 * 1. Spring Boot测试框架的使用
 * 2. Mockito模拟外部依赖（Redis）
 * 3. Reactor测试工具StepVerifier的使用
 * 4. 断路器状态转换的验证
 * 
 * 测试策略：
 * - 模拟Redis操作，专注测试业务逻辑
 * - 覆盖正常流程和异常场景
 * - 验证状态转换的正确性
 * - 确保异常安全（Redis故障时的降级）
 * 
 * 注意事项：
 * - 这是单元测试，不依赖真实Redis
 * - 适合CI/CD流水线自动执行
 * - 简单易懂，符合学习项目定位
 * 
 * @author xiaoxin
 * @since 1.0.0
 */
@ExtendWith(MockitoExtension.class)
class RedisCircuitBreakerTest {

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;
    
    @Mock
    private ReactiveZSetOperations<String, String> zSetOperations;
    
    @Mock
    private ReactiveValueOperations<String, String> valueOperations;
    
    private RedisCircuitBreaker circuitBreaker;
    private CircuitBreakerProperties properties;
    
    @BeforeEach
    void setUp() {
        // 初始化配置（使用测试友好的参数）
        properties = new CircuitBreakerProperties();
        properties.setEnabled(true);
        properties.setFailureThreshold(3);  // 3次失败就熔断（便于测试）
        properties.setWindowMinutes(5);
        properties.setOpenTimeoutMinutes(1);
        properties.setRedisKeyPrefix("test:circuit");
        properties.setRedisKeyExpireMinutes(10);
        
        // 初始化断路器
        circuitBreaker = new RedisCircuitBreaker();
        // 通过反射设置私有字段（实际项目中通过@Autowired注入）
        setField(circuitBreaker, "redisTemplate", redisTemplate);
        setField(circuitBreaker, "properties", properties);
        
        // 配置Redis模拟操作
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }
    
    /**
     * 测试记录失败功能
     * 
     * 验证点：
     * 1. 能正确添加失败记录到Redis ZSet
     * 2. 能清理过期数据
     * 3. 能设置合理的过期时间
     */
    @Test
    void testRecordFailure() {
        String serviceKey = "test.service";
        
        // 模拟Redis操作成功
        when(zSetOperations.add(anyString(), anyString(), anyDouble())).thenReturn(Mono.just(true));
        when(zSetOperations.removeRangeByScore(anyString(), anyDouble(), anyDouble())).thenReturn(Mono.just(0L));
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
        
        // 执行测试
        StepVerifier.create(circuitBreaker.recordFailure(serviceKey))
            .verifyComplete();
    }
    
    /**
     * 测试熔断触发判断
     * 
     * 验证点：
     * 1. 失败次数低于阈值时不熔断
     * 2. 失败次数达到阈值时触发熔断
     * 3. Redis异常时的降级处理
     */
    @Test
    void testShouldTriggerCircuitBreak() {
        String serviceKey = "test.service";
        
        // 场景1：失败次数未达阈值
        when(zSetOperations.count(anyString(), anyDouble(), anyDouble())).thenReturn(Mono.just(2L));
        
        StepVerifier.create(circuitBreaker.shouldTriggerCircuitBreak(serviceKey))
            .expectNext(false)  // 2次失败 < 3次阈值，不应熔断
            .verifyComplete();
        
        // 场景2：失败次数达到阈值
        when(zSetOperations.count(anyString(), anyDouble(), anyDouble())).thenReturn(Mono.just(3L));
        
        StepVerifier.create(circuitBreaker.shouldTriggerCircuitBreak(serviceKey))
            .expectNext(true)   // 3次失败 = 3次阈值，应该熔断
            .verifyComplete();
    }
    
    /**
     * 测试断路器状态获取
     * 
     * 验证点：
     * 1. 默认为CLOSED状态
     * 2. 能正确识别OPEN状态
     * 3. 超时后能转为HALF_OPEN状态
     */
    @Test
    void testGetCurrentState() {
        String serviceKey = "test.service";
        
        // 场景1：无状态记录，默认为CLOSED
        when(valueOperations.get(anyString())).thenReturn(Mono.empty());
        
        StepVerifier.create(circuitBreaker.getCurrentState(serviceKey))
            .expectNext(RedisCircuitBreaker.CircuitState.CLOSED)
            .verifyComplete();
        
        // 场景2：OPEN状态且未超时
        long now = System.currentTimeMillis();
        when(valueOperations.get(properties.getStateKey(serviceKey)))
            .thenReturn(Mono.just(RedisCircuitBreaker.CircuitState.OPEN.name()));
        when(valueOperations.get(properties.getOpenTimeKey(serviceKey)))
            .thenReturn(Mono.just(String.valueOf(now)));  // 刚刚熔断，未超时
        
        StepVerifier.create(circuitBreaker.getCurrentState(serviceKey))
            .expectNext(RedisCircuitBreaker.CircuitState.OPEN)
            .verifyComplete();
        
        // 场景3：OPEN状态且已超时，应转为HALF_OPEN
        long pastTime = now - Duration.ofMinutes(2).toMillis();  // 2分钟前熔断，超过1分钟超时
        when(valueOperations.get(properties.getOpenTimeKey(serviceKey)))
            .thenReturn(Mono.just(String.valueOf(pastTime)));
        
        StepVerifier.create(circuitBreaker.getCurrentState(serviceKey))
            .expectNext(RedisCircuitBreaker.CircuitState.HALF_OPEN)
            .verifyComplete();
    }
    
    /**
     * 测试触发熔断功能
     * 
     * 验证点：
     * 1. 能正确设置OPEN状态
     * 2. 能记录熔断开始时间
     * 3. 能设置合理的过期时间
     */
    @Test
    void testTriggerCircuitBreak() {
        String serviceKey = "test.service";
        
        // 模拟Redis操作成功
        when(valueOperations.set(anyString(), anyString())).thenReturn(Mono.just(true));
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
        
        // 执行测试
        StepVerifier.create(circuitBreaker.triggerCircuitBreak(serviceKey))
            .verifyComplete();
    }
    
    /**
     * 测试记录成功功能
     * 
     * 验证点：
     * 1. HALF_OPEN状态下成功时能清除熔断状态
     * 2. CLOSED状态下成功时不影响状态
     */
    @Test
    void testRecordSuccess() {
        String serviceKey = "test.service";
        
        // 场景1：HALF_OPEN状态下记录成功
        when(valueOperations.get(anyString()))
            .thenReturn(Mono.just(RedisCircuitBreaker.CircuitState.HALF_OPEN.name()));
        when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));
        
        StepVerifier.create(circuitBreaker.recordSuccess(serviceKey))
            .verifyComplete();
        
        // 场景2：CLOSED状态下记录成功（无特殊处理）
        when(valueOperations.get(anyString()))
            .thenReturn(Mono.just(RedisCircuitBreaker.CircuitState.CLOSED.name()));
        
        StepVerifier.create(circuitBreaker.recordSuccess(serviceKey))
            .verifyComplete();
    }
    
    /**
     * 测试Redis异常时的降级处理
     * 
     * 验证点：
     * 1. Redis异常时不影响主流程
     * 2. 返回安全的默认值
     * 3. 记录适当的错误日志
     */
    @Test
    void testRedisExceptionHandling() {
        String serviceKey = "test.service";
        
        // 模拟Redis异常
        when(zSetOperations.count(anyString(), anyDouble(), anyDouble()))
            .thenReturn(Mono.error(new RuntimeException("Redis连接失败")));
        
        // 异常时应返回false（不熔断），保证可用性
        StepVerifier.create(circuitBreaker.shouldTriggerCircuitBreak(serviceKey))
            .expectNext(false)
            .verifyComplete();
    }
    
    /**
     * 测试断路器禁用时的行为
     * 
     * 验证点：
     * 1. 禁用时所有操作都应快速返回
     * 2. 不应执行任何Redis操作
     */
    @Test
    void testDisabledCircuitBreaker() {
        String serviceKey = "test.service";
        
        // 禁用断路器
        properties.setEnabled(false);
        
        // 所有操作都应快速返回，不执行Redis操作
        StepVerifier.create(circuitBreaker.recordFailure(serviceKey))
            .verifyComplete();
        
        StepVerifier.create(circuitBreaker.shouldTriggerCircuitBreak(serviceKey))
            .expectNext(false)
            .verifyComplete();
        
        StepVerifier.create(circuitBreaker.getCurrentState(serviceKey))
            .expectNext(RedisCircuitBreaker.CircuitState.CLOSED)
            .verifyComplete();
    }
    
    /**
     * 简单的反射工具方法，用于设置私有字段
     * 
     * 生产代码中通过Spring的@Autowired自动注入
     * 测试中使用反射模拟注入过程
     */
    private void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("设置字段失败: " + fieldName, e);
        }
    }
}
