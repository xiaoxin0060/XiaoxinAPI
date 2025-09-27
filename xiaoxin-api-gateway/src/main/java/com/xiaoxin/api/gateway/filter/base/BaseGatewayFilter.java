package com.xiaoxin.api.gateway.filter.base;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaoxin.api.gateway.config.properties.GatewayFilterProperties;
import com.xiaoxin.api.gateway.exception.GatewayException;
import com.xiaoxin.api.gateway.exception.GatewayExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * 网关过滤器基类 - 提供统一依赖注入、异常处理、开关控制
 * 
 * 模板方法模式：子类修理isEnabled()和filter()即可
 */
public abstract class BaseGatewayFilter implements GlobalFilter, Ordered {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /** JSON序列化工具 */
    @Autowired
    protected ObjectMapper objectMapper;
    
    /** 网关过滤器配置属性 */
    @Autowired
    protected GatewayFilterProperties gatewayProperties;

    /** 统一异常处理器 */
    @Autowired
    protected GatewayExceptionHandler exceptionHandler;

    /** 判断当前过滤器是否启用 */
    protected abstract boolean isEnabled();



    /**
     * 构建统一成功响应格式
     * 
     * 复用原有buildUnifiedResponse逻辑，确保响应格式一致性
     * 
     * 业务逻辑：
     * - 成功时：解析响应体为JSON对象，包装在data字段中
     * - 失败时：返回错误信息和空data
     * - 添加时间戳和接口元信息
     * 
     * 响应格式：
     * {
     *   "code": 200,
     *   "message": "调用成功",
     *   "data": { ... },
     *   "timestamp": 1641234567890
     * }
     * 
     * 技术特性：
     * - 智能JSON解析：先尝试解析为对象，失败则保持字符串
     * - 异常安全：解析失败不影响整体响应
     * - 向后兼容：保持与现有CustomGlobalFilter相同的格式
     * 
     * @param responseBody 原始响应体
     * @param interfaceInfo 接口信息（可选）
     * @param success 是否成功
     * @param errorMessage 错误信息（失败时）
     * @return 格式化后的JSON字符串
     */
    protected String buildUnifiedResponse(String responseBody, Object interfaceInfo, 
                                        boolean success, String errorMessage) {
        try {
            Map<String, Object> response = new HashMap<>();
            
            if (success) {
                response.put("code", 200);
                response.put("message", "调用成功");
                
                // 尝试解析响应体为JSON对象
                try {
                    Object data = objectMapper.readValue(responseBody, Object.class);
                    response.put("data", data);
                } catch (Exception e) {
                    // 解析失败时直接使用字符串
                    log.debug("响应体不是有效JSON，保持字符串格式: {}", responseBody);
                    response.put("data", responseBody);
                }
            } else {
                response.put("code", 500);
                response.put("message", "接口调用失败: " + errorMessage);
                response.put("data", null);
            }
            
            // 添加时间戳
            response.put("timestamp", System.currentTimeMillis());
            
            return objectMapper.writeValueAsString(response);
            
        } catch (Exception e) {
            // 降级处理：构建失败时返回简单错误响应
            log.error("构建响应格式失败", e);
            return "{\"code\":500,\"message\":\"系统错误\",\"data\":null,\"timestamp\":" + System.currentTimeMillis() + "}";
        }
    }

    /**
     * 记录过滤器执行指标
     * 
     * 监控维度：
     * - 过滤器名称
     * - 执行时间
     * - 成功/失败状态
     * - 异常类型
     * 
     * 可扩展接入：
     * - Micrometer指标
     * - 自定义监控系统
     * - APM追踪系统
     * 
     * @param filterName 过滤器名称
     * @param startTime 开始时间（毫秒）
     * @param success 是否成功
     * @param exception 异常信息（可选）
     */
    protected void recordFilterMetrics(String filterName, long startTime, 
                                     boolean success, Throwable exception) {
        long duration = System.currentTimeMillis() - startTime;
        
        if (gatewayProperties.getProxy().isEnableMetrics()) {
            log.debug("过滤器执行指标 - 名称: {}, 耗时: {}ms, 成功: {}", 
                     filterName, duration, success);
            
            if (exception != null) {
                log.debug("过滤器异常信息: {}", exception.getMessage());
            }
            
            // TODO: 集成Micrometer或其他监控系统
            // meterRegistry.timer("gateway.filter.duration")
            //     .tag("filter", filterName)
            //     .tag("success", String.valueOf(success))
            //     .record(duration, TimeUnit.MILLISECONDS);
        }
    }

    /** 获取安全配置 */
    protected GatewayFilterProperties.SecurityConfig getSecurityConfig() {
        return gatewayProperties.getSecurity();
    }

    /** 获取限流配置 */
    protected GatewayFilterProperties.RateLimitConfig getRateLimitConfig() {
        return gatewayProperties.getRateLimit();
    }

    /** 获取代理配置 */
    protected GatewayFilterProperties.ProxyConfig getProxyConfig() {
        return gatewayProperties.getProxy();
    }

    // ========== 统一异常处理便捷方法 ==========

    /** 处理网关异常 */
    protected Mono<Void> handleGatewayException(GatewayException exception, ServerWebExchange exchange) {
        return exceptionHandler.handleException(exception, exchange);
    }

    /** 处理通用异常 */
    protected Mono<Void> handleGenericException(Exception exception, ServerWebExchange exchange) {
        return exceptionHandler.handleGenericException(exception, exchange);
    }

    /** 认证失败响应 */
    protected Mono<Void> handleAuthFailed(ServerWebExchange exchange) {
        return handleGatewayException(GatewayExceptionHandler.authFailed(), exchange);
    }

    /** 接口不存在响应 */
    protected Mono<Void> handleInterfaceNotFound(ServerWebExchange exchange) {
        return handleGatewayException(GatewayExceptionHandler.interfaceNotFound(), exchange);
    }

    /** 限流响应 */
    protected Mono<Void> handleRateLimited(ServerWebExchange exchange) {
        return handleGatewayException(GatewayExceptionHandler.rateLimited(), exchange);
    }

    /** 系统错误响应 */
    protected Mono<Void> handleSystemError(Throwable cause, ServerWebExchange exchange) {
        return handleGatewayException(GatewayExceptionHandler.systemError(cause), exchange);
    }
    
    /** 配额不足响应 */
    protected Mono<Void> handleQuotaExceeded(ServerWebExchange exchange) {
        return handleGatewayException(GatewayExceptionHandler.quotaExceeded(), exchange);
    }
}
