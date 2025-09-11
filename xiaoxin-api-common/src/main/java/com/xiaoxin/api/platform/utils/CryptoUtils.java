package com.xiaoxin.api.platform.utils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 加密工具类 - AES-GCM对称加密
 * 
 * 统一的加密工具，供平台和网关模块使用
 * 用于SecretKey和认证配置的加密存储
 * 
 * 技术特性：
 * - AES-GCM算法：提供认证加密，既保密又防篡改
 * - 随机IV：每次加密使用不同的初始化向量
 * - AAD支持：额外认证数据用于上下文绑定
 * - Base64编码：便于数据库存储和传输
 * 
 * @author xiaoxin
 */
public class CryptoUtils {
    
    private static final String AES_ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * AES-GCM加密
     * 业务场景：
     * - 用户注册时加密SecretKey存储到数据库
     * - 接口认证配置的加密存储
     * - 重置密钥时的加密处理
     * 
     * @param keyBytes 密钥字节数组（32字节 AES-256）
     * @param aadBytes 额外认证数据（可以为null）
     * @param plainText 明文字符串
     * @return Base64编码的加密结果
     * @throws IllegalArgumentException 参数验证失败
     * @throws RuntimeException 加密操作失败
     */
    public static String aesGcmEncryptFromString(byte[] keyBytes, byte[] aadBytes, String plainText) {
        if (keyBytes == null || keyBytes.length != 32) {
            throw new IllegalArgumentException("密钥必须是32字节的AES-256密钥");
        }
        if (plainText == null) {
            throw new IllegalArgumentException("明文不能为null");
        }
        
        try {
            // 1. 创建密钥规范
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, AES_ALGORITHM);
            
            // 2. 生成随机IV（12字节，GCM推荐长度）
            byte[] iv = new byte[IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);
            
            // 3. 配置GCM参数（128位认证标签）
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            
            // 4. 初始化加密器
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);
            
            // 5. 设置额外认证数据（如果提供）
            if (aadBytes != null && aadBytes.length > 0) {
                cipher.updateAAD(aadBytes);
            }
            
            // 6. 执行加密（包含认证标签）
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            
            // 7. 拼接IV和密文：[IV(12字节)][密文+认证标签]
            byte[] encryptedWithIv = new byte[IV_LENGTH + cipherText.length];
            System.arraycopy(iv, 0, encryptedWithIv, 0, IV_LENGTH);
            System.arraycopy(cipherText, 0, encryptedWithIv, IV_LENGTH, cipherText.length);
            
            // 8. Base64编码便于存储
            return Base64.getEncoder().encodeToString(encryptedWithIv);
            
        } catch (Exception e) {
            throw new RuntimeException("AES-GCM加密失败：" + e.getMessage(), e);
        }
    }

    /**
     * AES-GCM解密
     * 业务场景：
     * - 用户登录时解密SecretKey
     * - 网关解密接口认证配置
     * - 获取用户信息时解密敏感数据
     * 
     * @param keyBytes 密钥字节数组（32字节 AES-256）
     * @param aadBytes 额外认证数据（必须与加密时一致）
     * @param encryptedBase64 Base64编码的加密数据
     * @return 解密后的明文字符串
     * @throws IllegalArgumentException 参数验证失败
     * @throws RuntimeException 解密操作失败或认证失败
     */
    public static String aesGcmDecryptToString(byte[] keyBytes, byte[] aadBytes, String encryptedBase64) {
        if (keyBytes == null || keyBytes.length != 32) {
            throw new IllegalArgumentException("密钥必须是32字节的AES-256密钥");
        }
        if (encryptedBase64 == null || encryptedBase64.isEmpty()) {
            throw new IllegalArgumentException("加密数据不能为空");
        }
        
        try {
            // 1. 解码Base64数据
            byte[] encryptedWithIv = Base64.getDecoder().decode(encryptedBase64);
            
            // 2. 验证数据长度（至少包含IV + 最小密文 + 认证标签）
            if (encryptedWithIv.length < IV_LENGTH + 16) {
                throw new IllegalArgumentException("加密数据格式错误：长度不足");
            }
            
            // 3. 分离IV和密文
            byte[] iv = new byte[IV_LENGTH];
            byte[] cipherText = new byte[encryptedWithIv.length - IV_LENGTH];
            System.arraycopy(encryptedWithIv, 0, iv, 0, IV_LENGTH);
            System.arraycopy(encryptedWithIv, IV_LENGTH, cipherText, 0, cipherText.length);
            
            // 4. 创建密钥规范
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, AES_ALGORITHM);
            
            // 5. 配置GCM参数（使用相同的IV）
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            
            // 6. 初始化解密器
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);
            
            // 7. 设置额外认证数据（如果提供，必须与加密时一致）
            if (aadBytes != null && aadBytes.length > 0) {
                cipher.updateAAD(aadBytes);
            }
            
            // 8. 执行解密并验证认证标签
            byte[] plainTextBytes = cipher.doFinal(cipherText);
            
            // 9. 返回明文
            return new String(plainTextBytes, StandardCharsets.UTF_8);
            
        } catch (Exception e) {
            throw new RuntimeException("AES-GCM解密失败：" + e.getMessage(), e);
        }
    }

    /**
     * 检测数据是否为加密格式
     * 
     * 通过检查数据格式判断是否为AES-GCM加密的数据
     * 用于智能判断SecretKey和认证配置是否需要解密
     * 
     * 判断逻辑：
     * 1. 必须是有效的Base64格式
     * 2. 解码后长度足够（IV + 最小密文 + 认证标签）
     * 
     * @param data 待检测的数据
     * @return true-已加密格式，false-明文格式
     */
    public static boolean isEncrypted(String data) {
        if (data == null || data.isEmpty()) {
            return false;
        }
        
        try {
            // 检查是否为有效的Base64格式
            byte[] decoded = Base64.getDecoder().decode(data);
            
            // 加密数据应该至少包含：
            // - IV: 12字节
            // - 最小密文: 1字节  
            // - 认证标签: 16字节
            // 总计至少29字节
            return decoded.length >= (IV_LENGTH + 1 + 16);
            
        } catch (IllegalArgumentException e) {
            // Base64解码失败，说明不是加密格式
            return false;
        }
    }
}
