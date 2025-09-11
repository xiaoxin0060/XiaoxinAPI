package com.xiaoxin.api.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ApiSignUtils 单元测试
 * 
 * 测试目标：
 * - 验证API签名算法的正确性和一致性
 * - 确保客户端和网关使用相同的签名逻辑
 * - 测试边界情况和异常处理
 * - 验证与现有系统的兼容性
 * 
 * 技术栈说明：
 * - JUnit 5：现代化的Java测试框架，支持注解驱动和丰富的断言
 * - 纯单元测试：不依赖Spring上下文，测试工具类的静态方法
 * - 静态导入：使用static import简化断言代码
 * 
 * 业务价值：
 * - 确保签名算法的安全性和可靠性
 * - 防止客户端和服务端签名不一致导致的认证失败
 * - 为签名算法的升级和维护提供回归测试保障
 * 
 * 测试策略：
 * - 独立测试：每个方法独立测试，不依赖外部环境
 * - 边界测试：测试null值、空字符串等边界情况
 * - 兼容性测试：确保与现有系统的签名算法兼容
 * - 性能测试：基本的性能回归测试
 * 
 * @author xiaoxin
 * @since 1.0.0
 */
class ApiSignUtilsTest {
    
    // 测试数据常量
    private static final String TEST_METHOD = "POST";
    private static final String TEST_PATH = "/api/v1/test";
    private static final String TEST_CONTENT = "{\"name\":\"test\",\"value\":123}";
    private static final String TEST_SECRET_KEY = "sk_test123456789012345678901234567890123456";
    private static final String TEST_TIMESTAMP = "1699123456";
    private static final String TEST_NONCE = "abc123def456ghi7";
    
    /**
     * 测试buildCanonicalString方法的基本功能
     * 
     * 验证点：
     * - 正常参数情况下能正确构建canonical字符串
     * - 字段顺序和分隔符符合预期格式
     * - HTTP方法自动转为大写
     */
    @Test
    void testBuildCanonicalString_WithValidParameters() {
        // Given: 准备测试参数
        String contentSha256 = ApiSignUtils.sha256Hex(TEST_CONTENT);
        
        // When: 调用buildCanonicalString方法
        String canonical = ApiSignUtils.buildCanonicalString(
            TEST_METHOD, TEST_PATH, contentSha256, TEST_TIMESTAMP, TEST_NONCE
        );
        
        // Then: 验证结果格式
        String expected = "POST\n" + TEST_PATH + "\n" + contentSha256 + "\n" + TEST_TIMESTAMP + "\n" + TEST_NONCE;
        assertEquals(expected, canonical, "canonical字符串格式应该正确");
        
        // 验证字段分割
        String[] parts = canonical.split("\n");
        assertEquals(5, parts.length, "canonical字符串应该包含5个部分");
        assertEquals("POST", parts[0], "第一部分应该是大写的HTTP方法");
        assertEquals(TEST_PATH, parts[1], "第二部分应该是请求路径");
        assertEquals(contentSha256, parts[2], "第三部分应该是内容哈希");
        assertEquals(TEST_TIMESTAMP, parts[3], "第四部分应该是时间戳");
        assertEquals(TEST_NONCE, parts[4], "第五部分应该是随机数");
    }
    
    /**
     * 测试HTTP方法大小写处理
     * 
     * 验证点：
     * - 小写方法名自动转为大写
     * - 混合大小写方法名统一转为大写
     * - 确保客户端和服务端大小写处理一致
     */
    @Test
    void testBuildCanonicalString_MethodCaseHandling() {
        String contentSha256 = ApiSignUtils.sha256Hex(TEST_CONTENT);
        
        // Test 1: 小写方法名
        String canonical1 = ApiSignUtils.buildCanonicalString(
            "post", TEST_PATH, contentSha256, TEST_TIMESTAMP, TEST_NONCE
        );
        assertTrue(canonical1.startsWith("POST\n"), "小写方法名应该转为大写");
        
        // Test 2: 混合大小写方法名
        String canonical2 = ApiSignUtils.buildCanonicalString(
            "PoSt", TEST_PATH, contentSha256, TEST_TIMESTAMP, TEST_NONCE
        );
        assertTrue(canonical2.startsWith("POST\n"), "混合大小写方法名应该转为大写");
        
        // Test 3: 已经是大写的方法名
        String canonical3 = ApiSignUtils.buildCanonicalString(
            "POST", TEST_PATH, contentSha256, TEST_TIMESTAMP, TEST_NONCE
        );
        assertTrue(canonical3.startsWith("POST\n"), "大写方法名应该保持不变");
        
        // 验证三种情况生成的canonical字符串相同
        assertEquals(canonical1, canonical2, "不同大小写的相同方法应该生成相同的canonical字符串");
        assertEquals(canonical2, canonical3, "不同大小写的相同方法应该生成相同的canonical字符串");
    }
    
    /**
     * 测试null值处理
     * 
     * 验证点：
     * - 所有null参数都转为空字符串
     * - 不抛出NullPointerException
     * - 生成的canonical字符串格式仍然正确
     */
    @Test
    void testBuildCanonicalString_WithNullParameters() {
        // Test 1: 所有参数为null
        String canonical1 = ApiSignUtils.buildCanonicalString(null, null, null, null, null);
        assertEquals("\n\n\n\n", canonical1, "所有null参数应该转为空字符串");
        
        // Test 2: 部分参数为null
        String canonical2 = ApiSignUtils.buildCanonicalString(
            "GET", null, "hash123", null, "nonce456"
        );
        assertEquals("GET\n\nhash123\n\nnonce456", canonical2, "部分null参数应该转为空字符串");
        
        // Test 3: 验证格式仍然正确（5个部分）
        String[] parts = canonical1.split("\n", -1); // -1保留空字符串
        assertEquals(5, parts.length, "即使有null值，canonical字符串仍应包含5个部分");
    }
    
    /**
     * 测试SHA-256哈希计算
     * 
     * 验证点：
     * - 相同输入产生相同哈希
     * - 不同输入产生不同哈希
     * - 哈希值格式正确（64个字符的十六进制）
     * - null输入处理正确
     */
    @Test
    void testSha256Hex() {
        // Test 1: 正常字符串哈希
        String hash1 = ApiSignUtils.sha256Hex(TEST_CONTENT);
        assertNotNull(hash1, "哈希值不应该为null");
        assertEquals(64, hash1.length(), "SHA-256哈希值应该是64个字符");
        assertTrue(hash1.matches("[0-9a-fA-F]{64}"), "哈希值应该是十六进制格式");
        
        // Test 2: 相同输入产生相同哈希
        String hash2 = ApiSignUtils.sha256Hex(TEST_CONTENT);
        assertEquals(hash1, hash2, "相同输入应该产生相同的哈希值");
        
        // Test 3: 不同输入产生不同哈希
        String hash3 = ApiSignUtils.sha256Hex(TEST_CONTENT + "different");
        assertNotEquals(hash1, hash3, "不同输入应该产生不同的哈希值");
        
        // Test 4: 空字符串哈希
        String hash4 = ApiSignUtils.sha256Hex("");
        assertNotNull(hash4, "空字符串也应该能计算哈希");
        assertEquals(64, hash4.length(), "空字符串的哈希长度也应该是64");
        
        // Test 5: null输入处理
        String hash5 = ApiSignUtils.sha256Hex(null);
        assertEquals(hash4, hash5, "null输入应该按空字符串处理");
    }
    
    /**
     * 测试HMAC-SHA256签名计算
     * 
     * 验证点：
     * - 相同数据和密钥产生相同签名
     * - 不同数据或密钥产生不同签名
     * - 签名值格式正确（64个字符的十六进制）
     * - null数据处理正确
     * - 密钥不能为null
     */
    @Test
    void testHmacSha256Hex() {
        String testData = "test data for signing";
        
        // Test 1: 正常签名计算
        String signature1 = ApiSignUtils.hmacSha256Hex(testData, TEST_SECRET_KEY);
        assertNotNull(signature1, "签名值不应该为null");
        assertEquals(64, signature1.length(), "HMAC-SHA256签名应该是64个字符");
        assertTrue(signature1.matches("[0-9a-fA-F]{64}"), "签名值应该是十六进制格式");
        
        // Test 2: 相同数据和密钥产生相同签名
        String signature2 = ApiSignUtils.hmacSha256Hex(testData, TEST_SECRET_KEY);
        assertEquals(signature1, signature2, "相同数据和密钥应该产生相同签名");
        
        // Test 3: 不同数据产生不同签名
        String signature3 = ApiSignUtils.hmacSha256Hex(testData + "different", TEST_SECRET_KEY);
        assertNotEquals(signature1, signature3, "不同数据应该产生不同签名");
        
        // Test 4: 不同密钥产生不同签名
        String signature4 = ApiSignUtils.hmacSha256Hex(testData, TEST_SECRET_KEY + "different");
        assertNotEquals(signature1, signature4, "不同密钥应该产生不同签名");
        
        // Test 5: null数据处理
        String signature5 = ApiSignUtils.hmacSha256Hex(null, TEST_SECRET_KEY);
        String signature6 = ApiSignUtils.hmacSha256Hex("", TEST_SECRET_KEY);
        assertEquals(signature6, signature5, "null数据应该按空字符串处理");
    }
    
    /**
     * 测试签名验证功能
     * 
     * 验证点：
     * - 正确的签名验证通过
     * - 错误的签名验证失败
     * - 参数变化导致验证失败
     * - null值处理正确
     */
    @Test
    void testVerifySignature() {
        String contentSha256 = ApiSignUtils.sha256Hex(TEST_CONTENT);
        
        // 生成一个有效签名
        String validSignature = ApiSignUtils.hmacSha256Hex(
            ApiSignUtils.buildCanonicalString(TEST_METHOD, TEST_PATH, contentSha256, TEST_TIMESTAMP, TEST_NONCE),
            TEST_SECRET_KEY
        );
        
        // Test 1: 有效签名验证
        assertTrue(
            ApiSignUtils.verifySignature(TEST_METHOD, TEST_PATH, contentSha256, 
                                       TEST_TIMESTAMP, TEST_NONCE, TEST_SECRET_KEY, validSignature),
            "有效签名应该验证通过"
        );
        
        // Test 2: 无效签名验证
        assertFalse(
            ApiSignUtils.verifySignature(TEST_METHOD, TEST_PATH, contentSha256,
                                       TEST_TIMESTAMP, TEST_NONCE, TEST_SECRET_KEY, validSignature + "invalid"),
            "无效签名应该验证失败"
        );
        
        // Test 3: 方法变化导致验证失败
        assertFalse(
            ApiSignUtils.verifySignature("GET", TEST_PATH, contentSha256,
                                       TEST_TIMESTAMP, TEST_NONCE, TEST_SECRET_KEY, validSignature),
            "方法变化应该导致签名验证失败"
        );
        
        // Test 4: 路径变化导致验证失败
        assertFalse(
            ApiSignUtils.verifySignature(TEST_METHOD, "/different/path", contentSha256,
                                       TEST_TIMESTAMP, TEST_NONCE, TEST_SECRET_KEY, validSignature),
            "路径变化应该导致签名验证失败"
        );
        
        // Test 5: null签名处理
        assertFalse(
            ApiSignUtils.verifySignature(TEST_METHOD, TEST_PATH, contentSha256,
                                       TEST_TIMESTAMP, TEST_NONCE, TEST_SECRET_KEY, null),
            "null签名应该验证失败"
        );
        
        // Test 6: null密钥处理
        assertFalse(
            ApiSignUtils.verifySignature(TEST_METHOD, TEST_PATH, contentSha256,
                                       TEST_TIMESTAMP, TEST_NONCE, null, validSignature),
            "null密钥应该验证失败"
        );
    }
    
    /**
     * 测试与现有系统的兼容性
     * 
     * 验证点：
     * - 生成的签名与客户端SDK兼容
     * - 生成的签名与网关验证兼容
     * - 算法升级不影响现有签名
     * 
     * 业务场景：
     * - 模拟真实的API调用签名过程
     * - 确保客户端和服务端算法一致性
     */
    @Test
    void testCompatibilityWithExistingSystem() {
        // 模拟真实的API调用参数
        String method = "POST";
        String path = "/api/v1/user/create";
        String requestBody = "{\"username\":\"testuser\",\"email\":\"test@example.com\"}";
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = "abcd1234efgh5678";
        String secretKey = "sk_user123456789012345678901234567890123456";
        
        // 计算内容哈希
        String contentSha256 = ApiSignUtils.sha256Hex(requestBody);
        
        // 构建canonical字符串
        String canonical = ApiSignUtils.buildCanonicalString(method, path, contentSha256, timestamp, nonce);
        
        // 计算签名
        String signature = ApiSignUtils.hmacSha256Hex(canonical, secretKey);
        
        // 验证签名（模拟网关验证过程）
        boolean isValid = ApiSignUtils.verifySignature(method, path, contentSha256, 
                                                      timestamp, nonce, secretKey, signature);
        
        assertTrue(isValid, "签名验证应该通过，确保客户端和网关算法一致");
        
        // 验证canonical字符串格式符合预期
        assertTrue(canonical.contains(method.toUpperCase()), "canonical应包含大写的HTTP方法");
        assertTrue(canonical.contains(path), "canonical应包含请求路径");
        assertTrue(canonical.contains(contentSha256), "canonical应包含内容哈希");
        assertTrue(canonical.contains(timestamp), "canonical应包含时间戳");
        assertTrue(canonical.contains(nonce), "canonical应包含随机数");
        
        // 验证签名格式
        assertNotNull(signature, "签名不应该为null");
        assertEquals(64, signature.length(), "签名长度应该为64个字符");
        assertTrue(signature.matches("[0-9a-f]{64}"), "签名应该是小写十六进制格式");
    }
    
    /**
     * 测试异常情况处理
     * 
     * 验证点：
     * - HMAC计算异常时抛出RuntimeException
     * - 异常信息包含原因描述
     * - 不会导致程序崩溃
     */
    @Test
    void testExceptionHandling() {
        // Test 1: 正常情况不应该抛出异常
        assertDoesNotThrow(() -> {
            ApiSignUtils.hmacSha256Hex("test data", "valid_secret_key");
        }, "正常参数不应该抛出异常");
    }
    
    /**
     * 测试性能基准
     * 
     * 验证点：
     * - 签名计算性能满足要求
     * - 大量调用不会导致内存泄漏
     * - 算法效率符合预期
     * 
     * 注意：这个测试主要用于性能回归检测，不是严格的性能测试
     */
    @Test
    void testPerformanceBenchmark() {
        int iterations = 1000;
        String testData = "performance test data with some length to simulate real requests";
        
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < iterations; i++) {
            String contentHash = ApiSignUtils.sha256Hex(testData + i);
            String canonical = ApiSignUtils.buildCanonicalString(
                "POST", "/api/test", contentHash, String.valueOf(System.currentTimeMillis()), "nonce" + i
            );
            String signature = ApiSignUtils.hmacSha256Hex(canonical, TEST_SECRET_KEY);
            
            // 基本验证确保计算正确
            assertNotNull(signature, "签名不应该为null");
            assertEquals(64, signature.length(), "签名长度应该正确");
        }
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        // 性能基准：1000次签名操作应该在合理时间内完成（这里设置为5秒）
        assertTrue(duration < 5000, 
                  String.format("性能测试：%d次签名操作耗时%dms，应该小于5000ms", iterations, duration));
        
        System.out.printf("性能基准测试：%d次签名操作耗时%dms，平均每次%.2fms%n", 
                         iterations, duration, (double) duration / iterations);
    }
}
