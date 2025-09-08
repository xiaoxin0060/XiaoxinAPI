package com.xiaoxin.api.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaoxin.api.common.utils.ApiSignUtils;
import com.xiaoxin.api.platform.model.entity.InterfaceInfo;
import com.xiaoxin.api.platform.model.entity.User;
import com.xiaoxin.api.platform.service.InnerInterfaceInfoService;
import com.xiaoxin.api.platform.service.InnerUserInterfaceInfoService;
import com.xiaoxin.api.platform.service.InnerUserService;
import com.xiaoxin.api.platform.utils.CryptoUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Component
public class CustomGlobalFilter implements GlobalFilter, Ordered{

    @DubboReference
    private InnerUserService innerUserService;

    @DubboReference
    private InnerInterfaceInfoService innerInterfaceInfoService;

    @DubboReference
    private InnerUserInterfaceInfoService innerUserInterfaceInfoService;
    
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final ReactiveStringRedisTemplate redisTemplate;
    @Value("${security.authcfg.master-key:}")
    private String authcfgMasterKey;

    private static final List<String> IP_WHITE_LIST = Arrays.asList("127.0.0.1","0:0:0:0:0:0:0:1");
    
    public CustomGlobalFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.webClient = WebClient.builder().build();
        this.objectMapper = new ObjectMapper();
        this.redisTemplate = redisTemplate;
    }


    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        log.info(">>>网关全局过滤器被调用");
        // 1. 请求日志
        ServerHttpRequest request = exchange.getRequest();
        String platformPath = request.getPath().value();  // 平台路径，用于查询数据库
        String method = request.getMethod().toString();
        log.info("请求唯一标识：" + request.getId());
        log.info("平台路径：" + platformPath);
        log.info("请求方法：" + method);
        log.info("请求参数：" + request.getQueryParams());
        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        String sourceAddress;
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            sourceAddress = comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        } else {
            var remoteAddr = request.getRemoteAddress();
            if (remoteAddr != null && remoteAddr.getAddress() != null) {
                sourceAddress = remoteAddr.getAddress().getHostAddress();
            } else {
                sourceAddress = "unknown";
            }
        }
        log.info("请求来源地址：" + sourceAddress);
        ServerHttpResponse response = exchange.getResponse();
        // 2. 访问控制 - 黑白名单
        if (!IP_WHITE_LIST.contains(sourceAddress)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return response.setComplete();
        }
        // 3. 用户鉴权（判断 ak、sk 是否合法）
        HttpHeaders headers = request.getHeaders();
        String accessKey = headers.getFirst("accessKey");
        String nonce = headers.getFirst("nonce");
        String timestamp = headers.getFirst("timestamp");
        String sign = headers.getFirst("sign");
        String contentSha256 = headers.getFirst("x-content-sha256");
        // 去数据库中查是否已分配给用户
        User invokeUserTmp = null;
        try {
            invokeUserTmp = innerUserService.getInvokeUser(accessKey);
        } catch (Exception e) {
            log.error("getInvokeUser error", e);
        }
        final User invokeUser = invokeUserTmp;
        if (invokeUser == null) {
            return handleNoAuth(response);
        }
        // v2 随机字符串 nonce：SDK 固定 16 位，这里做长度=16 与字符集校验
        if (nonce == null || nonce.length() != 16) {
            return handleNoAuth(response);
        }
        // 可选：限制字符集为字母数字
        for (int i = 0; i < nonce.length(); i++) {
            char c = nonce.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')) {
                return handleNoAuth(response);
            }
        }
        // 时间和当前时间不能超过 5 分钟
        long currentTime = System.currentTimeMillis() / 1000;
        final long FIVE_MINUTES = 60 * 5L;
        if(timestamp != null && (currentTime - Long.parseLong(timestamp)) >= FIVE_MINUTES){
            return handleNoAuth(response);
        }
        // 实际情况中是从数据库中查出 secretKey
        String secretKey = invokeUser.getSecretKey();
        // v2 验签：使用统一的签名算法，确保与客户端完全一致
        String canonical = ApiSignUtils.buildCanonicalString(method, platformPath, contentSha256, timestamp, nonce);
        String serverSign = ApiSignUtils.hmacSha256Hex(canonical, secretKey);
        if (sign == null || !sign.equals(serverSign)) {
            return handleNoAuth(response);
        }
        // 重放防护：nonce 5分钟去重
        String replayKey = "replay:" + accessKey + ":" + nonce;
        return redisTemplate.opsForValue().setIfAbsent(replayKey, "1", java.time.Duration.ofMinutes(5))
                .flatMap(ok -> {
                    if (!Boolean.TRUE.equals(ok)) {
                        return handleNoAuth(response);
                    }
                    // 继续查询接口并代理
                    return proceedAfterAuth(exchange, platformPath, method, response, invokeUser);
                });
    }

    private Mono<Void> proceedAfterAuth(ServerWebExchange exchange, String platformPath, String method, ServerHttpResponse response, User invokeUser) {
        InterfaceInfo interfaceInfo = null;
        try {
            interfaceInfo = innerInterfaceInfoService.getInterfaceInfo(platformPath, method);
        } catch (Exception e) {
            log.error("查询接口信息失败", e);
        }
        final InterfaceInfo finInterfaceInfo = interfaceInfo;
        if (finInterfaceInfo == null) {
            log.warn("接口不存在: path={}, method={}", platformPath, method);
            return handleNoAuth(response);
        }
        if (finInterfaceInfo.getStatus() != 1) {
            log.warn("接口已下线: {}", finInterfaceInfo.getName());
            return handleNoAuth(response);
        }
        // 限流（滑动窗口限流60s）：按 userId:interfaceId 维度
        Integer rateLimit = finInterfaceInfo.getRateLimit();
        if (rateLimit != null && rateLimit > 0) {
            long now = System.currentTimeMillis();
            long windowMs = 60_000L;
            String key = "sw:" + invokeUser.getId() + ":" + finInterfaceInfo.getId();
            String member = now + ":" + java.util.UUID.randomUUID();
            double maxExpired = (double) (now - windowMs);
            return redisTemplate.opsForZSet().removeRangeByScore(key, Range.closed(Double.NEGATIVE_INFINITY, maxExpired))
                    .then(redisTemplate.opsForZSet().add(key, member, (double) now))
                    .then(redisTemplate.expire(key, java.time.Duration.ofSeconds(75)))
                    .then(redisTemplate.opsForZSet().count(key, Range.closed((double) (now - windowMs), (double) now)))
                    .flatMap(cnt -> {
                        if (cnt != null && cnt > rateLimit) {
                            String errorJson = buildUnifiedResponse(null, finInterfaceInfo, false, "触发限流，请稍后再试");
                            return writeErrorResponse(response, errorJson, HttpStatus.TOO_MANY_REQUESTS);
                        }
                        // 调用前配额预校验（预扣减一次，失败则直接阻断）
                        boolean preOk;
                        try {
                            preOk = innerUserInterfaceInfoService.preConsume(finInterfaceInfo.getId(), invokeUser.getId());
                        } catch (Exception e) {
                            log.error("配额预校验失败", e);
                            preOk = false;
                        }
                        if (!preOk) {
                            String errorJson = buildUnifiedResponse(null, finInterfaceInfo, false, "调用额度不足或未开通");
                            return writeErrorResponse(response, errorJson, HttpStatus.TOO_MANY_REQUESTS);
                        }
                        return handleDynamicProxy(exchange, finInterfaceInfo, invokeUser);
                    });
        }
        // 无限流配置：仅做配额预扣
        boolean preOk;
        try {
            preOk = innerUserInterfaceInfoService.preConsume(finInterfaceInfo.getId(), invokeUser.getId());
        } catch (Exception e) {
            log.error("配额预校验失败", e);
            preOk = false;
        }
        if (!preOk) {
            String errorJson = buildUnifiedResponse(null, finInterfaceInfo, false, "调用额度不足或未开通");
            return writeErrorResponse(response, errorJson, HttpStatus.TOO_MANY_REQUESTS);
        }
        return handleDynamicProxy(exchange, finInterfaceInfo, invokeUser);
    }

    // 移除未使用的响应装饰逻辑，避免不必要的内存拷贝与复杂度

    /**
     * 动态代理调用用户的真实接口
     */
    private Mono<Void> handleDynamicProxy(ServerWebExchange exchange, InterfaceInfo interfaceInfo, User invokeUser) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();
        
        String realUrl = buildRealUrl(interfaceInfo, request);
        log.info("开始动态代理调用 - 接口: {}, 真实地址: {}", interfaceInfo.getName(), realUrl);
        
        // 构建请求头
        HttpHeaders realHeaders = buildRealHeaders(interfaceInfo, request);
        
        // 使用WebClient调用真实接口
        return webClient.method(request.getMethod())
                .uri(realUrl)
                .headers(headers -> headers.addAll(realHeaders))
                .body(request.getBody(), DataBuffer.class)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(java.time.Duration.ofMillis(interfaceInfo.getTimeout() != null ? interfaceInfo.getTimeout() : 30000))
                .flatMap(responseBody -> {
                    try {
                        // 记录调用成功
                        log.info("接口调用成功 - 接口: {}, 响应长度: {}", interfaceInfo.getName(), responseBody.length());
                        
                        // 记录成功（仅 totalNum +1，不再扣减）
                        try {
                            innerUserInterfaceInfoService.invokeCount(interfaceInfo.getId(), invokeUser.getId());
                        } catch (Exception e) {
                            log.error("记录成功调用失败", e);
                        }
                        
                        // 构建统一响应格式
                        String unifiedResponse = buildUnifiedResponse(responseBody, interfaceInfo, true, null);
                        
                        // 返回响应
                        response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");
                        DataBuffer buffer = response.bufferFactory().wrap(unifiedResponse.getBytes(StandardCharsets.UTF_8));
                        return response.writeWith(Mono.just(buffer));
                        
                    } catch (Exception e) {
                        log.error("处理响应异常", e);
                        return handleProxyError(response, "处理响应失败: " + e.getMessage());
                    }
                })
                .onErrorResume(error -> {
                    log.error("接口调用失败 - 接口: {}, 错误: {}", interfaceInfo.getName(), error.getMessage());
                    String errorResponse = buildUnifiedResponse(null, interfaceInfo, false, error.getMessage());
                    return writeErrorResponse(response, errorResponse);
                });
    }
    
    /**
     * 构建真实接口URL
     */
    private String buildRealUrl(InterfaceInfo interfaceInfo, ServerHttpRequest request) {
        String providerUrl = interfaceInfo.getProviderUrl();
        String queryString = request.getURI().getQuery();
        
        if (queryString != null && !queryString.isEmpty()) {
            String separator = providerUrl.contains("?") ? "&" : "?";
            return providerUrl + separator + queryString;
        }
        
        return providerUrl;
    }
    
    /**
     * 构建真实接口请求头
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
     */
    private void addAuthHeaders(HttpHeaders headers, InterfaceInfo interfaceInfo) {
        String authType = interfaceInfo.getAuthType();
        String authConfig = interfaceInfo.getAuthConfig();
        
        if (!"NONE".equals(authType) && authConfig != null) {
            try {
                String plainCfg = authConfig;
                if (CryptoUtils.isEncrypted(authConfig)) {
                    if (authcfgMasterKey == null || authcfgMasterKey.isBlank()) {
                        throw new IllegalStateException("未配置认证配置主密钥");
                    }
                    String aadStr = safeStr(interfaceInfo.getProviderUrl()) + "|" + safeStr(interfaceInfo.getUrl()) + "|" + safeStr(interfaceInfo.getMethod());
                    plainCfg = CryptoUtils.aesGcmDecryptToString(authcfgMasterKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            aadStr.getBytes(java.nio.charset.StandardCharsets.UTF_8), authConfig);
                }
                JsonNode authNode = objectMapper.readTree(plainCfg);
                
                switch (authType) {
                    case "API_KEY":
                        String apiKey = authNode.get("key").asText();
                        String headerName = authNode.has("header") ? authNode.get("header").asText() : "X-API-Key";
                        headers.add(headerName, apiKey);
                        break;
                        
                    case "BASIC":
                        String username = authNode.get("username").asText();
                        String password = authNode.get("password").asText();
                        String credentials = Base64.getEncoder()
                                .encodeToString((username + ":" + password).getBytes());
                        headers.add("Authorization", "Basic " + credentials);
                        break;
                        
                    case "BEARER":
                        String token = authNode.get("token").asText();
                        headers.add("Authorization", "Bearer " + token);
                        break;
                }
            } catch (Exception e) {
                log.error("添加认证头失败: {}", e.getMessage());
            }
        }
    }

    private String safeStr(String s){
        return s == null ? "" : s;
    }
    
    /**
     * 构建统一响应格式
     */
    private String buildUnifiedResponse(String responseBody, InterfaceInfo interfaceInfo, boolean success, String errorMessage) {
        try {
            Map<String, Object> response = new HashMap<>();
            
            if (success) {
                response.put("code", 200);
                response.put("message", "调用成功");
                
                // 尝试解析JSON响应
                try {
                    Object data = objectMapper.readValue(responseBody, Object.class);
                    response.put("data", data);
                } catch (Exception e) {
                    // 如果不是JSON，直接返回字符串
                    response.put("data", responseBody);
                }
            } else {
                response.put("code", 500);
                response.put("message", "接口调用失败: " + errorMessage);
                response.put("data", null);
            }
            
            // 添加元信息
            response.put("interfaceId", interfaceInfo.getId());
            response.put("interfaceName", interfaceInfo.getName());
            response.put("timestamp", System.currentTimeMillis());
            
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("构建响应格式失败", e);
            return "{\"code\":500,\"message\":\"系统错误\",\"data\":null}";
        }
    }
    
    /**
     * 异步更新调用统计
     */
    // 删除未使用的统计方法，改由成功回调直接调用 invokeCount
    
    /**
     * 处理代理错误
     */
    private Mono<Void> handleProxyError(ServerHttpResponse response, String message) {
        try {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("code", 500);
            errorResponse.put("message", message);
            errorResponse.put("data", null);
            errorResponse.put("timestamp", System.currentTimeMillis());
            
            String errorJson = objectMapper.writeValueAsString(errorResponse);
            return writeErrorResponse(response, errorJson);
        } catch (Exception e) {
            log.error("构建错误响应失败", e);
            return response.setComplete();
        }
    }
    
    /**
     * 写入错误响应
     */
    private Mono<Void> writeErrorResponse(ServerHttpResponse response, String errorJson) {
        response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
        response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        DataBuffer buffer = response.bufferFactory().wrap(errorJson.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private Mono<Void> writeErrorResponse(ServerHttpResponse response, String errorJson, HttpStatus status) {
        response.setStatusCode(status);
        response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        DataBuffer buffer = response.bufferFactory().wrap(errorJson.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -1;
    }

    public Mono<Void> handleNoAuth(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return response.setComplete();
    }

}