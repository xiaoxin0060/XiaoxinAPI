-- =====================================================
-- 网关过滤器链条测试数据脚本
-- 功能：为网关测试提供Mock接口的数据库配置
-- 使用：mysql -u root -p xiaoxinapi < gateway_test_data.sql
-- =====================================================

USE `xiaoxinapi`;

-- =====================================================
-- 测试用户数据
-- =====================================================

-- 使用已存在的用户数据（用户ID: 3，已重置密钥对）
-- 用户信息：
-- - ID: 3
-- - 用户名: xiaoxin  
-- - 角色: admin
-- - AccessKey: ak_GZUxlpN8ZtHWGDqaqtao4jBU
-- - SecretKey: sk_eCclbpWXVc5bJuxiflTzzmsgEBXwq5gAX14Y93Lx (明文)
-- 注意：用户密钥对已重置，测试类已更新为新的密钥值

-- =====================================================
-- Mock接口配置数据
-- =====================================================

-- 先删除已存在的测试接口配置
DELETE FROM `interface_info` WHERE `url` IN ('/api/test/get', '/api/test/post');
DELETE FROM `user_interface_info` WHERE `userId` = 3 AND `interfaceInfoId` IN (101, 102);

-- 插入Mock GET测试接口
INSERT INTO `interface_info` (
    `id`, `name`, `description`, `url`, `providerUrl`, `method`, `userId`, `providerUserId`,
    `category`, `tags`, `version`, `requestSchema`, `responseSchema`, 
    `authType`, `authConfig`, `timeout`, `retryCount`, `rateLimit`, `price`, 
    `approvalStatus`, `status`, `documentation`, `exampleRequest`, `exampleResponse`,
    `createTime`, `updateTime`, `isDelete`
) VALUES (
    101, 'Mock GET测试接口', '用于网关过滤器链条测试的GET接口', 
    '/api/test/get', 'http://localhost:8888/api/test/get', 'GET',
    3, 3, '测试工具', '网关测试,GET请求', '1.0.0',
    
    -- 请求参数Schema
    '{"type":"object","properties":{"name":{"type":"string","description":"用户名称"},"userId":{"type":"string","description":"用户ID"}}}',
    
    -- 响应参数Schema
    '{"type":"object","properties":{"method":{"type":"string"},"message":{"type":"string"},"clientIP":{"type":"string"},"timestamp":{"type":"string"}}}',
    
    'NONE', '{}', 10000, 2, 100, 0.0001, 'APPROVED', 1,
    
    -- 接口文档
    '# Mock GET测试接口\n\n用于测试网关过滤器链条的GET请求处理。\n\n## 请求参数\n- name: 用户名称（可选）\n- userId: 用户ID（可选）\n\n## 返回数据\n- method: 请求方法\n- message: 处理结果消息\n- clientIP: 客户端IP地址\n- timestamp: 处理时间戳',
    
    -- 请求示例
    'GET /api/test/get?name=testUser&userId=123',
    
    -- 响应示例
    '{"method":"GET","message":"GET请求处理成功","clientIP":"127.0.0.1","timestamp":"2024-01-15T10:30:45"}',
    
    NOW(), NOW(), 0
);

-- 插入Mock POST测试接口
INSERT INTO `interface_info` (
    `id`, `name`, `description`, `url`, `providerUrl`, `method`, `userId`, `providerUserId`,
    `category`, `tags`, `version`, `requestSchema`, `responseSchema`, 
    `authType`, `authConfig`, `timeout`, `retryCount`, `rateLimit`, `price`, 
    `approvalStatus`, `status`, `documentation`, `exampleRequest`, `exampleResponse`,
    `createTime`, `updateTime`, `isDelete`
) VALUES (
    102, 'Mock POST测试接口', '用于网关过滤器链条测试的POST接口',
    '/api/test/post', 'http://localhost:8888/api/test/post', 'POST',
    3, 3, '测试工具', '网关测试,POST请求', '1.0.0',
    
    -- 请求参数Schema  
    '{"type":"object","properties":{"name":{"type":"string","description":"用户名称"},"email":{"type":"string","description":"邮箱地址"},"age":{"type":"number","description":"年龄"}}}',
    
    -- 响应参数Schema
    '{"type":"object","properties":{"method":{"type":"string"},"message":{"type":"string"},"receivedData":{"type":"object"},"dataSize":{"type":"number"}}}',
    
    'NONE', '{}', 10000, 2, 100, 0.0001, 'APPROVED', 1,
    
    -- 接口文档
    '# Mock POST测试接口\n\n用于测试网关过滤器链条的POST请求处理。\n\n## 请求参数\n- name: 用户名称\n- email: 邮箱地址\n- age: 年龄\n\n## 返回数据\n- method: 请求方法\n- message: 处理结果消息\n- receivedData: 接收到的数据\n- dataSize: 数据字段数量',
    
    -- 请求示例
    'POST /api/test/post\nContent-Type: application/json\n\n{"name":"测试用户","email":"test@example.com","age":25}',
    
    -- 响应示例
    '{"method":"POST","message":"POST请求处理成功","receivedData":{"name":"测试用户","email":"test@example.com","age":25},"dataSize":3}',
    
    NOW(), NOW(), 0
);

-- =====================================================
-- 用户接口权限配置
-- =====================================================

-- 为测试用户分配Mock接口的调用权限（用户ID: 3）
INSERT INTO `user_interface_info` (
    `userId`, `interfaceInfoId`, `totalNum`, `leftNum`, `status`,
    `createTime`, `updateTime`, `isDelete`
) VALUES 
    (3, 101, 0, 1000, 1, NOW(), NOW(), 0),  -- GET接口权限
    (3, 102, 0, 1000, 1, NOW(), NOW(), 0);  -- POST接口权限

-- =====================================================
-- 数据验证查询
-- =====================================================

-- 验证使用的测试用户数据
SELECT '=== 测试用户验证 ===' as section;
SELECT `id`, `userName`, `userAccount`, `accessKey`, `userRole` 
FROM `user` WHERE `id` = 3;

SELECT '=== Mock接口验证 ===' as section;
SELECT `id`, `name`, `url`, `providerUrl`, `method`, `status`, `timeout`, `rateLimit`
FROM `interface_info` WHERE `id` IN (101, 102);

SELECT '=== 用户接口权限验证 ===' as section;
SELECT 
    u.`userName`,
    ii.`name` as interfaceName,
    ii.`url`,
    ii.`providerUrl`,
    ii.`method`,
    uii.`leftNum`,
    uii.`status` as permissionStatus
FROM `user` u
JOIN `user_interface_info` uii ON u.`id` = uii.`userId`
JOIN `interface_info` ii ON uii.`interfaceInfoId` = ii.`id`
WHERE u.`id` = 3 AND ii.`id` IN (101, 102)
ORDER BY ii.`id`;

-- =====================================================
-- 使用说明
-- =====================================================

SELECT '=== 网关测试数据配置完成 ===' as section;
SELECT '
测试配置说明：

1. 测试用户信息（使用已存在用户，密钥对已重置）：
   - 用户ID: 3
   - 用户名: xiaoxin
   - AccessKey: ak_GZUxlpN8ZtHWGDqaqtao4jBU
   - SecretKey: sk_eCclbpWXVc5bJuxiflTzzmsgEBXwq5gAX14Y93Lx
   - 权限: 管理员

2. Mock接口配置：
   - GET接口: /api/test/get → http://localhost:8080/test/get
   - POST接口: /api/test/post → http://localhost:8080/test/post
   - 超时时间: 10秒
   - 限流: 100次/分钟
   - 配额: 1000次

3. 测试前准备：
   - 启动xiaoxin-mock-service服务 (端口8080)
   - 启动xiaoxin-api-gateway服务 (端口9999)
   - 确保Redis服务已启动

4. 运行测试：
   - 执行 GatewayFilterChainSimpleTest 测试类
   - 验证过滤器链条完整执行
   - 检查Mock接口正确响应

5. 手动测试示例：
   GET http://localhost:9999/api/test/get?name=test&userId=123
   POST http://localhost:9999/api/test/post
   
   需要添加认证头部：
   - accessKey: ak_GZUxlpN8ZtHWGDqaqtao4jBU
   - timestamp: 当前时间戳
   - nonce: 16位随机字符串
   - sign: HMAC-SHA256签名（使用SecretKey: sk_eCclbpWXVc5bJuxiflTzzmsgEBXwq5gAX14Y93Lx）
' as instructions;

-- =====================================================
-- 清理脚本（可选）
-- =====================================================

/* 
-- 如需清理测试数据，执行以下SQL：

-- 删除测试接口配置
DELETE FROM `user_interface_info` WHERE `userId` = 3 AND `interfaceInfoId` IN (101, 102);
DELETE FROM `interface_info` WHERE `id` IN (101, 102);

-- 注意：不删除已存在的用户数据（用户ID: 3）

SELECT '测试数据清理完成' as result;
*/
