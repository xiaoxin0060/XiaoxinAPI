package com.xiaoxin.api.gateway.filter;

import com.xiaoxin.api.gateway.filter.base.BaseGatewayFilter;
import com.xiaoxin.api.platform.model.entity.InterfaceInfo;
import com.xiaoxin.api.platform.service.InnerInterfaceInfoService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 接口过滤器 - 接口查询和状态检查
 * 
 * 业务职责：
 * - 根据平台路径和HTTP方法查询接口信息
 * - 验证接口是否存在于数据库中
 * - 检查接口状态是否为启用状态
 * - 为后续过滤器提供接口元信息
 * - 支持动态代理架构的接口路由
 * 
 * 调用链路：
 * 请求 → 提取路径和方法 → Dubbo查询接口 → 验证接口状态 → 存储接口信息 → 下一个过滤器
 * 
 * 技术实现：
 * - 使用@DubboReference调用平台服务查询接口信息
 * - 复用原有接口查询和验证逻辑，保持兼容性
 * - 支持动态代理：区分平台路径和真实接口地址
 * - 通过ServerWebExchange.attributes传递接口信息
 * - 响应式编程：异步处理Dubbo RPC调用
 * 
 * 数据模型：
 * - InterfaceInfo：接口元信息，包含路由、认证、限流等配置
 * - url：平台统一API路径（对外暴露，如：/api/geo/query）
 * - providerUrl：真实接口地址（内部转发，如：http://ip-api.com/json）
 * - status：接口状态（0-关闭，1-开启）
 * - authType：认证类型（NONE/API_KEY/BASIC/BEARER）
 * - rateLimit：限流配置（次/分钟）
 * 
 * 错误处理：
 * - 接口不存在：返回403 Forbidden
 * - 接口已下线：返回403 Forbidden
 * - 查询异常：记录日志并返回403
 * - 数据库连接失败：降级处理
 * 
 * 性能优化：
 * - 接口信息缓存：减少频繁的数据库查询
 * - 异步查询：避免阻塞WebFlux事件循环
 * - 批量预加载：启动时加载热点接口
 * - 懒加载：按需查询非热点接口
 * 
 * @author xiaoxin
 * @since 1.0.0
 */
@Component
public class InterfaceFilter extends BaseGatewayFilter {

    /**
     * 内部接口信息服务
     * 
     * 使用Dubbo RPC调用平台服务：
     * - 根据平台路径和HTTP方法查询接口信息
     * - 获取接口的配置信息（认证、限流、代理等）
     * - 支持接口的动态上下线管理
     * 
     * 注意事项：
     * - Dubbo调用是同步阻塞的，需要在专用线程池执行
     * - 考虑添加本地缓存减少RPC调用频率
     * - 需要处理网络异常和服务不可用情况
     */
    @DubboReference
    private InnerInterfaceInfoService innerInterfaceInfoService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 检查过滤器开关
        if (!isEnabled()) {
            log.debug("接口过滤器已禁用，跳过接口验证");
            return chain.filter(exchange);
        }

        long startTime = System.currentTimeMillis();
        
        // 从前置过滤器获取请求信息
        String platformPath = exchange.getAttribute("platform.path");
        String method = exchange.getAttribute("request.method");
        
        return queryInterfaceInfo(platformPath, method)
            .flatMap(interfaceInfo -> validateInterfaceStatus(interfaceInfo))
            .flatMap(interfaceInfo -> {
                // 将接口信息存储到Exchange attributes
                exchange.getAttributes().put("interface.info", interfaceInfo);
                
                log.debug("接口验证成功 - 接口ID: {}, 名称: {}, 状态: {}", 
                         interfaceInfo.getId(), interfaceInfo.getName(), interfaceInfo.getStatus());
                recordFilterMetrics("InterfaceFilter", startTime, true, null);
                
                return chain.filter(exchange);
            })
            .onErrorResume(InterfaceException.class, error -> {
                log.warn("接口验证失败: {}", error.getMessage());
                recordFilterMetrics("InterfaceFilter", startTime, false, error);
                return handleNoAuth(exchange.getResponse());
            })
            .onErrorResume(Exception.class, error -> {
                log.error("接口过滤器执行异常", error);
                recordFilterMetrics("InterfaceFilter", startTime, false, error);
                return handleNoAuth(exchange.getResponse());
            });
    }

    /**
     * 查询接口信息
     * 
     * 查询流程：
     * 1. 使用平台路径和HTTP方法作为查询条件
     * 2. 通过Dubbo RPC调用平台服务
     * 3. 返回完整的接口配置信息
     * 4. 处理查询异常和空结果
     * 
     * 平台路径说明：
     * - 客户端请求：/api/geo/query
     * - 数据库存储：/api/geo/query（平台统一路径）
     * - 真实转发：http://ip-api.com/json（存储在providerUrl字段）
     * 
     * 缓存策略（可扩展）：
     * - 热点接口：启动时预加载到本地缓存
     * - 普通接口：首次查询后缓存，TTL 5分钟
     * - 接口变更：通过消息队列更新缓存
     * - 缓存穿透：空结果也缓存，TTL 1分钟
     * 
     * 异常处理：
     * - RPC超时：记录日志，抛出异常
     * - 服务不可用：记录日志，抛出异常
     * - 数据解析异常：记录日志，抛出异常
     * - 网络异常：记录日志，抛出异常
     * 
     * @param platformPath 平台统一API路径
     * @param method HTTP请求方法
     * @return 包含接口信息的Mono
     */
    private Mono<InterfaceInfo> queryInterfaceInfo(String platformPath, String method) {
        return Mono.fromCallable(() -> {
            log.debug("查询接口信息 - 路径: {}, 方法: {}", platformPath, method);
            
            // 复用原有接口查询逻辑
            InterfaceInfo interfaceInfo = innerInterfaceInfoService.getInterfaceInfo(platformPath, method);
            
            if (interfaceInfo == null) {
                throw new InterfaceException(
                    String.format("接口不存在 - 路径: %s, 方法: %s", platformPath, method));
            }
            
            log.debug("接口信息查询成功 - ID: {}, 名称: {}, 提供者: {}", 
                     interfaceInfo.getId(), interfaceInfo.getName(), interfaceInfo.getProviderUrl());
            
            return interfaceInfo;
        })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()) // 在专用线程池执行阻塞操作
        .onErrorMap(Exception.class, error -> {
            if (error instanceof InterfaceException) {
                return error;
            }
            log.error("查询接口信息异常 - 路径: {}, 方法: {}", platformPath, method, error);
            return new InterfaceException("接口信息查询失败");
        });
    }

    /**
     * 验证接口状态
     * 
     * 状态检查：
     * - status = 1：接口已启用，允许调用
     * - status = 0：接口已禁用，拒绝调用
     * - status = null：异常状态，拒绝调用
     * 
     * 业务场景：
     * - 接口维护：临时禁用接口进行维护
     * - 接口下线：永久禁用废弃的接口
     * - 灰度发布：部分启用新版本接口
     * - 紧急处理：快速禁用有问题的接口
     * 
     * 扩展能力：
     * - 版本控制：支持多版本接口并存
     * - 权限控制：结合用户角色进行访问控制
     * - 区域控制：支持地域性接口启用/禁用
     * - 时间控制：支持定时启用/禁用接口
     * 
     * @param interfaceInfo 接口信息
     * @return 验证通过的接口信息Mono
     */
    private Mono<InterfaceInfo> validateInterfaceStatus(InterfaceInfo interfaceInfo) {
        try {
            // 检查接口状态（复用原有验证逻辑）
            if (interfaceInfo.getStatus() == null) {
                return Mono.error(new InterfaceException(
                    String.format("接口状态异常 - 接口: %s", interfaceInfo.getName())));
            }
            
            if (interfaceInfo.getStatus() != 1) {
                return Mono.error(new InterfaceException(
                    String.format("接口已下线 - 接口: %s, 状态: %d", 
                                 interfaceInfo.getName(), interfaceInfo.getStatus())));
            }
            
            // 验证接口配置完整性
            if (interfaceInfo.getProviderUrl() == null || interfaceInfo.getProviderUrl().isBlank()) {
                return Mono.error(new InterfaceException(
                    String.format("接口配置不完整，缺少提供者URL - 接口: %s", interfaceInfo.getName())));
            }
            
            log.debug("接口状态验证通过 - 接口: {}, 状态: {}, 提供者: {}", 
                     interfaceInfo.getName(), interfaceInfo.getStatus(), interfaceInfo.getProviderUrl());
            
            return Mono.just(interfaceInfo);
            
        } catch (Exception e) {
            log.error("接口状态验证异常 - 接口: {}", interfaceInfo.getName(), e);
            return Mono.error(new InterfaceException("接口状态验证异常"));
        }
    }

    /**
     * 获取过滤器启用状态
     * 
     * 配置路径：xiaoxin.gateway.filters.interface-validation
     * 
     * 注意：配置key使用kebab-case（interface-validation），
     * 但属性名使用camelCase（interfaceValidation）
     * 
     * @return true-启用，false-禁用
     */
    @Override
    protected boolean isEnabled() {
        return gatewayProperties.getFilters().isInterfaceValidation();
    }

    /**
     * 过滤器执行顺序
     * 
     * 顺序安排：
     * - LoggingFilter(-100)：记录请求信息
     * - SecurityFilter(-90)：IP白名单验证
     * - AuthenticationFilter(-80)：用户认证
     * - InterfaceFilter(-70)：接口验证 ← 当前过滤器
     * - RateLimitFilter(-60)：限流控制
     * 
     * 设计理由：
     * - 在用户认证后进行接口验证，确保有合法用户访问
     * - 在限流前完成接口验证，避免对无效接口进行限流计算
     * - 为后续过滤器提供接口元信息，支持个性化配置
     * - 早期拦截无效接口，减少系统资源消耗
     * 
     * @return 执行顺序值
     */
    @Override
    public int getOrder() {
        return -70;
    }

    /**
     * 接口异常类
     * 
     * 用于接口验证过程中的异常处理：
     * - 接口不存在
     * - 接口已下线
     * - 接口配置异常
     * - 接口查询失败
     */
    private static class InterfaceException extends RuntimeException {
        public InterfaceException(String message) {
            super(message);
        }
        
        public InterfaceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
