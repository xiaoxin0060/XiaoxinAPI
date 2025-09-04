package com.xiaoxin.api.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaoxin.sdk.utils.SignUtils;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import model.entity.InterfaceInfo;
import model.entity.User;
import org.apache.dubbo.config.annotation.DubboReference;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.function.client.WebClient;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import service.InnerInterfaceInfoService;
import service.InnerUserInterfaceInfoService;
import service.InnerUserService;

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

    private static final List<String> IP_WHITE_LIST = Arrays.asList("127.0.0.1","0:0:0:0:0:0:0:1");
    
    public CustomGlobalFilter() {
        this.webClient = WebClient.builder().build();
        this.objectMapper = new ObjectMapper();
        // 配置ObjectMapper确保正确处理UTF-8编码
        this.objectMapper.getFactory().configure(
            com.fasterxml.jackson.core.JsonGenerator.Feature.ESCAPE_NON_ASCII, false);
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
        String sourceAddress = Objects.requireNonNull(request.getLocalAddress()).getHostString();
        log.info("请求来源地址：" + sourceAddress);
        log.info("请求来源地址：" + request.getRemoteAddress());
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
        String body = headers.getFirst("body");
        // 去数据库中查是否已分配给用户
        User invokeUser = null;
        try {
            invokeUser = innerUserService.getInvokeUser(accessKey);
        } catch (Exception e) {
            log.error("getInvokeUser error", e);
        }
        if (invokeUser == null) {
            return handleNoAuth(response);
        }
        if(nonce != null && Long.parseLong(nonce) > 10000L){
            return handleNoAuth(response);
        }
        // 时间和当前时间不能超过 5 分钟
        long currentTime = System.currentTimeMillis() / 1000;
        final long FIVE_MINUTES = 60 * 5L;
        if(timestamp != null && (currentTime - Long.parseLong(timestamp)) >= FIVE_MINUTES){
            return handleNoAuth(response);
        }
        // 实际情况中是从数据库中查出 secretKey
        String secretKey = invokeUser.getSecretKey();
        String serverSign = SignUtils.genSign(body, secretKey);
        if (sign == null || !sign.equals(serverSign)) {
            return handleNoAuth(response);
        }
        // 4. 查询用户上传的真实接口信息
        InterfaceInfo interfaceInfo = null;
        try {
            interfaceInfo = innerInterfaceInfoService.getInterfaceInfo(platformPath, method);
        } catch (Exception e) {
            log.error("查询接口信息失败", e);
        }
        if (interfaceInfo == null) {
            log.warn("接口不存在: path={}, method={}", platformPath, method);
            return handleNoAuth(response);
        }
        
        // 检查接口状态
        if (interfaceInfo.getStatus() != 1) {
            log.warn("接口已下线: {}", interfaceInfo.getName());
            return handleNoAuth(response);
        }
        
        //5. 动态代理调用用户的真实接口
        return handleDynamicProxy(exchange, interfaceInfo, invokeUser);
    }

    // 处理响应
    public Mono<Void> handleResponse(ServerWebExchange exchange, GatewayFilterChain chain, long interfaceInfoId, long userId) {
        try {
            ServerHttpResponse originalResponse = exchange.getResponse();
            // 缓存数据的工厂
            DataBufferFactory bufferFactory = originalResponse.bufferFactory();
            // 拿到响应码
            HttpStatusCode statusCode = originalResponse.getStatusCode();
            if (statusCode == HttpStatus.OK) {
                // 装饰，增强能力
                ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {@NonNull
                    // 等调用完转发的接口后才会执行
                    @Override
                    public Mono<Void> writeWith(@NonNull Publisher<? extends DataBuffer> body) {
                        log.info("body instanceof Flux: {}", (body instanceof Flux));
                        if (body instanceof Flux) {
                            Flux<? extends DataBuffer> fluxBody = Flux.from(body);
                            // 往返回值里写数据
                            // 拼接字符串
                            return super.writeWith(
                                    fluxBody.map(dataBuffer -> {
                                        // 7. 调用成功，接口调用次数 + 1 invokeCount
                                        try {
                                            innerUserInterfaceInfoService.invokeCount(interfaceInfoId, userId);
                                        } catch (Exception e) {
                                            log.error("invokeCount error", e);
                                        }
                                        // 读取响应内容
                                        byte[] content = new byte[dataBuffer.readableByteCount()];
                                        dataBuffer.read(content);
                                        DataBufferUtils.release(dataBuffer);//释放掉内存
                                        // 构建日志
                                        String data = new String(content, StandardCharsets.UTF_8); //data
                                        // 打印日志
                                        log.info("响应结果：{}", data);
                                        return bufferFactory.wrap(content);
                                    }));
                        } else {
                            // 8. 调用失败，返回一个规范的错误码
                            log.error("<--- {} 响应code异常", getStatusCode());
                        }
                        return super.writeWith(body);
                    }
                };
                // 设置 response 对象为装饰过的
                return chain.filter(exchange.mutate().response(decoratedResponse).build());
            }
            return chain.filter(exchange); // 降级处理返回数据
        } catch (Exception e) {
            log.error("网关处理响应异常{}", e.getMessage());
            return chain.filter(exchange);
        }
    }

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
                        
                        // 更新调用统计（异步）
                        updateCallStats(interfaceInfo.getId(), invokeUser.getId());
                        
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
               "timestamp".equalsIgnoreCase(headerName);
    }
    
    /**
     * 添加访问真实接口的认证头
     */
    private void addAuthHeaders(HttpHeaders headers, InterfaceInfo interfaceInfo) {
        String authType = interfaceInfo.getAuthType();
        String authConfig = interfaceInfo.getAuthConfig();
        
        if (!"NONE".equals(authType) && authConfig != null) {
            try {
                JsonNode authNode = objectMapper.readTree(authConfig);
                
                switch (authType) {
                    case "API_KEY":
                        String apiKey = authNode.get("key").asText();
                        String headerName = authNode.has("header") ? authNode.get("header").asText() : "X-API-Key";
                        headers.add(headerName, apiKey);
                        break;
                        
                    case "BASIC":
                        String username = authNode.get("username").asText();
                        String password = authNode.get("password").asText();
                        String credentials = java.util.Base64.getEncoder()
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
    private void updateCallStats(Long interfaceId, Long userId) {
        try {
            innerUserInterfaceInfoService.invokeCount(interfaceId, userId);
        } catch (Exception e) {
            log.error("更新调用统计失败", e);
        }
    }
    
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

    @Override
    public int getOrder() {
        return -1;
    }

    public Mono<Void> handleNoAuth(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return response.setComplete();
    }

    public Mono<Void> handleInvokeError(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
        return response.setComplete();
    }

}