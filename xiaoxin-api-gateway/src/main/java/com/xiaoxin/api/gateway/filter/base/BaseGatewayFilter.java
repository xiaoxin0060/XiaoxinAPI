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
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 网关过滤器基类
 * 
 * 设计模式：模板方法模式
 * 
 * 核心职责：
 * 1. 提供通用的依赖注入点（ObjectMapper、配置等）
 * 2. 统一错误处理机制和响应格式
 * 3. 统一日志记录规范
 * 4. 提供过滤器开关控制能力
 * 5. 抽象通用行为，简化子类实现
 * 
 * 技术特性：
 * - 抽象基类：定义过滤器通用结构
 * - 依赖注入：集中管理公共依赖
 * - 模板方法：提供可重写的钩子方法
 * - 异常安全：统一的异常处理和降级
 * 
 * 继承关系：
 * BaseGatewayFilter (抽象基类)
 *   ├── LoggingFilter (日志记录)
 *   ├── SecurityFilter (安全验证)
 *   ├── AuthenticationFilter (用户认证)
 *   ├── InterfaceFilter (接口验证)
 *   ├── RateLimitFilter (限流控制)
 *   ├── QuotaFilter (配额管理)
 *   ├── ProxyFilter (动态代理)
 *   └── ResponseFilter (响应处理)
 * 
 * 使用示例：
 * <pre>
 * {@code
 * @Component
 * public class CustomFilter extends BaseGatewayFilter {
 *     @Override
 *     public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
 *         if (!isEnabled()) {
 *             return chain.filter(exchange);
 *         }
 *         
 *         // 业务逻辑
 *         if (businessValidationFailed) {
 *             return handleNoAuth(exchange.getResponse());
 *         }
 *         
 *         return chain.filter(exchange);
 *     }
 *     
 *     @Override
 *     protected boolean isEnabled() {
 *         return gatewayProperties.getFilters().isCustom();
 *     }
 * }
 * }
 * </pre>
 * 
 * @author xiaoxin
 * @since 1.0.0
 */
public abstract class BaseGatewayFilter implements GlobalFilter, Ordered {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * JSON序列化工具
     * 
     * 用途：
     * - 构建统一格式的错误响应
     * - 解析和格式化API响应数据
     * - 处理配置参数的序列化
     */
    @Autowired
    protected ObjectMapper objectMapper;
    
    /**
     * 网关过滤器配置属性
     * 
     * 提供：
     * - 过滤器开关控制
     * - 业务参数配置
     * - 多环境差异化设置
     */
    @Autowired
    protected GatewayFilterProperties gatewayProperties;

    /**
     * 统一异常处理器
     * 
     * 学习要点：
     * - Spring依赖注入的使用
     * - 统一异常处理的设计思想
     * - 代码复用和解耦的实现
     */
    @Autowired
    protected GatewayExceptionHandler exceptionHandler;

    /**
     * 判断当前过滤器是否启用
     * 
     * 抽象方法，子类必须实现：
     * - 返回对应的配置开关状态
     * - 支持运行时动态开关
     * - 用于故障排查和灰度发布
     * 
     * @return true-启用，false-禁用
     */
    protected abstract boolean isEnabled();

    /**
     * 统一的无权限处理
     * 
     * 业务逻辑：
     * - 设置HTTP 403 Forbidden状态码
     * - 直接完成响应，不返回响应体
     * - 保持与原有CustomGlobalFilter一致的行为
     * 
     * 适用场景：
     * - IP白名单验证失败
     * - 签名验证失败
     * - 接口权限不足
     * - 接口状态异常
     * 
     * 技术实现：
     * - 使用ServerHttpResponse.setComplete()快速结束请求
     * - 不构建响应体，减少网络传输
     * - 返回Mono<Void>符合WebFlux响应式编程
     * 
     * @param response HTTP响应对象
     * @return 完成的Mono，表示响应已结束
     */
    protected Mono<Void> handleNoAuth(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return response.setComplete();
    }

    /**
     * 构建统一错误响应
     * 
     * 业务场景：
     * - 限流触发时的错误响应
     * - 配额不足时的错误响应
     * - 代理失败时的错误响应
     * - 系统异常时的降级响应
     * 
     * 响应格式：
     * {
     *   "code": 429,
     *   "message": "触发限流，请稍后重试",
     *   "timestamp": 1641234567890
     * }
     * 
     * 技术实现：
     * - 使用Jackson ObjectMapper序列化JSON
     * - 设置正确的Content-Type头
     * - 使用DataBuffer写入响应体
     * - 异常安全：序列化失败时降级处理
     * 
     * Java高级特性：
     * - try-with-resources：自动资源管理
     * - StandardCharsets：避免编码问题
     * - DataBuffer：Spring WebFlux的缓冲区抽象
     * 
     * @param response HTTP响应对象
     * @param message 错误消息
     * @param status HTTP状态码
     * @return 包含错误响应的Mono
     */
    protected Mono<Void> writeErrorResponse(ServerHttpResponse response, 
                                          String message, HttpStatus status) {
        try {
            // 构建标准错误响应格式
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("code", status.value());
            errorResponse.put("message", message);
            errorResponse.put("timestamp", System.currentTimeMillis());
            
            // 序列化为JSON字符串
            String errorJson = objectMapper.writeValueAsString(errorResponse);
            
            // 设置响应头和状态码
            response.setStatusCode(status);
            response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");
            
            // 写入响应体
            DataBuffer buffer = response.bufferFactory().wrap(errorJson.getBytes(StandardCharsets.UTF_8));
            return response.writeWith(Mono.just(buffer));
            
        } catch (Exception e) {
            // 降级处理：序列化失败时返回简单响应
            log.error("构建错误响应失败，错误信息: {}", message, e);
            response.setStatusCode(status);
            return response.setComplete();
        }
    }

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

    /**
     * 获取安全配置快捷方法
     * 
     * 简化子类访问配置的代码
     */
    protected GatewayFilterProperties.SecurityConfig getSecurityConfig() {
        return gatewayProperties.getSecurity();
    }

    /**
     * 获取限流配置快捷方法
     */
    protected GatewayFilterProperties.RateLimitConfig getRateLimitConfig() {
        return gatewayProperties.getRateLimit();
    }

    /**
     * 获取代理配置快捷方法
     */
    protected GatewayFilterProperties.ProxyConfig getProxyConfig() {
        return gatewayProperties.getProxy();
    }

    // ========== 统一异常处理便捷方法 ==========

    /**
     * 处理网关异常
     * 
     * 学习要点：
     * - 统一异常处理的调用方式
     * - 异常处理的责任分离
     * - 过滤器代码的简化
     * 
     * 使用示例：
     * <pre>
     * // 在过滤器中直接抛出异常，由统一处理器处理
     * if (user == null) {
     *     return handleGatewayException(GatewayExceptionHandler.authFailed(), exchange);
     * }
     * </pre>
     * 
     * @param exception 网关异常
     * @param exchange WebFlux交换对象
     * @return 异常响应Mono
     */
    protected Mono<Void> handleGatewayException(GatewayException exception, ServerWebExchange exchange) {
        return exceptionHandler.handleException(exception, exchange);
    }

    /**
     * 处理通用异常
     * 
     * 将非GatewayException的异常包装为系统异常进行处理
     * 
     * @param exception 通用异常
     * @param exchange WebFlux交换对象
     * @return 异常响应Mono
     */
    protected Mono<Void> handleGenericException(Exception exception, ServerWebExchange exchange) {
        return exceptionHandler.handleGenericException(exception, exchange);
    }

    /**
     * 快捷创建认证失败异常响应
     * 
     * @param exchange WebFlux交换对象
     * @return 认证失败响应Mono
     */
    protected Mono<Void> handleAuthFailed(ServerWebExchange exchange) {
        return handleGatewayException(GatewayExceptionHandler.authFailed(), exchange);
    }

    /**
     * 快捷创建接口不存在异常响应
     * 
     * @param exchange WebFlux交换对象
     * @return 接口不存在响应Mono
     */
    protected Mono<Void> handleInterfaceNotFound(ServerWebExchange exchange) {
        return handleGatewayException(GatewayExceptionHandler.interfaceNotFound(), exchange);
    }

    /**
     * 快捷创建限流异常响应
     * 
     * @param exchange WebFlux交换对象
     * @return 限流响应Mono
     */
    protected Mono<Void> handleRateLimited(ServerWebExchange exchange) {
        return handleGatewayException(GatewayExceptionHandler.rateLimited(), exchange);
    }

    /**
     * 快捷创建系统错误异常响应
     * 
     * @param cause 原始异常
     * @param exchange WebFlux交换对象
     * @return 系统错误响应Mono
     */
    protected Mono<Void> handleSystemError(Throwable cause, ServerWebExchange exchange) {
        return handleGatewayException(GatewayExceptionHandler.systemError(cause), exchange);
    }
}
