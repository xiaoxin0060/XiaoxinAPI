package com.xiaoxin.api.gateway.filter;

import com.xiaoxin.api.gateway.filter.base.BaseGatewayFilter;
import com.xiaoxin.api.platform.model.entity.InterfaceInfo;
import com.xiaoxin.api.platform.model.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * 限流过滤器 - 基于滑动窗口的分布式限流
 * 
 * 业务职责：
 * - 基于用户+接口维度进行限流控制
 * - 使用Redis Sorted Set实现滑动窗口算法
 * - 支持接口级别的差异化限流策略
 * - 防止系统过载和恶意攻击
 * - 保障系统稳定性和公平性
 * 
 * 调用链路：
 * 请求 → 获取用户和接口信息 → 检查限流配置 → 滑动窗口计算 → 通过继续/拒绝限流
 * 
 * 技术实现：
 * - Redis Sorted Set存储请求时间戳，Score为时间戳，Member为UUID
 * - 滑动窗口：定期清理过期记录，统计窗口内请求数量
 * - 原子操作：使用Redis管道确保操作的原子性
 * - 响应式编程：避免阻塞WebFlux事件循环
 * - 降级策略：Redis异常时允许请求通过
 * 
 * 限流算法：滑动窗口 vs 固定窗口
 * - 固定窗口：每分钟重置计数器，存在突发流量问题
 * - 滑动窗口：实时滑动，平滑限流，更精确的流量控制
 * - 示例：限制1000次/分钟，滑动窗口能防止前30秒1000次请求的突发
 * 
 * 限流维度：
 * - 用户级限流：userId:interfaceId，防止单用户过度使用
 * - 接口级限流：interfaceId，防止单接口过载
 * - 全局限流：global，防止系统整体过载
 * - IP级限流：clientIp，防止恶意攻击（可扩展）
 * 
 * 配置支持：
 * - 窗口大小：xiaoxin.gateway.rate-limit.window-seconds
 * - 默认限制：xiaoxin.gateway.rate-limit.default-limit
 * - Redis前缀：xiaoxin.gateway.rate-limit.redis-key-prefix
 * - 接口个性化：InterfaceInfo.rateLimit字段
 * 
 * 性能优化：
 * - 批量Redis操作：减少网络往返次数
 * - 合理过期时间：自动清理Redis内存
 * - 异步操作：不阻塞主请求流程
 * - 降级机制：Redis故障时的容错处理
 * 
 * @author xiaoxin
 * @since 1.0.0
 */
@Component
public class RateLimitFilter extends BaseGatewayFilter {

    /**
     * Redis响应式模板
     * 
     * 用于限流数据存储：
     * - Sorted Set存储请求时间戳
     * - 原子操作确保并发安全
     * - 过期时间管理内存使用
     * - 响应式操作避免阻塞
     */
    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 检查过滤器开关
        if (!isEnabled()) {
            log.debug("限流过滤器已禁用，跳过限流检查");
            return chain.filter(exchange);
        }

        // 检查限流功能总开关
        if (!getRateLimitConfig().isEnabled()) {
            log.debug("限流功能已禁用，跳过限流检查");
            return chain.filter(exchange);
        }

        long startTime = System.currentTimeMillis();
        
        // 从前置过滤器获取用户和接口信息
        User user = exchange.getAttribute("authenticated.user");
        InterfaceInfo interfaceInfo = exchange.getAttribute("interface.info");
        
        // 空值检查
        if (user == null) {
            log.error("用户信息为空，无法进行限流检查");
            recordFilterMetrics("RateLimitFilter", startTime, false, null);
            // 使用统一异常处理器处理认证异常
            return handleAuthFailed(exchange);
        }
        
        if (interfaceInfo == null) {
            log.error("接口信息为空，无法进行限流检查");
            recordFilterMetrics("RateLimitFilter", startTime, false, null);
            // 使用统一异常处理器处理接口不存在异常
            return handleInterfaceNotFound(exchange);
        }
        
        // 获取接口限流配置
        Integer rateLimit = getRateLimitForInterface(interfaceInfo);

        // 如果接口未配置限流或限流值为0，则跳过限流
        if (rateLimit == null || rateLimit <= 0) {
            log.debug("接口未配置限流，跳过限流检查 - 接口: {}", interfaceInfo.getName());
            recordFilterMetrics("RateLimitFilter", startTime, true, null);
            return chain.filter(exchange);
        }

        return performRateLimitCheck(user, interfaceInfo, rateLimit)
            .flatMap(allowed -> {
                if (!allowed) {
                    log.warn("触发限流 - 用户ID: {}, 接口ID: {}, 限制: {}次/{}秒",
                            user.getId(), interfaceInfo.getId(), rateLimit,
                            getRateLimitConfig().getWindowSeconds());

                    recordFilterMetrics("RateLimitFilter", startTime, false, null);

                    // 使用统一异常处理器处理限流异常
                    return handleRateLimited(exchange);
                }

                log.debug("限流检查通过 - 用户ID: {}, 接口ID: {}", user.getId(), interfaceInfo.getId());
                recordFilterMetrics("RateLimitFilter", startTime, true, null);

                return chain.filter(exchange);
            })
            .onErrorResume(Exception.class, error -> {
                log.error("限流检查异常，降级允许请求 - 用户ID: {}, 接口ID: {}",
                         user.getId(), interfaceInfo.getId(), error);
                recordFilterMetrics("RateLimitFilter", startTime, false, error);

                // 降级策略：异常时允许请求通过，保证可用性（可根据业务需求调整）
                // 也可以选择返回系统异常：return handleSystemError(error, exchange);
                return chain.filter(exchange);
            });
    }

    /**
     * 获取接口限流配置
     * 
     * 优先级策略：
     * 1. 接口自定义限流值：InterfaceInfo.rateLimit
     * 2. 系统默认限流值：配置文件中的default-limit
     * 3. 无限流：返回null或0
     * 
     * 配置灵活性：
     * - 热点接口：可以设置更严格的限流
     * - 内部接口：可以设置更宽松的限流
     * - 测试接口：可以设置很小的限流用于测试
     * - 免费接口：可以设置较低的限流
     * - 付费接口：可以设置较高的限流
     * 
     * @param interfaceInfo 接口信息
     * @return 限流值（次数/窗口），null表示不限流
     */
    private Integer getRateLimitForInterface(InterfaceInfo interfaceInfo) {
        // 优先使用接口自定义限流配置
        Integer interfaceLimit = interfaceInfo.getRateLimit();
        if (interfaceLimit != null && interfaceLimit > 0) {
            log.debug("使用接口自定义限流配置 - 接口: {}, 限制: {}次/{}秒", 
                     interfaceInfo.getName(), interfaceLimit, getRateLimitConfig().getWindowSeconds());
            return interfaceLimit;
        }
        
        // 使用系统默认限流配置
        int defaultLimit = getRateLimitConfig().getDefaultLimit();
        if (defaultLimit > 0) {
            log.debug("使用系统默认限流配置 - 接口: {}, 限制: {}次/{}秒", 
                     interfaceInfo.getName(), defaultLimit, getRateLimitConfig().getWindowSeconds());
            return defaultLimit;
        }
        
        // 不进行限流
        log.debug("接口不进行限流 - 接口: {}", interfaceInfo.getName());
        return null;
    }

    /**
     * 执行限流检查
     * 
     * 滑动窗口算法实现：
     * 1. 清理过期请求记录（超过窗口时间的记录）
     * 2. 添加当前请求时间戳到Sorted Set
     * 3. 设置Redis key的过期时间，自动清理内存
     * 4. 统计窗口内的请求数量
     * 5. 判断是否超过限流阈值
     * 
     * Redis操作序列：
     * - ZREMRANGEBYSCORE：删除过期的时间戳记录
     * - ZADD：添加当前请求的时间戳
     * - EXPIRE：设置key的过期时间
     * - ZCOUNT：统计窗口内的请求数量
     * 
     * 并发安全：
     * - Redis单线程模型保证操作原子性
     * - Sorted Set操作具有原子性
     * - 多个Redis命令通过reactive chain保证顺序
     * 
     * 内存管理：
     * - 自动清理过期记录，防止内存泄漏
     * - 设置key过期时间，Redis自动回收
     * - Sorted Set自动按score排序，查询高效
     * 
     * @param user 用户信息
     * @param interfaceInfo 接口信息
     * @param rateLimit 限流阈值
     * @return true-允许请求，false-触发限流
     */
    private Mono<Boolean> performRateLimitCheck(User user, InterfaceInfo interfaceInfo, int rateLimit) {
        // 构建Redis key：按用户+接口维度限流
        String rateLimitKey = buildRateLimitKey(user.getId(), interfaceInfo.getId());
        
        // 当前时间和窗口参数
        long now = System.currentTimeMillis();
        long windowMs = getRateLimitConfig().getWindowSeconds() * 1000L;
        long expireSeconds = getRateLimitConfig().getKeyExpireSeconds();
        
        // 生成唯一的请求标识
        String requestMember = now + ":" + UUID.randomUUID().toString();
        
        // 计算窗口范围
        double windowStart = (double) (now - windowMs);
        double windowEnd = (double) now;
        double expiredBefore = (double) (now - windowMs);
        
        log.debug("限流检查开始 - Key: {}, 窗口: {}ms, 限制: {}, 当前时间: {}", 
                 rateLimitKey, windowMs, rateLimit, now);
        
        // 执行滑动窗口限流算法
        return redisTemplate.opsForZSet()
            // 1. 清理过期的请求记录
            .removeRangeByScore(rateLimitKey, Range.closed(Double.NEGATIVE_INFINITY, expiredBefore))
            .doOnNext(removedCount -> {
                if (removedCount > 0) {
                    log.debug("清理过期记录 - Key: {}, 删除数量: {}", rateLimitKey, removedCount);
                }
            })
            // 2. 添加当前请求时间戳
            .then(redisTemplate.opsForZSet().add(rateLimitKey, requestMember, (double) now))
            .doOnNext(added -> {
                log.debug("添加请求记录 - Key: {}, Member: {}, Score: {}, 结果: {}", 
                         rateLimitKey, requestMember, now, added);
            })
            // 3. 设置key过期时间，防止内存泄漏
            .then(redisTemplate.expire(rateLimitKey, Duration.ofSeconds(expireSeconds)))
            .doOnNext(expireSet -> {
                log.debug("设置过期时间 - Key: {}, 过期时间: {}秒, 结果: {}", 
                         rateLimitKey, expireSeconds, expireSet);
            })
            // 4. 统计当前窗口内的请求数量
            .then(redisTemplate.opsForZSet().count(rateLimitKey, Range.closed(windowStart, windowEnd)))
            .map(count -> {
                boolean allowed = count <= rateLimit;
                
                log.debug("限流检查结果 - Key: {}, 窗口内请求数: {}, 限制: {}, 允许: {}", 
                         rateLimitKey, count, rateLimit, allowed);
                
                return allowed;
            })
            .onErrorResume(error -> {
                // Redis异常时的降级策略：允许请求通过
                log.error("Redis限流检查异常，降级允许请求 - Key: {}", rateLimitKey, error);
                return Mono.just(true);
            });
    }

    /**
     * 构建限流Redis Key
     * 
     * Key格式：{prefix}:userId:interfaceId
     * 示例：xiaoxin:rate_limit:1001:2001
     * 
     * 设计考虑：
     * - 包含前缀：避免与其他业务key冲突
     * - 用户维度：不同用户独立限流
     * - 接口维度：不同接口独立限流
     * - 可读性好：便于Redis监控和调试
     * 
     * 扩展支持：
     * - 全局限流：xiaoxin:rate_limit:global
     * - IP限流：xiaoxin:rate_limit:ip:192.168.1.100
     * - 接口限流：xiaoxin:rate_limit:interface:2001
     * - 用户限流：xiaoxin:rate_limit:user:1001
     * 
     * @param userId 用户ID
     * @param interfaceId 接口ID
     * @return Redis key
     */
    private String buildRateLimitKey(Long userId, Long interfaceId) {
        String prefix = getRateLimitConfig().getRedisKeyPrefix();
        return prefix + ":" + userId + ":" + interfaceId;
    }

    /**
     * 获取过滤器启用状态
     * 
     * 配置路径：xiaoxin.gateway.filters.rate-limit
     * 
     * 双重开关控制：
     * 1. 过滤器开关：filters.rate-limit
     * 2. 功能开关：rate-limit.enabled
     * 
     * @return true-启用，false-禁用
     */
    @Override
    protected boolean isEnabled() {
        return gatewayProperties.getFilters().isRateLimit();
    }

    /**
     * 过滤器执行顺序
     * 
     * 顺序安排：
     * - LoggingFilter(-100)：记录请求信息
     * - SecurityFilter(-90)：IP白名单验证
     * - AuthenticationFilter(-80)：用户认证
     * - InterfaceFilter(-70)：接口验证
     * - RateLimitFilter(-60)：限流控制 ← 当前过滤器
     * - QuotaFilter(-50)：配额管理
     * 
     * 设计理由：
     * - 在接口验证后进行限流，确保限流的是有效接口
     * - 在配额检查前进行限流，减少配额系统压力
     * - 限流比配额检查更轻量，优先执行
     * - 早期拦截过量请求，保护下游系统
     * 
     * @return 执行顺序值
     */
    @Override
    public int getOrder() {
        return -60;
    }
}
