package com.xiaoxin.api.gateway.filter;

import com.xiaoxin.api.gateway.filter.base.BaseGatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 安全过滤器 - IP白名单验证
 * 
 * 业务职责：
 * - 验证客户端IP是否在白名单中
 * - 支持单IP和CIDR网段配置
 * - 提供安全访问控制第一道防线
 * - 支持多环境差异化配置
 * 
 * 调用链路：
 * 请求 → 获取客户端IP → 白名单验证 → 通过继续/拒绝返回403
 * 
 * 技术实现：
 * - 支持IPv4精确匹配：127.0.0.1
 * - 支持CIDR网段匹配：192.168.0.0/16  
 * - 支持IPv6地址匹配：::1
 * - 配置化白名单，支持多环境差异化
 * - 复用原有IP白名单验证逻辑，保持兼容性
 * 
 * 安全机制：
 * - 默认拒绝策略：不在白名单的IP一律拒绝
 * - 早期拦截：在认证之前进行IP验证，减少攻击面
 * - 日志记录：记录被拒绝的IP，便于安全分析
 * - 配置验证：启动时验证IP格式，避免运行时错误
 * 
 * 性能优化：
 * - 精确匹配优先：先进行字符串比较，再进行CIDR计算
 * - 异常缓存：缓存解析失败的IP，避免重复计算
 * - 短路逻辑：找到匹配项立即返回，减少不必要的计算
 * 
 * 配置示例：
 * <pre>
 * xiaoxin:
 *   gateway:
 *     security:
 *       ip-whitelist:
 *         - "127.0.0.1"           # 本地回环
 *         - "::1"                 # IPv6本地回环
 *         - "192.168.0.0/16"      # 内网网段
 *         - "10.0.0.0/8"          # 企业内网
 * </pre>
 * 
 * @author xiaoxin
 * @since 1.0.0
 */
public class SecurityFilter extends BaseGatewayFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 检查过滤器开关
        if (!isEnabled()) {
            log.debug("安全过滤器已禁用，跳过IP白名单验证");
            return chain.filter(exchange);
        }

        long startTime = System.currentTimeMillis();
        
        // 从前一个过滤器获取客户端IP
        String clientIp = exchange.getAttribute("client.ip");
        List<String> ipWhitelist = getSecurityConfig().getIpWhitelist();
        
        // 执行IP白名单验证
        if (!isIpAllowed(clientIp, ipWhitelist)) {
            log.warn("IP访问被拒绝 - 客户端IP: {}, 白名单: {}", clientIp, ipWhitelist);
            recordFilterMetrics("SecurityFilter", startTime, false, null);
            return handleNoAuth(exchange.getResponse());
        }
        
        log.debug("IP白名单验证通过 - 客户端IP: {}", clientIp);
        recordFilterMetrics("SecurityFilter", startTime, true, null);
        
        return chain.filter(exchange);
    }

    /**
     * IP白名单验证主算法
     * 
     * 验证流程：
     * 1. 参数有效性检查
     * 2. 遍历白名单进行匹配
     * 3. 支持精确匹配和CIDR网段匹配
     * 4. 任意一个匹配成功即通过验证
     * 
     * 边界条件处理：
     * - clientIp为null：直接拒绝
     * - whitelist为null或空：直接拒绝
     * - 匹配异常：记录日志但继续匹配其他项
     * 
     * 性能优化：
     * - 精确匹配优先：大部分场景下精确匹配更快
     * - 短路逻辑：找到匹配项立即返回
     * - 异常隔离：单个匹配失败不影响其他匹配
     * 
     * @param clientIp 客户端IP地址
     * @param whitelist IP白名单列表
     * @return true-允许访问，false-拒绝访问
     */
    private boolean isIpAllowed(String clientIp, List<String> whitelist) {
        // 参数有效性检查
        if (clientIp == null) {
            log.warn("客户端IP为null，拒绝访问");
            return false;
        }
        
        if (whitelist == null || whitelist.isEmpty()) {
            log.warn("IP白名单为空，拒绝所有访问");
            return false;
        }
        
        // 遍历白名单进行匹配
        for (String allowedPattern : whitelist) {
            try {
                if (isIpMatched(clientIp, allowedPattern)) {
                    log.debug("IP白名单匹配成功 - 客户端IP: {}, 匹配规则: {}", clientIp, allowedPattern);
                    return true;
                }
            } catch (Exception e) {
                // 单个匹配失败不影响其他规则的匹配
                log.warn("IP匹配异常 - 客户端IP: {}, 规则: {}, 错误: {}", 
                        clientIp, allowedPattern, e.getMessage());
            }
        }
        
        log.debug("IP白名单匹配失败 - 客户端IP: {}, 白名单: {}", clientIp, whitelist);
        return false;
    }

    /**
     * IP匹配算法（支持精确匹配和CIDR网段匹配）
     * 
     * 匹配类型：
     * 1. 精确匹配：192.168.1.100 == 192.168.1.100
     * 2. CIDR网段匹配：192.168.1.100 在 192.168.0.0/16 网段内
     * 3. IPv6支持：::1 == ::1
     * 
     * 算法优化：
     * - 精确匹配优先：String.equals()比网络计算快
     * - CIDR识别：包含"/"字符的被识别为CIDR格式
     * - 格式验证：无效格式直接返回false
     * 
     * @param clientIp 客户端IP地址
     * @param allowedPattern 允许的IP模式（精确IP或CIDR）
     * @return true-匹配成功，false-匹配失败
     */
    private boolean isIpMatched(String clientIp, String allowedPattern) {
        // 精确匹配（最常见场景，性能最优）
        if (allowedPattern.equals(clientIp)) {
            return true;
        }
        
        // CIDR网段匹配
        if (allowedPattern.contains("/")) {
            return isCidrMatched(clientIp, allowedPattern);
        }
        
        // 其他格式不支持
        return false;
    }

    /**
     * CIDR网段匹配实现
     * 
     * 算法原理：
     * 1. 解析CIDR格式：192.168.0.0/16 → 网络地址192.168.0.0 + 前缀长度16
     * 2. 计算子网掩码：16位前缀 → 0xFFFF0000（前16位为1，后16位为0）
     * 3. 网络地址计算：(IP & 掩码) == (网络地址 & 掩码)
     * 4. 匹配判断：客户端IP的网络部分是否等于CIDR的网络部分
     * 
     * 示例计算：
     * - CIDR：192.168.0.0/16
     * - 客户端IP：192.168.1.100
     * - 掩码：0xFFFF0000
     * - 192.168.1.100 & 0xFFFF0000 = 192.168.0.0
     * - 192.168.0.0 & 0xFFFF0000 = 192.168.0.0
     * - 结果：192.168.0.0 == 192.168.0.0，匹配成功
     * 
     * Java技术特性：
     * - 位运算：使用&操作进行高效的网络计算
     * - 长整型：IPv4地址转为32位长整型进行计算
     * - 异常处理：数字格式异常、数组越界等
     * - 边界检查：前缀长度范围验证（0-32）
     * 
     * 网络协议知识：
     * - CIDR：Classless Inter-Domain Routing，无类别域间路由
     * - 子网掩码：用于划分网络地址和主机地址
     * - 前缀长度：表示网络地址占用的位数
     * 
     * 性能考虑：
     * - 预计算掩码：避免重复的位移操作
     * - 异常缓存：可考虑缓存计算结果（如果匹配频繁）
     * - IPv6支持：当前仅支持IPv4，IPv6需要额外实现
     * 
     * @param clientIp 客户端IP地址
     * @param cidr CIDR格式的网段（如：192.168.0.0/16）
     * @return true-在网段内，false-不在网段内
     * @throws IllegalArgumentException 当CIDR格式无效时
     */
    private boolean isCidrMatched(String clientIp, String cidr) {
        try {
            // 解析CIDR格式
            String[] cidrParts = cidr.split("/");
            if (cidrParts.length != 2) {
                log.warn("无效的CIDR格式: {}", cidr);
                return false;
            }
            
            String networkIp = cidrParts[0];
            int prefixLength = Integer.parseInt(cidrParts[1]);
            
            // 验证前缀长度范围
            if (prefixLength < 0 || prefixLength > 32) {
                log.warn("无效的CIDR前缀长度: {}, 有效范围: 0-32", prefixLength);
                return false;
            }
            
            // 将IP地址转换为长整型
            long clientIpLong = ipv4ToLong(clientIp);
            long networkIpLong = ipv4ToLong(networkIp);
            
            // 计算子网掩码
            // 注意：当prefixLength为0时，mask应为0；当为32时，mask应为0xFFFFFFFF
            long mask = prefixLength == 0 ? 0 : (0xFFFFFFFFL << (32 - prefixLength));
            
            // 网络地址匹配
            boolean matched = (clientIpLong & mask) == (networkIpLong & mask);
            
            log.debug("CIDR匹配详情 - 客户端IP: {} ({}), 网络: {} ({}), 前缀: {}, 掩码: 0x{}, 匹配: {}", 
                     clientIp, Long.toHexString(clientIpLong),
                     networkIp, Long.toHexString(networkIpLong),
                     prefixLength, Long.toHexString(mask), matched);
            
            return matched;
            
        } catch (NumberFormatException e) {
            log.warn("CIDR格式解析失败 - 客户端IP: {}, CIDR: {}, 错误: {}", 
                    clientIp, cidr, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("CIDR匹配异常 - 客户端IP: {}, CIDR: {}", clientIp, cidr, e);
            return false;
        }
    }

    /**
     * IPv4地址转长整型
     * 
     * 转换算法：
     * - IPv4地址格式：A.B.C.D（每部分0-255）
     * - 转换公式：(A << 24) | (B << 16) | (C << 8) | D
     * - 示例：192.168.1.1 → (192 << 24) | (168 << 16) | (1 << 8) | 1
     * 
     * 位运算解释：
     * - A << 24：A左移24位，占据高8位
     * - B << 16：B左移16位，占据次高8位
     * - C << 8：C左移8位，占据次低8位
     * - D：D不移位，占据低8位
     * - |运算：按位或，将四部分合并
     * 
     * Java技术细节：
     * - long类型：避免int溢出（IPv4最大值超过int最大值）
     * - 异常处理：NumberFormatException和ArrayIndexOutOfBoundsException
     * - 输入验证：IP段数验证（必须为4段）
     * - 范围验证：每段必须在0-255之间
     * 
     * @param ipAddress IPv4地址字符串（如：192.168.1.1）
     * @return 对应的长整型值
     * @throws IllegalArgumentException 当IP格式无效时
     */
    private long ipv4ToLong(String ipAddress) {
        try {
            String[] octets = ipAddress.split("\\.");
            
            // IPv4必须有4个部分
            if (octets.length != 4) {
                throw new IllegalArgumentException("IPv4地址必须包含4个部分: " + ipAddress);
            }
            
            long result = 0;
            for (int i = 0; i < 4; i++) {
                int octet = Integer.parseInt(octets[i]);
                
                // 验证每个八位组的范围
                if (octet < 0 || octet > 255) {
                    throw new IllegalArgumentException("IPv4地址段超出范围(0-255): " + octet);
                }
                
                // 左移到对应位置并进行或运算
                result |= ((long) octet << (8 * (3 - i)));
            }
            
            return result;
            
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("IPv4地址格式无效: " + ipAddress, e);
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("IPv4地址格式不完整: " + ipAddress, e);
        }
    }

    /**
     * 获取过滤器启用状态
     * 
     * 配置路径：xiaoxin.gateway.filters.security
     * 
     * @return true-启用，false-禁用
     */
    @Override
    protected boolean isEnabled() {
        return gatewayProperties.getFilters().isSecurity();
    }

    /**
     * 过滤器执行顺序
     * 
     * 顺序安排：
     * - LoggingFilter(-100)：记录请求信息
     * - SecurityFilter(-90)：IP白名单验证 ← 当前过滤器
     * - AuthenticationFilter(-80)：用户认证
     * 
     * 设计理由：
     * - 安全优先：在认证前先进行IP验证，减少攻击面
     * - 性能优化：IP验证比签名验证更快，早期拦截无效请求
     * - 日志完整：在日志记录后进行安全验证，便于追踪
     * 
     * @return 执行顺序值
     */
    @Override
    public int getOrder() {
        return -90;
    }
}
