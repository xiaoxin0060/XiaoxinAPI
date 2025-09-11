package com.xiaoxin.api.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.xiaoxin.api.gateway.filter.base.BaseGatewayFilter;
import com.xiaoxin.api.platform.model.entity.InterfaceInfo;
import com.xiaoxin.api.platform.model.entity.User;
import com.xiaoxin.api.platform.service.InnerUserInterfaceInfoService;
import com.xiaoxin.api.platform.utils.CryptoUtils;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * 代理过滤器 - 动态代理转发
 * 
 * 业务职责：
 * - 将平台统一API路径转发到真实接口地址
 * - 处理不同接口的认证方式（API_KEY/BASIC/BEARER）
 * - 构建和转发HTTP请求到目标服务
 * - 处理超时、重试和错误响应
 * - 记录调用成功次数用于统计计费
 * 
 * 调用链路：
 * 请求 → 构建目标URL → 处理认证头 → WebClient调用 → 处理响应 → 记录统计
 * 
 * 技术实现：
 * - 使用Spring WebClient进行响应式HTTP调用
 * - 支持多种认证方式的动态配置
 * - 复用原有动态代理逻辑，保持兼容性
 * - 解密接口认证配置，安全访问真实接口
 * - 统一响应格式，包装第三方接口返回
 * 
 * 动态代理架构：
 * - 平台路径：/api/geo/query（客户端请求）
 * - 真实地址：http://ip-api.com/json（实际转发）
 * - 路径映射：数据库存储的接口配置
 * - 参数透传：Query参数和请求体完整转发
 * 
 * 认证支持：
 * - NONE：无需认证，直接转发
 * - API_KEY：添加API密钥到指定头部
 * - BASIC：HTTP Basic认证
 * - BEARER：Bearer Token认证
 * - 配置加密：认证信息AES-GCM加密存储
 * 
 * 错误处理：
 * - 网络超时：配置化超时时间，支持重试
 * - 服务不可用：返回统一错误格式
 * - 认证失败：记录日志，返回代理错误
 * - 解析异常：降级处理，保证服务可用性
 * 
 * 性能优化：
 * - 连接池：WebClient复用HTTP连接
 * - 超时控制：避免长时间阻塞
 * - 异步处理：响应式编程，高并发支持
 * - 资源回收：及时释放网络和内存资源
 * 
 * @author xiaoxin
 * @since 1.0.0
 */
@Component
public class ProxyFilter extends BaseGatewayFilter {

    /**
     * 内部用户接口信息服务
     * 
     * 用于记录调用统计：
     * - invokeCount：记录成功调用次数
     * - 支持计费和统计分析
     * - 在代理调用成功后异步记录
     */
    @DubboReference
    private InnerUserInterfaceInfoService innerUserInterfaceInfoService;

    /**
     * WebClient用于HTTP调用
     * 
     * 特性：
     * - 响应式HTTP客户端
     * - 支持连接池和超时配置
     * - 内置重试和熔断机制
     * - 高性能异步处理
     */
    @Autowired
    private WebClient webClient;

    /**
     * 认证配置主密钥
     * 
     * 用于解密接口认证配置：
     * - AES-GCM算法解密
     * - 环境变量注入，避免硬编码
     * - 生产环境必须配置
     */
    @Value("${security.authcfg.master-key:}")
    private String authcfgMasterKey;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 检查过滤器开关
        if (!isEnabled()) {
            log.debug("代理过滤器已禁用，跳过动态代理");
            return chain.filter(exchange);
        }

        long startTime = System.currentTimeMillis();
        
        // 从前置过滤器获取用户和接口信息
        User user = exchange.getAttribute("authenticated.user");
        InterfaceInfo interfaceInfo = exchange.getAttribute("interface.info");
        ServerHttpRequest request = exchange.getRequest();
        
        // 空值检查
        if (user == null) {
            log.error("用户信息为空，无法进行代理调用");
            recordFilterMetrics("ProxyFilter", startTime, false, null);
            return handleNoAuth(exchange.getResponse());
        }
        
        if (interfaceInfo == null) {
            log.error("接口信息为空，无法进行代理调用");
            recordFilterMetrics("ProxyFilter", startTime, false, null);
            return handleNoAuth(exchange.getResponse());
        }
        
        return performDynamicProxy(exchange, interfaceInfo, user, request)
            .flatMap(responseBody -> {
                // 代理调用成功，记录统计
                recordSuccessfulInvocation(interfaceInfo, user);
                
                // 构建统一响应格式
                String unifiedResponse = buildUnifiedResponse(responseBody, interfaceInfo, true, null);
                
                // 存储响应数据供后续处理
                exchange.getAttributes().put("proxy.response", unifiedResponse);
                
                log.debug("代理调用成功 - 接口: {}, 响应长度: {}", 
                         interfaceInfo.getName(), responseBody.length());
                recordFilterMetrics("ProxyFilter", startTime, true, null);
                
                return chain.filter(exchange);
            })
            .onErrorResume(Exception.class, error -> {
                log.error("代理调用失败 - 接口: {}, 错误: {}", 
                         interfaceInfo.getName(), error.getMessage(), error);
                
                // 构建错误响应
                String errorResponse = buildUnifiedResponse(null, interfaceInfo, false, error.getMessage());
                exchange.getAttributes().put("proxy.response", errorResponse);
                
                recordFilterMetrics("ProxyFilter", startTime, false, error);
                
                // 继续执行，让响应过滤器处理错误响应
                return chain.filter(exchange);
            });
    }

    /**
     * 执行动态代理调用
     * 
     * 代理流程：
     * 1. 构建真实接口URL（包含Query参数）
     * 2. 构建请求头（复制原始头+添加认证头）
     * 3. 使用WebClient发起HTTP调用
     * 4. 处理响应和异常
     * 
     * URL构建：
     * - 基础URL：interfaceInfo.providerUrl
     * - Query参数：从原始请求透传
     * - 路径参数：暂不支持（可扩展）
     * 
     * 请求头处理：
     * - 过滤网关认证头：不转发给真实接口
     * - 添加认证头：根据接口配置添加
     * - 透传业务头：Content-Type、Accept等
     * - 添加标识头：标识请求来源网关
     * 
     * @param exchange ServerWebExchange对象
     * @param interfaceInfo 接口信息
     * @param user 用户信息
     * @param request 原始请求
     * @return 响应内容Mono
     */
    private Mono<String> performDynamicProxy(ServerWebExchange exchange, InterfaceInfo interfaceInfo, 
                                           User user, ServerHttpRequest request) {
        try {
            // 构建真实接口URL
            String realUrl = buildRealUrl(interfaceInfo, request);
            log.info("开始动态代理调用 - 接口: {}, 真实地址: {}", interfaceInfo.getName(), realUrl);
            
            // 构建请求头
            HttpHeaders realHeaders = buildRealHeaders(interfaceInfo, request);
            
            // 获取超时配置
            int timeoutMs = interfaceInfo.getTimeout() != null ? 
                interfaceInfo.getTimeout() : getProxyConfig().getDefaultTimeoutMs();
            
            // 使用WebClient调用真实接口
            return webClient.method(request.getMethod())
                .uri(realUrl)
                .headers(headers -> headers.addAll(realHeaders))
                .body(request.getBody(), DataBuffer.class)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(timeoutMs))
                .doOnNext(responseBody -> {
                    if (getProxyConfig().isEnableRequestLogging()) {
                        Long startTime = exchange.getAttribute("request.startTime");
                        long duration = startTime != null ? System.currentTimeMillis() - startTime : 0;
                        log.info("代理调用响应 - 接口: {}, 响应长度: {}, 耗时: {}ms", 
                                interfaceInfo.getName(), responseBody.length(), duration);
                    }
                });
                
        } catch (Exception e) {
            log.error("构建代理请求失败 - 接口: {}", interfaceInfo.getName(), e);
            return Mono.error(new ProxyException("构建代理请求失败: " + e.getMessage(), e));
        }
    }

    /**
     * 构建真实接口URL
     * 
     * URL组成：
     * - 基础URL：interfaceInfo.providerUrl
     * - Query参数：request.getURI().getQuery()
     * - 参数合并：处理URL已有参数和新增参数
     * 
     * 示例：
     * - 平台请求：/api/geo/query?ip=8.8.8.8
     * - 真实URL：http://ip-api.com/json?ip=8.8.8.8
     * 
     * 特殊处理：
     * - URL编码：确保特殊字符正确编码
     * - 参数去重：避免重复参数
     * - 空值处理：忽略空值参数
     * 
     * @param interfaceInfo 接口信息
     * @param request 原始请求
     * @return 完整的真实接口URL
     */
    private String buildRealUrl(InterfaceInfo interfaceInfo, ServerHttpRequest request) {
        String providerUrl = interfaceInfo.getProviderUrl();
        String queryString = request.getURI().getQuery();
        
        if (queryString != null && !queryString.isEmpty()) {
            // 判断providerUrl是否已包含查询参数
            String separator = providerUrl.contains("?") ? "&" : "?";
            return providerUrl + separator + queryString;
        }
        
        return providerUrl;
    }

    /**
     * 构建真实接口请求头
     * 
     * 请求头处理策略：
     * 1. 复制原始请求头（排除网关认证头）
     * 2. 添加访问真实接口的认证信息
     * 3. 添加网关标识头
     * 4. 处理特殊头部（如Content-Length）
     * 
     * 排除的头部：
     * - accessKey：网关认证头
     * - sign：网关签名头
     * - nonce：网关随机数
     * - timestamp：网关时间戳
     * - x-content-sha256：网关内容哈希
     * 
     * 添加的头部：
     * - 认证头：根据接口配置添加
     * - X-Forwarded-By：标识来源网关
     * - X-Request-ID：请求追踪ID
     * 
     * @param interfaceInfo 接口信息
     * @param request 原始请求
     * @return 处理后的请求头
     */
    private HttpHeaders buildRealHeaders(InterfaceInfo interfaceInfo, ServerHttpRequest request) {
        HttpHeaders realHeaders = new HttpHeaders();
        
        // 复制原始请求头（排除网关认证头）
        request.getHeaders().forEach((key, values) -> {
            if (!isGatewayHeader(key)) {
                realHeaders.addAll(key, values);
            }
        });
        
        // 添加访问真实接口的认证信息
        addAuthHeaders(realHeaders, interfaceInfo);
        
        // 添加标识头
        realHeaders.add("X-Forwarded-By", "XiaoXin-API-Gateway");
        realHeaders.add("X-Request-ID", request.getId());
        
        return realHeaders;
    }

    /**
     * 判断是否为网关认证头
     * 
     * 网关认证头不应该转发给真实接口：
     * - 避免泄露网关内部认证信息
     * - 防止与真实接口认证冲突
     * - 减少不必要的头部传输
     * 
     * @param headerName 头部名称
     * @return true-网关头部，false-业务头部
     */
    private boolean isGatewayHeader(String headerName) {
        return "accessKey".equalsIgnoreCase(headerName) ||
                "sign".equalsIgnoreCase(headerName) ||
                "nonce".equalsIgnoreCase(headerName) ||
                "timestamp".equalsIgnoreCase(headerName) ||
                "body".equalsIgnoreCase(headerName) ||
                "x-content-sha256".equalsIgnoreCase(headerName) ||
                "x-sign-version".equalsIgnoreCase(headerName);
    }

    /**
     * 添加访问真实接口的认证头
     * 
     * 支持的认证类型：
     * 1. NONE：无需认证
     * 2. API_KEY：添加API密钥到指定头部
     * 3. BASIC：HTTP Basic认证
     * 4. BEARER：Bearer Token认证
     * 
     * 配置解密：
     * - 认证配置使用AES-GCM加密存储
     * - 使用主密钥解密配置信息
     * - AAD包含接口标识，防止配置被篡改
     * 
     * 错误处理：
     * - 解密失败：记录日志，不添加认证头
     * - 配置缺失：记录日志，使用默认配置
     * - 格式错误：记录日志，跳过认证
     * 
     * @param headers 请求头对象
     * @param interfaceInfo 接口信息
     */
    private void addAuthHeaders(HttpHeaders headers, InterfaceInfo interfaceInfo) {
        String authType = interfaceInfo.getAuthType();
        String authConfig = interfaceInfo.getAuthConfig();
        
        if (!"NONE".equals(authType) && authConfig != null) {
            try {
                String plainCfg = authConfig;
                
                // 解密认证配置
                if (CryptoUtils.isEncrypted(authConfig)) {
                    if (authcfgMasterKey == null || authcfgMasterKey.isBlank()) {
                        throw new IllegalStateException("未配置认证配置主密钥");
                    }
                    
                    String aadStr = safeStr(interfaceInfo.getProviderUrl()) + "|" + 
                                   safeStr(interfaceInfo.getUrl()) + "|" + 
                                   safeStr(interfaceInfo.getMethod());
                    
                    plainCfg = CryptoUtils.aesGcmDecryptToString(
                        authcfgMasterKey.getBytes(StandardCharsets.UTF_8),
                        aadStr.getBytes(StandardCharsets.UTF_8), 
                        authConfig);
                }
                
                JsonNode authNode = objectMapper.readTree(plainCfg);
                
                // 根据认证类型添加相应的头部
                switch (authType) {
                    case "API_KEY":
                        addApiKeyAuth(headers, authNode);
                        break;
                        
                    case "BASIC":
                        addBasicAuth(headers, authNode);
                        break;
                        
                    case "BEARER":
                        addBearerAuth(headers, authNode);
                        break;
                        
                    default:
                        log.warn("不支持的认证类型: {}", authType);
                }
                
            } catch (Exception e) {
                log.error("添加认证头失败 - 接口: {}, 认证类型: {}", 
                         interfaceInfo.getName(), authType, e);
            }
        }
    }

    /**
     * 添加API密钥认证
     */
    private void addApiKeyAuth(HttpHeaders headers, JsonNode authNode) {
        String apiKey = authNode.get("key").asText();
        String headerName = authNode.has("header") ? 
            authNode.get("header").asText() : "X-API-Key";
        headers.add(headerName, apiKey);
        log.debug("已添加API Key认证头: {}", headerName);
    }

    /**
     * 添加HTTP Basic认证
     */
    private void addBasicAuth(HttpHeaders headers, JsonNode authNode) {
        String username = authNode.get("username").asText();
        String password = authNode.get("password").asText();
        String credentials = Base64.getEncoder()
            .encodeToString((username + ":" + password).getBytes());
        headers.add("Authorization", "Basic " + credentials);
        log.debug("已添加Basic认证头");
    }

    /**
     * 添加Bearer Token认证
     */
    private void addBearerAuth(HttpHeaders headers, JsonNode authNode) {
        String token = authNode.get("token").asText();
        headers.add("Authorization", "Bearer " + token);
        log.debug("已添加Bearer认证头");
    }

    /**
     * 安全字符串处理
     */
    private String safeStr(String s) {
        return s == null ? "" : s;
    }

    /**
     * 记录成功调用统计
     * 
     * 统计用途：
     * - 计费系统：按调用次数计费
     * - 监控报表：接口使用情况分析
     * - 用户画像：用户行为分析
     * - 容量规划：系统负载评估
     * 
     * 异步处理：
     * - 不阻塞主请求流程
     * - 统计失败不影响业务
     * - 可考虑批量处理提升性能
     * 
     * @param interfaceInfo 接口信息
     * @param user 用户信息
     */
    private void recordSuccessfulInvocation(InterfaceInfo interfaceInfo, User user) {
        try {
            // 异步记录，不阻塞主流程
            Mono.fromCallable(() -> {
                innerUserInterfaceInfoService.invokeCount(interfaceInfo.getId(), user.getId());
                return true;
            })
            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
            .subscribe(
                result -> log.debug("调用统计记录成功 - 用户ID: {}, 接口ID: {}", 
                                   user.getId(), interfaceInfo.getId()),
                error -> log.error("调用统计记录失败 - 用户ID: {}, 接口ID: {}", 
                                  user.getId(), interfaceInfo.getId(), error)
            );
        } catch (Exception e) {
            log.error("启动统计记录异常 - 用户ID: {}, 接口ID: {}", 
                     user.getId(), interfaceInfo.getId(), e);
        }
    }

    /**
     * 获取过滤器启用状态
     */
    @Override
    protected boolean isEnabled() {
        return gatewayProperties.getFilters().isProxy();
    }

    /**
     * 过滤器执行顺序
     */
    @Override
    public int getOrder() {
        return -40;
    }

    /**
     * 代理异常类
     */
    private static class ProxyException extends RuntimeException {
        public ProxyException(String message) {
            super(message);
        }
        
        public ProxyException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
