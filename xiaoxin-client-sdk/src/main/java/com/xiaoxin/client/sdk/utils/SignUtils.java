package com.xiaoxin.client.sdk.utils;

import cn.hutool.crypto.digest.DigestAlgorithm;
import cn.hutool.crypto.digest.Digester;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 签名工具
 */
public class SignUtils {
    /**
     * 生成签名
     * @param body
     * @param secretKey
     * @return
     */
    public static String genSign(String body, String secretKey) {
        Digester md5 = new Digester(DigestAlgorithm.SHA256);
        String content = body + "." + secretKey;
        return md5.digestHex(content);
    }

    /**
     * 计算字符串的 SHA-256 十六进制摘要
     */
    public static String sha256Hex(String content) {
        Digester sha256 = new Digester(DigestAlgorithm.SHA256);
        return sha256.digestHex(content == null ? "" : content);
    }

    /**
     * HMAC-SHA256 签名，输出十六进制字符串
     */
    public static String hmacSha256Hex(String data, String secretKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] result = mac.doFinal((data == null ? "" : data).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return toHex(result);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 sign error", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String hex = Integer.toHexString(b & 0xff);
            if (hex.length() == 1) sb.append('0');
            sb.append(hex);
        }
        return sb.toString();
    }
}
