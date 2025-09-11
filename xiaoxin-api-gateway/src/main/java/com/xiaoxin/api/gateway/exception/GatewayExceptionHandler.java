package com.xiaoxin.api.gateway.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 网关统一异常处理器 - 简化版
 * 
 * 学习要点：
 * 1. 统一异常处理的设计思想
 * 2. Jackson JSON序列化的使用
 * 3. WebFlux响应式编程模型
 * 4. Spring依赖注入的实际应用
 * 5. 异常安全的编程实践
 * 
 * 核心职责：
 * - 将GatewayException转换为统一的JSON响应
 * - 设置正确的HTTP状态码和响应头
 * - 记录异常日志便于问题排查
 * - 提供异常降级处理机制
 * 
 * 技术特性：
 * - 响应式编程：支持WebFlux的Mono返回
 * - 异常安全：处理异常中的异常情况
 * - 编码安全：使用UTF-8避免中文乱码
 * - 格式统一：所有异常返回相同的JSON结构
 * 
 * @author xiaoxin
 * @since 1.0.0
 */
@Component
public class GatewayExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    /**
     * JSON序列化工具
     * 
     * 学习要点：
     * - Spring Boot自动配置的ObjectMapper
     * - 依赖注入的使用方式
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 处理网关异常
     * 
     * 处理流程：
     * 1. 记录异常日志
     * 2. 构建错误响应对象
     * 3. 序列化为JSON字符串
     * 4. 设置HTTP状态码和响应头
     * 5. 写入响应体返回给客户端
     * 
     * 学习要点：
     * - 异常处理的完整流程
     * - 响应式编程的错误处理
     * - 异常安全的编程实践
     * 
     * @param exception 网关异常
     * @param exchange WebFlux交换对象
     * @return 异常响应的Mono
     */
    public Mono<Void> handleException(GatewayException exception, ServerWebExchange exchange) {
        try {
            // 1. 记录异常日志
            logException(exception, exchange);
            
            // 2. 构建错误响应
            Map<String, Object> errorResponse = buildErrorResponse(exception, exchange);
            
            // 3. 序列化为JSON
            String jsonResponse = objectMapper.writeValueAsString(errorResponse);
            
            // 4. 写入HTTP响应
            return writeErrorResponse(exchange.getResponse(), jsonResponse, exception.getHttpStatus());
            
        } catch (Exception e) {
            // 异常处理中的异常降级
            log.error("处理网关异常时发生错误", e);
            return handleProcessingError(exchange.getResponse(), exception);
        }
    }

    /**
     * 处理通用异常
     * 
     * 对于非GatewayException的异常，包装成系统内部错误
     * 
     * 学习要点：
     * - 异常的分类和转换
     * - 系统异常的统一处理
     * 
     * @param exception 通用异常
     * @param exchange WebFlux交换对象
     * @return 异常响应的Mono
     */
    public Mono<Void> handleGenericException(Exception exception, ServerWebExchange exchange) {
        // 将通用异常包装为GatewayException
        GatewayException gatewayException = new GatewayException(
            GatewayErrorCode.INTERNAL_ERROR, exception);
        
        return handleException(gatewayException, exchange);
    }

    /**
     * 记录异常日志
     * 
     * 学习要点：
     * - SLF4J日志框架的使用
     * - 结构化日志的设计
     * - 从Exchange中提取上下文信息
     * 
     * @param exception 异常对象
     * @param exchange WebFlux交换对象
     */
    private void logException(GatewayException exception, ServerWebExchange exchange) {
        // 提取请求信息
        String path = exchange.getRequest().getPath().value();
        String method = exchange.getRequest().getMethod().name();
        String clientIp = exchange.getAttribute("client.ip");
        String requestId = exchange.getAttribute("request.id");
        
        // 记录结构化日志
        log.error("网关异常 - 错误码: {}, 消息: {}, HTTP状态: {}, 请求: {} {}, 客户端IP: {}, 请求ID: {}",
                exception.getErrorCode(),
                exception.getMessage(),
                exception.getHttpStatus(),
                method,
                path,
                clientIp,
                requestId,
                exception);
    }

    /**
     * 构建错误响应对象
     * 
     * 响应格式：
     * {
     *   "success": false,
     *   "code": 1001,
     *   "message": "认证失败",
     *   "data": null,
     *   "timestamp": 1641234567890,
     *   "path": "/api/user/info"
     * }
     * 
     * 学习要点：
     * - 统一响应格式的设计
     * - Map数据结构的使用
     * - 时间戳的获取和使用
     * 
     * @param exception 异常对象
     * @param exchange WebFlux交换对象
     * @return 错误响应Map
     */
    private Map<String, Object> buildErrorResponse(GatewayException exception, ServerWebExchange exchange) {
        Map<String, Object> response = new HashMap<>();
        
        // 基础响应信息
        response.put("success", false);
        response.put("code", exception.getErrorCode());
        response.put("message", exception.getMessage());
        response.put("data", null);
        response.put("timestamp", System.currentTimeMillis());
        
        // 请求路径信息
        response.put("path", exchange.getRequest().getPath().value());
        
        // 请求ID（便于问题追踪）
        String requestId = exchange.getAttribute("request.id");
        if (requestId != null) {
            response.put("requestId", requestId);
        }
        
        return response;
    }

    /**
     * 写入错误响应
     * 
     * 学习要点：
     * - WebFlux响应写入的标准流程
     * - DataBuffer的使用和内存管理
     * - HTTP状态码和响应头的设置
     * - 响应式编程的异常处理
     * 
     * @param response HTTP响应对象
     * @param jsonContent JSON响应内容
     * @param httpStatus HTTP状态码
     * @return 写入完成的Mono
     */
    private Mono<Void> writeErrorResponse(ServerHttpResponse response, String jsonContent, int httpStatus) {
        // 设置HTTP状态码
        response.setStatusCode(HttpStatus.valueOf(httpStatus));
        
        // 设置响应头
        response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        response.getHeaders().add("Cache-Control", "no-cache, no-store, must-revalidate");
        
        // CORS支持
        response.getHeaders().add("Access-Control-Allow-Origin", "*");
        response.getHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        response.getHeaders().add("Access-Control-Allow-Headers", 
            "Content-Type, Authorization, accessKey, sign, nonce, timestamp");
        
        // 创建响应体并写入
        DataBuffer buffer = response.bufferFactory().wrap(jsonContent.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer))
            .doOnSuccess(aVoid -> log.debug("异常响应发送成功，状态码: {}", httpStatus))
            .doOnError(error -> log.error("异常响应发送失败", error));
    }

    /**
     * 处理异常处理过程中的错误（降级处理）
     * 
     * 当异常处理本身发生异常时的降级策略：
     * 1. 返回最简单的错误响应
     * 2. 避免再次抛出异常
     * 3. 确保客户端能收到响应
     * 
     * 学习要点：
     * - 降级处理的设计思想
     * - 异常安全的重要性
     * - 最小化响应的构建
     * 
     * @param response HTTP响应对象
     * @param originalException 原始异常
     * @return 降级响应的Mono
     */
    private Mono<Void> handleProcessingError(ServerHttpResponse response, GatewayException originalException) {
        try {
            // 构建最简单的错误响应
            String fallbackResponse = String.format(
                "{\"success\":false,\"code\":%d,\"message\":\"%s\",\"timestamp\":%d}",
                originalException.getErrorCode(),
                "系统内部错误",
                System.currentTimeMillis()
            );
            
            // 设置基本响应头
            response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");
            
            // 写入降级响应
            DataBuffer buffer = response.bufferFactory().wrap(fallbackResponse.getBytes(StandardCharsets.UTF_8));
            return response.writeWith(Mono.just(buffer));
            
        } catch (Exception e) {
            // 最终降级：直接结束响应
            log.error("降级处理也失败了", e);
            return response.setComplete();
        }
    }

    /**
     * 创建常用异常的便捷方法
     * 
     * 学习要点：
     * - 静态工厂方法的设计模式
     * - 异常创建的便捷性
     */
    
    public static GatewayException authFailed() {
        return new GatewayException(GatewayErrorCode.AUTH_FAILED);
    }
    
    public static GatewayException interfaceNotFound() {
        return new GatewayException(GatewayErrorCode.INTERFACE_NOT_FOUND);
    }
    
    public static GatewayException rateLimited() {
        return new GatewayException(GatewayErrorCode.RATE_LIMITED);
    }
    
    public static GatewayException systemError(Throwable cause) {
        return new GatewayException(GatewayErrorCode.INTERNAL_ERROR, cause);
    }
}
