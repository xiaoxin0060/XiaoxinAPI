package com.xiaoxin.api.gateway.filter;

import com.xiaoxin.api.gateway.filter.base.BaseGatewayFilter;
import com.xiaoxin.api.platform.model.entity.InterfaceInfo;
import com.xiaoxin.api.platform.model.entity.User;
import com.xiaoxin.api.platform.service.InnerUserInterfaceInfoService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 配额过滤器 - 用户接口调用次数管理
 * 
 * 职责：检查用户接口调用配额，预扣减调用次数，防止配额穿透
 * 机制：Dubbo RPC调用平台服务，原子操作保证并发安全
 */
public class QuotaFilter extends BaseGatewayFilter {

    /**
     * 内部用户接口信息服务
     * 
     * 使用Dubbo RPC调用平台服务：
     * - preConsume：预扣减用户接口调用次数
     * - invokeCount：记录成功调用次数（在响应处理阶段）
     * - 查询用户配额状态和剩余次数
     * 
     * 注意事项：
     * - Dubbo调用是同步阻塞的，需要在专用线程池执行
     * - 配额操作涉及数据库事务，需要处理并发和一致性问题
     * - 考虑添加本地缓存减少频繁的RPC调用
     * - 需要处理配额服务不可用的降级情况
     */
    @DubboReference
    private InnerUserInterfaceInfoService innerUserInterfaceInfoService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 检查过滤器开关
        if (!isEnabled()) {
            log.debug("配额过滤器已禁用，跳过配额检查");
            return chain.filter(exchange);
        }

        long startTime = System.currentTimeMillis();
        
        // 从前置过滤器获取用户和接口信息
        User user = exchange.getAttribute("authenticated.user");
        InterfaceInfo interfaceInfo = exchange.getAttribute("interface.info");
        
        // 空值检查
        if (user == null) {
            log.error("用户信息为空，无法进行配额检查");
            recordFilterMetrics("QuotaFilter", startTime, false, null);
            return handleAuthFailed(exchange);
        }
        
        if (interfaceInfo == null) {
            log.error("接口信息为空，无法进行配额检查");
            recordFilterMetrics("QuotaFilter", startTime, false, null);
            return handleAuthFailed(exchange);
        }
        
        return performQuotaCheck(user, interfaceInfo)
            .flatMap(quotaAvailable -> {
                if (!quotaAvailable) {
                    log.warn("配额不足 - 用户ID: {}, 接口ID: {}, 接口名称: {}", 
                            user.getId(), interfaceInfo.getId(), interfaceInfo.getName());
                    
                    recordFilterMetrics("QuotaFilter", startTime, false, null);
                    
                    return handleQuotaExceeded(exchange);
                }
                
                log.debug("配额检查通过 - 用户ID: {}, 接口ID: {}", user.getId(), interfaceInfo.getId());
                recordFilterMetrics("QuotaFilter", startTime, true, null);
                
                return chain.filter(exchange);
            })
            .onErrorResume(Exception.class, error -> {
                log.error("配额检查异常 - 用户ID: {}, 接口ID: {}", 
                         user.getId(), interfaceInfo.getId(), error);
                recordFilterMetrics("QuotaFilter", startTime, false, error);
                
                // 降级策略选择：
                // 1. 严格模式：配额服务异常时拒绝请求（推荐生产环境）
                // 2. 宽松模式：配额服务异常时允许请求（推荐开发环境）
                return handleQuotaServiceError(exchange, error);
            });
    }

    /**
     * 执行配额检查和预扣减
     * 
     * 预扣减机制说明：
     * - 目的：防止高并发下的配额穿透问题
     * - 原理：请求开始时先扣减配额，调用成功后记录统计
     * - 优势：避免"检查-使用"之间的竞态条件
     * - 风险：调用失败时可能造成配额浪费（业务可选择是否回滚）
     * 
     * 数据一致性考虑：
     * - 原子操作：preConsume使用数据库行锁保证原子性
     * - 幂等性：重复调用preConsume应该有相同的结果
     * - 回滚机制：调用失败时的配额回滚策略（可选实现）
     * - 监控告警：配额异常时的监控和告警机制
     * 
     * 性能优化：
     * - 缓存策略：缓存用户配额信息，减少数据库查询
     * - 批量操作：对于批量请求可以考虑批量扣减
     * - 异步处理：非关键路径的配额更新可以异步处理
     * - 分库分表：大用户量下的配额数据分片策略
     * 
     * @param user 用户信息
     * @param interfaceInfo 接口信息
     * @return true-配额充足并已扣减，false-配额不足
     */
    private Mono<Boolean> performQuotaCheck(User user, InterfaceInfo interfaceInfo) {
        return Mono.fromCallable(() -> {
            log.debug("开始配额检查 - 用户ID: {}, 接口ID: {}, 接口名称: {}", 
                     user.getId(), interfaceInfo.getId(), interfaceInfo.getName());
            
            // 复用原有预扣减逻辑，保持与现有系统的兼容性
            boolean quotaAvailable = innerUserInterfaceInfoService.preConsume(
                interfaceInfo.getId(), user.getId());
            
            if (quotaAvailable) {
                log.debug("配额预扣减成功 - 用户ID: {}, 接口ID: {}", 
                         user.getId(), interfaceInfo.getId());
            } else {
                log.warn("配额预扣减失败 - 用户ID: {}, 接口ID: {}, 可能原因: 配额不足或未开通", 
                        user.getId(), interfaceInfo.getId());
            }
            
            return quotaAvailable;
        })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()) // 在专用线程池执行阻塞操作
        .onErrorMap(Exception.class, error -> {
            log.error("配额预扣减异常 - 用户ID: {}, 接口ID: {}", 
                     user.getId(), interfaceInfo.getId(), error);
            return new QuotaException("配额服务异常", error);
        });
    }

    /**
     * 处理配额服务异常
     * 
     * 降级策略设计：
     * 1. 严格模式：任何配额服务异常都拒绝请求
     *    - 优点：数据一致性强，防止配额滥用
     *    - 缺点：可用性较低，依赖配额服务稳定性
     * 
     * 2. 宽松模式：配额服务异常时允许请求通过
     *    - 优点：可用性高，用户体验好
     *    - 缺点：可能造成配额超用，需要后续补偿
     * 
     * 3. 智能模式：根据异常类型和用户等级决策
     *    - 网络超时：允许VIP用户通过，拒绝普通用户
     *    - 服务宕机：短期允许通过，长期拒绝
     *    - 数据库异常：拒绝所有请求
     * 
     * 当前实现：严格模式（生产环境推荐）
     * 可通过配置开关切换降级策略
     * 
     * @param exchange ServerWebExchange对象
     * @param error 异常信息
     * @return 错误响应Mono
     */
    private Mono<Void> handleQuotaServiceError(ServerWebExchange exchange, Throwable error) {
        // 可以通过配置控制降级策略
        boolean strictMode = true; // 可配置化：xiaoxin.gateway.quota.strict-mode
        
        if (strictMode) {
            // 严格模式：配额服务异常时拒绝请求
            log.error("配额服务异常，严格模式下拒绝请求", error);
            return handleSystemError(error, exchange);
        } else {
            // 宽松模式：配额服务异常时允许请求通过
            log.warn("配额服务异常，宽松模式下允许请求通过", error);
            // 注意：这里需要特殊标记，避免在响应阶段重复扣减配额
            exchange.getAttributes().put("quota.bypass", true);
            return Mono.empty(); // 继续执行后续过滤器
        }
    }

    /**
     * 获取过滤器启用状态
     * 
     * 配置路径：xiaoxin.gateway.filters.quota
     * 
     * 配额管理是API网关的核心功能，通常建议始终启用
     * 只有在特殊场景下（如内部测试、开发调试）才考虑禁用
     * 
     * @return true-启用，false-禁用
     */
    @Override
    protected boolean isEnabled() {
        return gatewayProperties.getFilters().isQuota();
    }

    /**
     * 过滤器执行顺序
     * 
     * 顺序安排：
     * - LoggingFilter(-100)：记录请求信息
     * - SecurityFilter(-90)：IP白名单验证
     * - AuthenticationFilter(-80)：用户认证
     * - InterfaceFilter(-70)：接口验证
     * - RateLimitFilter(-60)：限流控制
     * - QuotaFilter(-50)：配额管理 ← 当前过滤器
     * - ProxyFilter(-40)：动态代理
     * 
     * 设计理由：
     * - 在限流后进行配额检查，限流通过的请求才检查配额
     * - 在代理前完成配额扣减，确保扣减的都是有效请求
     * - 配额检查涉及数据库事务，需要在请求真正处理前完成
     * - 为计费系统提供准确的调用统计数据
     * 
     * 性能考虑：
     * - 配额检查比代理调用轻量，优先执行
     * - 早期拦截配额不足的请求，减少资源浪费
     * - 配额数据用于后续的计费和统计分析
     * 
     * @return 执行顺序值
     */
    @Override
    public int getOrder() {
        return -50;
    }

    /**
     * 配额异常类
     * 
     * 用于配额检查过程中的异常处理：
     * - 配额服务不可用
     * - 配额数据异常
     * - 扣减操作失败
     * - 网络通信异常
     */
    private static class QuotaException extends RuntimeException {
        public QuotaException(String message) {
            super(message);
        }
        
        public QuotaException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
