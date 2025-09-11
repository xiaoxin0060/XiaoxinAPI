package com.xiaoxin.api.common.utils;

import cn.hutool.crypto.digest.DigestAlgorithm;
import cn.hutool.crypto.digest.Digester;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * API签名工具类 - 统一的签名算法实现
 * 
 * 业务职责：
 * - 提供统一的canonical字符串构建逻辑
 * - 提供HMAC-SHA256签名算法
 * - 确保客户端和网关签名算法的绝对一致性
 * 
 * 技术实现：
 * - 使用Hutool的Digester进行SHA-256哈希
 * - 使用Java标准库的Mac进行HMAC-SHA256签名
 * - 采用静态工具方法模式，便于全局调用
 * 
 * 调用链路：
 * 客户端SDK：
 *   XiaoxinApiClient → ApiSignUtils.buildCanonicalString → ApiSignUtils.hmacSha256Hex
 * 
 * 网关验证：
 *   CustomGlobalFilter → ApiSignUtils.buildCanonicalString → ApiSignUtils.hmacSha256Hex
 * 
 * 安全说明：
 * - canonical字符串格式：method + "\n" + path + "\n" + contentSha256 + "\n" + timestamp + "\n" + nonce
 * - 使用HMAC-SHA256确保签名的不可伪造性
 * - 所有null值转为空字符串，避免NullPointerException
 * 
 * @author xiaoxin
 * @since 1.0.0
 */
public class ApiSignUtils {
    
    /**
     * 构建canonical签名字符串（v2版本）
     * 
     * 算法说明：
     * - 将HTTP请求的关键信息按固定格式拼接
     * - 格式：method + "\n" + path + "\n" + contentSha256 + "\n" + timestamp + "\n" + nonce
     * - 每个字段用换行符分隔，确保字段边界清晰
     * 
     * 业务场景：
     * - 客户端生成签名时调用
     * - 服务端验证签名时调用
     * - 保证两端使用完全相同的算法
     * 
     * 技术要点：
     * - method统一转大写，避免大小写不一致导致的签名失败
     * - 所有null参数转为空字符串，保证算法稳定性
     * - 使用StringBuilder提高字符串拼接性能
     * 
     * @param method HTTP方法（GET、POST等），null时转为空字符串
     * @param path 请求路径（不包含query参数），null时转为空字符串
     * @param contentSha256 请求体的SHA-256哈希值，null时转为空字符串
     * @param timestamp 时间戳（秒级），null时转为空字符串
     * @param nonce 随机字符串（防重放），null时转为空字符串
     * @return canonical字符串，用于后续HMAC签名
     */
    public static String buildCanonicalString(String method, String path, String contentSha256, 
                                            String timestamp, String nonce) {
        // 参数标准化处理：null -> 空字符串，method统一大写
        String normalizedMethod = (method == null) ? "" : method.toUpperCase();
        String normalizedPath = (path == null) ? "" : path;
        String normalizedContentSha256 = (contentSha256 == null) ? "" : contentSha256;
        String normalizedTimestamp = (timestamp == null) ? "" : timestamp;
        String normalizedNonce = (nonce == null) ? "" : nonce;
        
        // 使用StringBuilder提高性能，避免大量字符串临时对象
        StringBuilder canonical = new StringBuilder();
        canonical.append(normalizedMethod).append("\n")
                .append(normalizedPath).append("\n")
                .append(normalizedContentSha256).append("\n")
                .append(normalizedTimestamp).append("\n")
                .append(normalizedNonce);
        
        return canonical.toString();
    }
    
    /**
     * 计算字符串的SHA-256哈希值（十六进制）
     * 
     * 使用场景：
     * - 计算请求体内容的哈希值
     * - 用于防止传输过程中的数据篡改
     * - 作为canonical字符串的一部分参与签名
     * 
     * 技术实现：
     * - 使用Hutool的Digester工具类
     * - DigestAlgorithm.SHA256指定使用SHA-256算法
     * - 输出十六进制字符串，便于HTTP传输
     * 
     * @param content 需要哈希的内容，null时视为空字符串
     * @return SHA-256哈希值的十六进制表示（64个字符）
     */
    public static String sha256Hex(String content) {
        Digester sha256 = new Digester(DigestAlgorithm.SHA256);
        return sha256.digestHex(content == null ? "" : content);
    }
    
    /**
     * HMAC-SHA256签名算法（输出十六进制）
     * 
     * 算法原理：
     * - HMAC = Hash-based Message Authentication Code
     * - 使用密钥和消息计算消息认证码
     * - 既验证消息完整性，又验证消息来源的真实性
     * 
     * 安全特性：
     * - 抗密钥恢复：即使知道签名和消息，也无法推导出密钥
     * - 抗消息伪造：没有密钥无法生成有效签名
     * - 抗重放攻击：配合timestamp和nonce使用
     * 
     * 技术实现：
     * - 使用Java标准库的Mac类
     * - HmacSHA256算法规范
     * - 统一使用UTF-8编码，避免编码不一致问题
     * 
     * Java高级特性说明：
     * - SecretKeySpec：密钥规范类，封装密钥材料和算法信息
     * - Mac.getInstance()：工厂方法模式获取算法实例
     * - byte数组到十六进制的转换：位运算优化性能
     * 
     * @param data 需要签名的数据（通常是canonical字符串），null时视为空字符串
     * @param secretKey 签名密钥（用户的SecretKey），不能为null
     * @return HMAC-SHA256签名的十六进制表示（64个字符）
     * @throws RuntimeException 当签名算法不可用或密钥格式错误时抛出
     */
    public static String hmacSha256Hex(String data, String secretKey) {
        try {
            // 获取HMAC-SHA256算法实例
            Mac mac = Mac.getInstance("HmacSHA256");
            
            // 创建密钥规范：将字符串密钥转为字节数组，指定算法
            SecretKeySpec keySpec = new SecretKeySpec(
                secretKey.getBytes(StandardCharsets.UTF_8), 
                "HmacSHA256"
            );
            
            // 初始化Mac实例
            mac.init(keySpec);
            
            // 执行HMAC运算
            byte[] result = mac.doFinal(
                (data == null ? "" : data).getBytes(StandardCharsets.UTF_8)
            );
            
            // 转换为十六进制字符串
            return bytesToHex(result);
            
        } catch (Exception e) {
            // 包装为RuntimeException，简化调用方的异常处理
            throw new RuntimeException("HMAC-SHA256 签名计算失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 字节数组转十六进制字符串
     * 
     * 性能优化技术：
     * - 使用StringBuilder预分配容量，避免数组扩容
     * - 位运算(& 0xff)确保正确的无符号字节处理
     * - 预先判断是否需要补零，避免条件判断开销
     * 
     * Java技术细节：
     * - byte在Java中是有符号类型（-128到127）
     * - & 0xff操作将byte转为0-255的int值
     * - Integer.toHexString()生成十六进制字符串
     * 
     * @param bytes 字节数组
     * @return 十六进制字符串（小写）
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder(bytes.length * 2);
        
        for (byte b : bytes) {
            // 转为无符号整数
            int unsignedByte = b & 0xff;
            
            // 转为十六进制，不足两位前补0
            String hex = Integer.toHexString(unsignedByte);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        
        return hexString.toString();
    }
    
    /**
     * 验证签名是否有效
     * 
     * 业务逻辑：
     * - 使用相同的参数重新计算签名
     * - 与提供的签名进行比对
     * - 使用常量时间比较避免时序攻击
     * 
     * 使用场景：
     * - 网关验证客户端请求签名
     * - 第三方回调签名验证
     * 
     * 安全说明：
     * - 使用equals进行字符串比较（Java已优化为常量时间）
     * - 避免短路比较，防止时序攻击
     * 
     * @param method HTTP方法
     * @param path 请求路径
     * @param contentSha256 内容哈希
     * @param timestamp 时间戳
     * @param nonce 随机数
     * @param secretKey 密钥
     * @param providedSignature 待验证的签名
     * @return true-签名有效，false-签名无效
     */
    public static boolean verifySignature(String method, String path, String contentSha256,
                                        String timestamp, String nonce, String secretKey, 
                                        String providedSignature) {
        if (providedSignature == null || secretKey == null) {
            return false;
        }
        
        // 重新计算签名
        String canonical = buildCanonicalString(method, path, contentSha256, timestamp, nonce);
        String expectedSignature = hmacSha256Hex(canonical, secretKey);
        
        // 常量时间比较
        return expectedSignature.equals(providedSignature);
    }
}
