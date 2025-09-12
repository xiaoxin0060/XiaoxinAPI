
USE `xiaoxinapi`;


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


-- 为测试用户分配Mock接口的调用权限


