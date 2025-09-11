package com.xiaoxin.api.gateway.filter;

import com.xiaoxin.api.gateway.filter.base.BaseGatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 日志过滤器
 * 
 * 业务职责：
 * - 记录请求基本信息(ID、路径、方法、参数)
 * - 解析客户端真实IP地址
 * - 处理代理场景的X-Forwarded-For头
 * - 为后续过滤器提供数据
 * - 记录请求开始时间用于性能统计
 * 
 * 调用链路：
 * 请求 → 提取请求信息 → 解析客户端IP → 记录日志 → 存储到Exchange → 下一个过滤器
 * 
 * 技术实现：
 * - 复用原有日志记录逻辑，保持兼容性
 * - 通过ServerWebExchange.attributes传递数据给后续过滤器
 * - 支持IPv4和IPv6地址解析
 * - 处理反向代理的X-Forwarded-For头
 * - 使用Spring WebFlux响应式编程模型
 * 
 * 数据传递：
 * - request.id → Exchange.attributes["request.id"]
 * - platform.path → Exchange.attributes["platform.path"]
 * - request.method → Exchange.attributes["request.method"]
 * - client.ip → Exchange.attributes["client.ip"]
 * - request.startTime → Exchange.attributes["request.startTime"]
 * 
 * 性能考虑：
 * - 日志级别控制：INFO级别记录关键信息，DEBUG级别记录详细信息
 * - 字符串操作优化：避免不必要的字符串拼接
 * - 异常安全：IP解析异常不影响主流程
 * 
 * @author xiaoxin
 * @since 1.0.0
 */
@Component
public class LoggingFilter extends BaseGatewayFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 检查过滤器开关
        if (!isEnabled()) {
            log.debug("日志过滤器已禁用，跳过执行");
            return chain.filter(exchange);
        }

        long startTime = System.currentTimeMillis();
        ServerHttpRequest request = exchange.getRequest();
        
        // 提取请求基本信息
        String requestId = request.getId();
        String platformPath = request.getPath().value();
        String method = request.getMethod().toString();
        String queryParams = request.getQueryParams().toString();
        
        // 解析客户端真实IP
        String clientIp = parseClientIp(request);
        
        // 记录请求日志（保持与原有CustomGlobalFilter一致）
        log.info(">>>网关日志过滤器被调用");
        log.info("请求唯一标识：{}", requestId);
        log.info("平台路径：{}", platformPath);
        log.info("请求方法：{}", method);
        log.info("请求参数：{}", queryParams);
        log.info("请求来源地址：{}", clientIp);
        
        // 将关键信息存储到Exchange attributes供后续过滤器使用
        exchange.getAttributes().put("request.id", requestId);
        exchange.getAttributes().put("platform.path", platformPath);
        exchange.getAttributes().put("request.method", method);
        exchange.getAttributes().put("client.ip", clientIp);
        exchange.getAttributes().put("request.startTime", startTime);
        
        // 记录过滤器执行指标
        recordFilterMetrics("LoggingFilter", startTime, true, null);
        
        return chain.filter(exchange);
    }

    /**
     * 解析客户端真实IP地址
     * 
     * 业务场景：
     * - 直连场景：客户端直接访问网关，使用RemoteAddress
     * - 代理场景：通过Nginx等反向代理，解析X-Forwarded-For头
     * - 负载均衡：通过云厂商LB，获取第一个真实客户端IP
     * - CDN场景：通过CDN加速，解析多层代理IP
     * 
     * 技术实现：
     * - X-Forwarded-For格式：client, proxy1, proxy2
     * - 取第一个IP作为真实客户端IP
     * - 处理IPv6映射IPv4的特殊情况
     * - 异常安全：解析失败返回"unknown"
     * 
     * 网络协议说明：
     * - X-Forwarded-For：RFC 7239标准，代理服务器添加的客户端IP
     * - RemoteAddress：TCP连接的直接来源地址
     * - 优先级：X-Forwarded-For > RemoteAddress
     * 
     * 安全考虑：
     * - X-Forwarded-For可以被伪造，需要在可信代理环境下使用
     * - 生产环境应配置可信代理列表
     * - 对于安全敏感应用，建议额外的IP验证机制
     * 
     * Java技术特性：
     * - Optional模式：使用null检查而非Optional，性能优化考虑
     * - 字符串处理：indexOf比split性能更好
     * - 异常处理：catch所有异常，确保不影响主流程
     * 
     * @param request HTTP请求对象
     * @return 客户端IP地址，解析失败时返回"unknown"
     */
    private String parseClientIp(ServerHttpRequest request) {
        try {
            // 优先从X-Forwarded-For头获取真实客户端IP
            String xForwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isBlank()) {
                // X-Forwarded-For可能包含多个IP，格式：client, proxy1, proxy2
                // 取第一个IP（真实客户端IP）
                int commaIndex = xForwardedFor.indexOf(',');
                String clientIp = commaIndex > 0 ? 
                    xForwardedFor.substring(0, commaIndex).trim() : 
                    xForwardedFor.trim();
                
                log.debug("从X-Forwarded-For获取客户端IP: {} (原始值: {})", clientIp, xForwardedFor);
                return clientIp;
            }
            
            // 备选：从X-Real-IP头获取（Nginx常用）
            String xRealIp = request.getHeaders().getFirst("X-Real-IP");
            if (xRealIp != null && !xRealIp.isBlank()) {
                log.debug("从X-Real-IP获取客户端IP: {}", xRealIp);
                return xRealIp.trim();
            }
            
            // 最后：从RemoteAddress获取直连IP
            var remoteAddress = request.getRemoteAddress();
            if (remoteAddress != null && remoteAddress.getAddress() != null) {
                String directIp = remoteAddress.getAddress().getHostAddress();
                log.debug("从RemoteAddress获取客户端IP: {}", directIp);
                return directIp;
            }
            
            log.warn("无法解析客户端IP地址，所有来源都为空");
            return "unknown";
            
        } catch (Exception e) {
            // 异常安全：IP解析失败不应影响主流程
            log.warn("解析客户端IP地址异常，使用默认值", e);
            return "unknown";
        }
    }

    /**
     * 获取过滤器启用状态
     * 
     * 从配置文件读取开关状态：
     * xiaoxin.gateway.filters.logging: true/false
     * 
     * @return true-启用，false-禁用
     */
    @Override
    protected boolean isEnabled() {
        return gatewayProperties.getFilters().isLogging();
    }

    /**
     * 过滤器执行顺序
     * 
     * Order值说明：
     * - 值越小优先级越高
     * - -100：最高优先级，第一个执行
     * - 其他过滤器依次递增：-90, -80, -70...
     * 
     * 执行链路：
     * LoggingFilter(-100) → SecurityFilter(-90) → AuthenticationFilter(-80) → ...
     * 
     * @return 执行顺序值
     */
    @Override
    public int getOrder() {
        return -100;
    }
}
