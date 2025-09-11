package com.xiaoxin.api.gateway.filter;

import com.xiaoxin.api.common.utils.ApiSignUtils;
import com.xiaoxin.api.gateway.filter.base.BaseGatewayFilter;
import com.xiaoxin.api.platform.model.entity.User;
import com.xiaoxin.api.platform.service.InnerUserService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.time.Duration;

/**
 * 认证过滤器 - 用户鉴权和签名验证
 * 
 * 业务职责：
 * - 验证API签名的有效性（HMAC-SHA256）
 * - 检查时间戳防重放攻击
 * - 验证nonce防重复提交
 * - 查询用户信息并验证AccessKey
 * - 实现防重放攻击机制
 * 
 * 调用链路：
 * 请求 → 提取签名参数 → 验证参数格式 → Dubbo查询用户 → 验证签名 → Redis防重放 → 放行
 * 
 * 技术实现：
 * - 使用@DubboReference进行RPC调用，查询用户信息
 * - ReactiveStringRedisTemplate实现防重放攻击
 * - 复用ApiSignUtils统一签名算法，确保客户端服务端一致性
 * - 使用Spring WebFlux响应式编程模型
 * - 常量时间比较防止时序攻击
 * 
 * 安全机制：
 * - HMAC-SHA256签名：防止请求篡改和伪造
 * - 时间戳验证：防止重放攻击（默认5分钟有效期）
 * - nonce验证：防止重复提交（16位随机字符串）
 * - 字符集限制：nonce只允许字母数字字符
 * - Redis去重：相同nonce在有效期内只能使用一次
 * 
 * 性能优化：
 * - 早期参数验证：无效请求快速拒绝，减少后续计算
 * - 异步用户查询：使用Dubbo异步调用（如果支持）
 * - Redis批量操作：原子性检查和设置nonce
 * - 常量时间比较：防止签名时序攻击
 * 
 * 错误处理：
 * - 参数缺失：返回403 Forbidden
 * - 用户不存在：返回403 Forbidden
 * - 签名无效：返回403 Forbidden
 * - 时间戳过期：返回403 Forbidden
 * - nonce重复：返回403 Forbidden
 * - 系统异常：记录日志并返回403
 * 
 * 配置支持：
 * - 签名有效期：xiaoxin.gateway.security.signature-timeout-seconds
 * - nonce长度：xiaoxin.gateway.security.nonce-length
 * - 时间戳验证开关：xiaoxin.gateway.security.enable-timestamp-validation
 * - 防重放开关：xiaoxin.gateway.security.enable-replay-protection
 * 
 * @author xiaoxin
 * @since 1.0.0
 */
@Component
public class AuthenticationFilter extends BaseGatewayFilter {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthenticationFilter.class);

    /**
     * 内部用户服务
     * 
     * 使用Dubbo RPC调用平台服务：
     * - 根据AccessKey查询用户信息
     * - 获取用户的SecretKey用于签名验证
     * - 验证用户状态和权限
     * 
     * 注意事项：
     * - Dubbo调用是同步阻塞的，在WebFlux中需要特殊处理
     * - 需要配置合适的超时时间，避免阻塞过久
     * - 考虑缓存用户信息，减少RPC调用频率
     */
    @DubboReference
    private InnerUserService innerUserService;

    /**
     * Redis响应式模板
     * 
     * 用于防重放攻击：
     * - 存储已使用的nonce，过期时间等于签名有效期
     * - 使用setIfAbsent原子操作检查和设置nonce
     * - 支持响应式编程，避免阻塞
     */
    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 检查过滤器开关
        if (!isEnabled()) {
            log.debug("认证过滤器已禁用，跳过签名验证");
            return chain.filter(exchange);
        }

        long startTime = System.currentTimeMillis();
        
        try {
            // 1. 提取和验证认证参数
            return extractAuthenticationParams(exchange)
                .flatMap(params -> {
                    // 2. 查询用户信息
                    return queryUserInfo(params.accessKey)
                        .flatMap(user -> {
                            // 3. 验证签名
                            return validateSignature(exchange, user, params)
                                .flatMap(validUser -> {
                                    // 4. 防重放攻击验证
                                    if (getSecurityConfig().isEnableReplayProtection()) {
                                        return preventReplayAttack(params.accessKey, params.nonce)
                                            .flatMap(allowed -> {
                                                if (!allowed) {
                                                    log.warn("检测到重放攻击 - AccessKey: {}, Nonce: {}", 
                                                            params.accessKey, params.nonce);
                                                    return handleNoAuth(exchange.getResponse());
                                                }
                                                return proceedWithAuthentication(exchange, chain, validUser, startTime);
                                            });
                                    } else {
                                        return proceedWithAuthentication(exchange, chain, validUser, startTime);
                                    }
                                });
                        });
                })
                .onErrorResume(AuthenticationException.class, error -> {
                    log.warn("认证失败: {}", error.getMessage());
                    recordFilterMetrics("AuthenticationFilter", startTime, false, error);
                    // 使用统一异常处理器处理认证异常
                    return handleAuthFailed(exchange);
                })
                .onErrorResume(Exception.class, error -> {
                    log.error("认证过程发生异常", error);
                    recordFilterMetrics("AuthenticationFilter", startTime, false, error);
                    // 使用统一异常处理器处理通用异常
                    return handleSystemError(error, exchange);
                });
                
        } catch (Exception e) {
            log.error("认证过滤器执行异常", e);
            recordFilterMetrics("AuthenticationFilter", startTime, false, e);
            // 使用统一异常处理器处理系统异常
            return handleSystemError(e, exchange);
        }
    }

    /**
     * 提取和验证认证参数
     * 
     * 认证参数：
     * - accessKey: API访问密钥，用于标识用户身份
     * - nonce: 16位随机字符串，防止重放攻击
     * - timestamp: 请求时间戳（秒级），防止重放攻击
     * - sign: HMAC-SHA256签名，防止请求篡改
     * - x-content-sha256: 请求体SHA256哈希（可选）
     * 
     * 验证规则：
     * - 所有必需参数不能为空
     * - nonce必须为16位字母数字字符
     * - timestamp必须在有效期内（默认5分钟）
     * - 参数格式必须符合SDK规范
     * 
     * @param exchange ServerWebExchange对象
     * @return 包含认证参数的Mono
     */
    private Mono<AuthParams> extractAuthenticationParams(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        HttpHeaders headers = request.getHeaders();

        // 提取认证参数
        String accessKey = headers.getFirst("accessKey");
        String nonce = headers.getFirst("nonce");
        String timestamp = headers.getFirst("timestamp");
        String sign = headers.getFirst("sign");
        String contentSha256 = headers.getFirst("x-content-sha256");

        // 验证必需参数
        if (accessKey == null || accessKey.isBlank()) {
            return Mono.error(new AuthenticationException("AccessKey不能为空"));
        }
        if (nonce == null || nonce.isBlank()) {
            return Mono.error(new AuthenticationException("Nonce不能为空"));
        }
        if (timestamp == null || timestamp.isBlank()) {
            return Mono.error(new AuthenticationException("Timestamp不能为空"));
        }
        if (sign == null || sign.isBlank()) {
            return Mono.error(new AuthenticationException("Sign不能为空"));
        }

        // 验证nonce格式（复用原有逻辑）
        int expectedNonceLength = getSecurityConfig().getNonceLength();
        if (nonce.length() != expectedNonceLength) {
            return Mono.error(new AuthenticationException(
                String.format("Nonce长度错误，期望%d位，实际%d位", expectedNonceLength, nonce.length())));
        }
        
        // 验证nonce字符集（只允许字母数字）
        for (int i = 0; i < nonce.length(); i++) {
            char c = nonce.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')) {
                return Mono.error(new AuthenticationException("Nonce包含非法字符，只允许字母数字"));
            }
        }

        // 验证时间戳（如果启用）
        if (getSecurityConfig().isEnableTimestampValidation()) {
            try {
                long requestTime = Long.parseLong(timestamp);
                long currentTime = System.currentTimeMillis() / 1000;
                long maxAge = getSecurityConfig().getSignatureTimeoutSeconds();
                
                if (Math.abs(currentTime - requestTime) > maxAge) {
                    return Mono.error(new AuthenticationException(
                        String.format("请求时间戳过期，当前时间：%d，请求时间：%d，最大允许差值：%d秒", 
                                currentTime, requestTime, maxAge)));
                }
            } catch (NumberFormatException e) {
                return Mono.error(new AuthenticationException("时间戳格式无效"));
            }
        }

        // 构建认证参数对象
        AuthParams params = new AuthParams();
        params.accessKey = accessKey;
        params.nonce = nonce;
        params.timestamp = timestamp;
        params.sign = sign;
        params.contentSha256 = contentSha256;

        log.debug("认证参数提取成功 - AccessKey: {}, Nonce: {}, Timestamp: {}", 
                 accessKey, nonce, timestamp);
        
        return Mono.just(params);
    }

    /**
     * 查询用户信息
     * 
     * 通过Dubbo RPC调用平台服务查询用户：
     * - 根据AccessKey查询用户信息
     * - 验证用户是否存在和状态是否正常
     * - 获取SecretKey用于后续签名验证
     * 
     * 注意事项：
     * - Dubbo调用是同步阻塞的，使用Mono.fromCallable包装
     * - 在专用线程池执行，避免阻塞WebFlux事件循环
     * - 考虑添加超时控制和重试机制
     * - 可以考虑添加本地缓存减少RPC调用
     * 
     * @param accessKey API访问密钥
     * @return 包含用户信息的Mono
     */
    private Mono<User> queryUserInfo(String accessKey) {
        return Mono.fromCallable(() -> {
            log.debug("查询用户信息 - AccessKey: {}", accessKey);
            User user = innerUserService.getInvokeUser(accessKey);
            if (user == null) {
                throw new AuthenticationException("AccessKey无效，用户不存在");
            }
            log.debug("用户信息查询成功 - UserId: {}, UserRole: {}", user.getId(), user.getUserRole());
            return user;
        })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()) // 在专用线程池执行阻塞操作
        .onErrorMap(Exception.class, error -> {
            if (error instanceof AuthenticationException) {
                return error;
            }
            log.error("查询用户信息异常 - AccessKey: {}", accessKey, error);
            return new AuthenticationException("用户信息查询失败");
        });
    }

    /**
     * 验证签名
     * 
     * 签名验证流程：
     * 1. 构建canonical字符串（与客户端算法完全一致）
     * 2. 使用用户SecretKey计算HMAC-SHA256签名
     * 3. 使用常量时间比较防止时序攻击
     * 
     * Canonical字符串格式（v2版本）：
     * method + "\n" + path + "\n" + contentSha256 + "\n" + timestamp + "\n" + nonce
     * 
     * 技术要点：
     * - 复用ApiSignUtils.buildCanonicalString确保算法一致性
     * - 使用MessageDigest.isEqual进行常量时间比较
     * - 所有null值转为空字符串，避免NullPointerException
     * 
     * 安全考虑：
     * - 常量时间比较：防止通过时间差分析推断签名
     * - 完整性验证：包含请求方法、路径、内容等关键信息
     * - 密钥保护：SecretKey仅在内存中使用，不记录日志
     * 
     * @param exchange ServerWebExchange对象
     * @param user 用户信息
     * @param params 认证参数
     * @return 验证成功的用户信息Mono
     */
    private Mono<User> validateSignature(ServerWebExchange exchange, User user, AuthParams params) {
        try {
            // 获取请求信息
            String method = exchange.getAttribute("request.method");
            String platformPath = exchange.getAttribute("platform.path");
            
            // 构建canonical字符串（与客户端SDK完全一致）
            String canonical = ApiSignUtils.buildCanonicalString(
                method, 
                platformPath, 
                params.contentSha256, 
                params.timestamp, 
                params.nonce
            );
            
            // 计算期望的签名
            String expectedSign = ApiSignUtils.hmacSha256Hex(canonical, user.getSecretKey());
            
            // 常量时间比较（防止时序攻击）
            boolean signatureValid = MessageDigest.isEqual(
                params.sign.getBytes(), 
                expectedSign.getBytes()
            );
            
            if (!signatureValid) {
                log.warn("签名验证失败 - AccessKey: {}, 期望: {}, 实际: {}", 
                        user.getAccessKey(), expectedSign, params.sign);
                return Mono.error(new AuthenticationException("签名验证失败"));
            }
            
            log.debug("签名验证成功 - AccessKey: {}, UserId: {}", user.getAccessKey(), user.getId());
            return Mono.just(user);
            
        } catch (Exception e) {
            log.error("签名验证异常 - AccessKey: {}", user.getAccessKey(), e);
            return Mono.error(new AuthenticationException("签名验证异常"));
        }
    }

    /**
     * 防重放攻击验证
     * 
     * 重放攻击原理：
     * - 攻击者截获合法请求
     * - 在签名有效期内重复发送该请求
     * - 绕过正常的业务逻辑控制
     * 
     * 防护机制：
     * - 使用Redis存储已使用的nonce
     * - nonce过期时间等于签名有效期
     * - 使用setIfAbsent原子操作确保nonce唯一性
     * - 相同nonce在有效期内只能使用一次
     * 
     * Redis操作：
     * - Key格式：replay:accessKey:nonce
     * - Value：固定值"1"（仅用于存在性检查）
     * - 过期时间：与签名有效期相同
     * - 原子性：setIfAbsent保证并发安全
     * 
     * 性能优化：
     * - 异步Redis操作，不阻塞主流程
     * - 合理的过期时间，避免Redis内存积累
     * - 可考虑使用布隆过滤器优化内存使用
     * 
     * @param accessKey API访问密钥
     * @param nonce 随机字符串
     * @return true-允许请求，false-检测到重放攻击
     */
    private Mono<Boolean> preventReplayAttack(String accessKey, String nonce) {
        String replayKey = "replay:" + accessKey + ":" + nonce;
        long expireSeconds = getSecurityConfig().getSignatureTimeoutSeconds();
        
        return redisTemplate.opsForValue()
            .setIfAbsent(replayKey, "1", Duration.ofSeconds(expireSeconds))
            .doOnNext(success -> {
                if (success) {
                    log.debug("防重放检查通过 - AccessKey: {}, Nonce: {}", accessKey, nonce);
                } else {
                    log.warn("检测到重放攻击 - AccessKey: {}, Nonce: {}", accessKey, nonce);
                }
            })
            .onErrorResume(error -> {
                // Redis异常时的降级策略：记录日志但允许请求通过
                log.error("Redis防重放检查异常，降级允许请求 - AccessKey: {}, Nonce: {}", 
                         accessKey, nonce, error);
                return Mono.just(true);
            });
    }

    /**
     * 认证成功后续处理
     * 
     * 成功流程：
     * - 将认证用户信息存储到Exchange attributes
     * - 记录认证成功指标
     * - 继续执行后续过滤器
     * 
     * @param exchange ServerWebExchange对象
     * @param chain 过滤器链
     * @param user 认证用户
     * @param startTime 开始时间
     * @return 继续执行的Mono
     */
    private Mono<Void> proceedWithAuthentication(ServerWebExchange exchange, 
                                               GatewayFilterChain chain, 
                                               User user, long startTime) {
        // 将认证用户存储到Exchange attributes供后续过滤器使用
        exchange.getAttributes().put("authenticated.user", user);
        
        log.debug("用户认证成功 - UserId: {}, AccessKey: {}", user.getId(), user.getAccessKey());
        recordFilterMetrics("AuthenticationFilter", startTime, true, null);
        
        return chain.filter(exchange);
    }

    /**
     * 获取过滤器启用状态
     * 
     * 配置路径：xiaoxin.gateway.filters.authentication
     * 
     * @return true-启用，false-禁用
     */
    @Override
    protected boolean isEnabled() {
        return gatewayProperties.getFilters().isAuthentication();
    }

    /**
     * 过滤器执行顺序
     * 
     * 顺序安排：
     * - LoggingFilter(-100)：记录请求信息
     * - SecurityFilter(-90)：IP白名单验证
     * - AuthenticationFilter(-80)：用户认证 ← 当前过滤器
     * - InterfaceFilter(-70)：接口验证
     * 
     * 设计理由：
     * - 在安全验证后进行认证，确保请求来源可信
     * - 在接口验证前完成用户认证，为权限检查提供用户信息
     * - 签名验证计算量较大，放在IP验证后可减少无效计算
     * 
     * @return 执行顺序值
     */
    @Override
    public int getOrder() {
        return -80;
    }

    /**
     * 认证参数内部类
     * 
     * 封装从HTTP头中提取的认证参数
     */
    private static class AuthParams {
        String accessKey;
        String nonce;
        String timestamp;
        String sign;
        String contentSha256;
    }

    /**
     * 认证异常类
     * 
     * 用于认证过程中的异常处理
     */
    private static class AuthenticationException extends RuntimeException {
        public AuthenticationException(String message) {
            super(message);
        }
        
        public AuthenticationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
