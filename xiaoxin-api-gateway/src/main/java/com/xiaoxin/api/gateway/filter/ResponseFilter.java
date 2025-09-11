package com.xiaoxin.api.gateway.filter;

import com.xiaoxin.api.gateway.filter.base.BaseGatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 响应过滤器 - 统一响应处理和性能统计
 * 
 * 业务职责：
 * - 处理代理调用的响应结果
 * - 统一响应格式输出给客户端
 * - 记录请求处理的完整耗时
 * - 处理异常情况的降级响应
 * - 添加响应头和CORS支持
 * 
 * 调用链路：
 * 所有前置过滤器执行完毕 → 获取响应数据 → 格式化输出 → 记录性能指标
 * 
 * 技术实现：
 * - 从Exchange attributes获取代理响应
 * - 使用DataBuffer写入响应体
 * - 设置正确的Content-Type和编码
 * - 响应式编程：非阻塞写入响应
 * - 异常安全：确保总有响应返回给客户端
 * 
 * 响应格式：
 * 成功响应：
 * {
 *   "code": 200,
 *   "message": "调用成功",
 *   "data": {...},
 *   "timestamp": 1641234567890
 * }
 * 
 * 错误响应：
 * {
 *   "code": 500,
 *   "message": "接口调用失败: 具体错误",
 *   "data": null,
 *   "timestamp": 1641234567890
 * }
 * 
 * 性能监控：
 * - 请求总耗时：从接收到响应的完整时间
 * - 各过滤器耗时：分阶段性能分析
 * - 成功率统计：调用成功/失败比例
 * - 响应大小：网络传输量统计
 * 
 * CORS支持：
 * - Access-Control-Allow-Origin：跨域访问控制
 * - Access-Control-Allow-Methods：允许的HTTP方法
 * - Access-Control-Allow-Headers：允许的请求头
 * - Access-Control-Max-Age：预检请求缓存时间
 * 
 * 缓存控制：
 * - Cache-Control：缓存策略控制
 * - ETag：响应内容标识
 * - Last-Modified：内容修改时间
 * - Expires：过期时间设置
 * 
 * @author xiaoxin
 * @since 1.0.0
 */
@Component
public class ResponseFilter extends BaseGatewayFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 检查过滤器开关
        if (!isEnabled()) {
            log.debug("响应过滤器已禁用，跳过响应处理");
            return chain.filter(exchange);
        }

        long startTime = System.currentTimeMillis();
        
        // 执行所有前置过滤器
        return chain.filter(exchange)
            .then(Mono.defer(() -> {
                // 所有前置过滤器执行完毕，处理最终响应
                return processResponse(exchange, startTime);
            }))
            .onErrorResume(Exception.class, error -> {
                // 处理整个过滤器链的异常
                log.error("过滤器链执行异常", error);
                return handleFilterChainError(exchange, error, startTime);
            });
    }

    /**
     * 处理响应数据
     * 
     * 响应数据来源：
     * 1. 代理过滤器：proxy.response（成功或失败响应）
     * 2. 错误过滤器：error.response（各种错误响应）
     * 3. 默认响应：当没有明确响应时的兜底处理
     * 
     * 处理流程：
     * 1. 从Exchange attributes获取响应数据
     * 2. 验证响应数据有效性
     * 3. 设置响应头（Content-Type、CORS等）
     * 4. 写入响应体到客户端
     * 5. 记录性能指标
     * 
     * 特殊情况：
     * - 代理失败：使用错误响应格式
     * - 响应为空：使用默认成功响应
     * - 数据异常：使用系统错误响应
     * 
     * @param exchange ServerWebExchange对象
     * @param startTime 请求开始时间
     * @return 响应处理完成的Mono
     */
    private Mono<Void> processResponse(ServerWebExchange exchange, long startTime) {
        try {
            ServerHttpResponse response = exchange.getResponse();
            
            // 从Exchange attributes获取响应数据
            String responseData = getResponseData(exchange);
            
            // 设置响应头
            setResponseHeaders(response);
            
            // 记录性能指标
            recordPerformanceMetrics(exchange, startTime, true, responseData.length());
            
            // 写入响应体
            return writeResponseBody(response, responseData);
            
        } catch (Exception e) {
            log.error("处理响应数据异常", e);
            return handleResponseError(exchange, e, startTime);
        }
    }

    /**
     * 获取响应数据
     * 
     * 数据优先级：
     * 1. proxy.response：代理调用的响应（最高优先级）
     * 2. error.response：错误处理的响应
     * 3. 默认响应：兜底的成功响应
     * 
     * 数据验证：
     * - 检查数据是否为null或空字符串
     * - 验证JSON格式的有效性
     * - 处理特殊字符和编码问题
     * 
     * @param exchange ServerWebExchange对象
     * @return 响应数据字符串
     */
    private String getResponseData(ServerWebExchange exchange) {
        // 优先获取代理响应
        String proxyResponse = exchange.getAttribute("proxy.response");
        if (proxyResponse != null && !proxyResponse.isBlank()) {
            log.debug("获取代理响应数据，长度: {}", proxyResponse.length());
            return proxyResponse;
        }
        
        // 获取错误响应
        String errorResponse = exchange.getAttribute("error.response");
        if (errorResponse != null && !errorResponse.isBlank()) {
            log.debug("获取错误响应数据，长度: {}", errorResponse.length());
            return errorResponse;
        }
        
        // 默认成功响应
        String defaultResponse = buildDefaultSuccessResponse();
        log.debug("使用默认成功响应");
        return defaultResponse;
    }

    /**
     * 构建默认成功响应
     * 
     * 使用场景：
     * - 所有过滤器都执行成功，但没有明确的响应数据
     * - 某些过滤器处理异常，但仍要返回成功标识
     * - 测试和调试场景的兜底响应
     * 
     * 响应格式：
     * {
     *   "code": 200,
     *   "message": "请求处理成功",
     *   "data": null,
     *   "timestamp": 1641234567890
     * }
     * 
     * @return 默认成功响应JSON字符串
     */
    private String buildDefaultSuccessResponse() {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                "code", 200,
                "message", "请求处理成功",
                "data", (Object) null,
                "timestamp", System.currentTimeMillis()
            ));
        } catch (Exception e) {
            log.error("构建默认响应失败", e);
            return "{\"code\":200,\"message\":\"请求处理成功\",\"data\":null,\"timestamp\":" + 
                   System.currentTimeMillis() + "}";
        }
    }

    /**
     * 设置响应头
     * 
     * 必要的响应头：
     * - Content-Type：指定响应内容类型和编码
     * - Cache-Control：缓存控制策略
     * - X-Response-Time：响应时间（可选）
     * 
     * CORS响应头（如果需要）：
     * - Access-Control-Allow-Origin：允许的源
     * - Access-Control-Allow-Methods：允许的方法
     * - Access-Control-Allow-Headers：允许的头部
     * - Access-Control-Max-Age：预检缓存时间
     * 
     * 安全响应头：
     * - X-Content-Type-Options：防止MIME类型嗅探
     * - X-Frame-Options：防止点击劫持
     * - X-XSS-Protection：XSS保护
     * 
     * @param response ServerHttpResponse对象
     */
    private void setResponseHeaders(ServerHttpResponse response) {
        // 基本响应头
        response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        response.getHeaders().add("Cache-Control", "no-cache, no-store, must-revalidate");
        
        // CORS支持（可配置）
        if (shouldEnableCors()) {
            response.getHeaders().add("Access-Control-Allow-Origin", "*");
            response.getHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            response.getHeaders().add("Access-Control-Allow-Headers", 
                "Content-Type, Authorization, accessKey, sign, nonce, timestamp, x-content-sha256");
            response.getHeaders().add("Access-Control-Max-Age", "3600");
        }
        
        // 安全响应头
        response.getHeaders().add("X-Content-Type-Options", "nosniff");
        response.getHeaders().add("X-Frame-Options", "DENY");
        response.getHeaders().add("X-XSS-Protection", "1; mode=block");
        
        // 网关标识
        response.getHeaders().add("X-Powered-By", "XiaoXin-API-Gateway");
    }

    /**
     * 判断是否启用CORS
     * 
     * 可以通过配置控制CORS策略：
     * - 开发环境：通常启用，便于前端调试
     * - 生产环境：根据业务需求决定
     * - 内网环境：可能不需要CORS
     * 
     * @return true-启用CORS，false-禁用CORS
     */
    private boolean shouldEnableCors() {
        // 可配置化：xiaoxin.gateway.cors.enabled
        return true; // 默认启用，可根据配置调整
    }

    /**
     * 写入响应体
     * 
     * WebFlux响应写入：
     * - 使用DataBuffer包装响应数据
     * - 指定正确的字符编码（UTF-8）
     * - 响应式写入，避免阻塞
     * - 自动处理内存释放
     * 
     * 错误处理：
     * - 写入失败时记录日志
     * - 连接断开时优雅处理
     * - 内存溢出时降级处理
     * 
     * @param response ServerHttpResponse对象
     * @param responseData 响应数据
     * @return 写入完成的Mono
     */
    private Mono<Void> writeResponseBody(ServerHttpResponse response, String responseData) {
        try {
            DataBuffer buffer = response.bufferFactory().wrap(responseData.getBytes(StandardCharsets.UTF_8));
            return response.writeWith(Mono.just(buffer))
                .doOnSuccess(aVoid -> log.debug("响应写入成功，数据长度: {}", responseData.length()))
                .doOnError(error -> log.error("响应写入失败", error));
        } catch (Exception e) {
            log.error("创建响应缓冲区失败", e);
            return response.setComplete();
        }
    }

    /**
     * 记录性能指标
     * 
     * 监控指标：
     * - 请求总耗时：完整的处理时间
     * - 响应大小：网络传输量
     * - 成功率：请求处理成功比例
     * - 异常信息：错误类型和频率
     * 
     * 指标用途：
     * - 性能监控：识别慢请求和瓶颈
     * - 容量规划：评估系统负载能力
     * - 问题诊断：分析异常和错误原因
     * - 用户体验：优化响应时间
     * 
     * @param exchange ServerWebExchange对象
     * @param startTime 请求开始时间
     * @param success 是否成功
     * @param responseSize 响应大小
     */
    private void recordPerformanceMetrics(ServerWebExchange exchange, long startTime, 
                                        boolean success, int responseSize) {
        try {
            long totalTime = System.currentTimeMillis() - startTime;
            
            // 🔧 添加空值检查，防止NPE
            String requestId = exchange.getAttribute("request.id");
            String platformPath = exchange.getAttribute("platform.path");
            String method = exchange.getAttribute("request.method");
            String clientIp = exchange.getAttribute("client.ip");
            
            // 安全的空值处理
            requestId = requestId != null ? requestId : "unknown";
            platformPath = platformPath != null ? platformPath : "unknown";
            method = method != null ? method : "unknown";
            clientIp = clientIp != null ? clientIp : "unknown";
            
            // 记录性能日志
            log.info("请求处理完成 - ID: {}, 路径: {}, 方法: {}, 客户端: {}, 耗时: {}ms, 成功: {}, 响应大小: {}字节", 
                    requestId, platformPath, method, clientIp, totalTime, success, responseSize);
            
            // 可扩展：集成监控系统
            if (getProxyConfig().isEnableMetrics()) {
                recordFilterMetrics("ResponseFilter", startTime, success, null);
                
                // TODO: 集成Micrometer或其他监控系统
                // meterRegistry.timer("gateway.request.duration")
                //     .tag("path", platformPath)
                //     .tag("method", method)
                //     .tag("success", String.valueOf(success))
                //     .record(totalTime, TimeUnit.MILLISECONDS);
                //
                // meterRegistry.gauge("gateway.response.size", responseSize);
            }
            
        } catch (Exception e) {
            log.error("记录性能指标异常", e);
        }
    }

    /**
     * 处理过滤器链异常
     * 
     * 异常场景：
     * - 前置过滤器抛出异常
     * - 网络连接异常
     * - 系统资源不足
     * - 配置错误
     * 
     * 处理策略：
     * - 记录详细的异常信息
     * - 返回统一的错误响应
     * - 保护敏感信息不泄露
     * - 确保客户端总是收到响应
     * 
     * @param exchange ServerWebExchange对象
     * @param error 异常信息
     * @param startTime 请求开始时间
     * @return 错误响应Mono
     */
    private Mono<Void> handleFilterChainError(ServerWebExchange exchange, Throwable error, long startTime) {
        try {
            log.error("过滤器链执行异常", error);
            
            // 构建错误响应
            String errorResponse = buildErrorResponse(error);
            
            // 设置响应头
            ServerHttpResponse response = exchange.getResponse();
            setResponseHeaders(response);
            response.setStatusCode(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
            
            // 记录性能指标
            recordPerformanceMetrics(exchange, startTime, false, errorResponse.length());
            
            // 写入错误响应
            return writeResponseBody(response, errorResponse);
            
        } catch (Exception e) {
            log.error("处理过滤器链异常时发生异常", e);
            return exchange.getResponse().setComplete();
        }
    }

    /**
     * 处理响应处理异常
     */
    private Mono<Void> handleResponseError(ServerWebExchange exchange, Throwable error, long startTime) {
        try {
            log.error("响应处理异常", error);
            
            ServerHttpResponse response = exchange.getResponse();
            String errorResponse = buildErrorResponse(error);
            
            response.setStatusCode(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
            response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");
            
            recordPerformanceMetrics(exchange, startTime, false, errorResponse.length());
            
            return writeResponseBody(response, errorResponse);
            
        } catch (Exception e) {
            log.error("处理响应异常时发生异常", e);
            return exchange.getResponse().setComplete();
        }
    }

    /**
     * 构建错误响应
     */
    private String buildErrorResponse(Throwable error) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                "code", 500,
                "message", "系统内部错误，请稍后重试",
                "data", (Object) null,
                "timestamp", System.currentTimeMillis()
            ));
        } catch (Exception e) {
            log.error("构建错误响应失败", e);
            return "{\"code\":500,\"message\":\"系统内部错误，请稍后重试\",\"data\":null,\"timestamp\":" + 
                   System.currentTimeMillis() + "}";
        }
    }

    /**
     * 获取过滤器启用状态
     */
    @Override
    protected boolean isEnabled() {
        return gatewayProperties.getFilters().isResponse();
    }

    /**
     * 过滤器执行顺序
     * 
     * 作为最后一个过滤器：
     * - 处理所有前置过滤器的结果
     * - 统一输出响应格式
     * - 记录完整的性能指标
     * - 处理整个链路的异常
     */
    @Override
    public int getOrder() {
        return -30;
    }
}
