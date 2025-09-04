-- ==========================================================
-- 小新API开放平台 - 数据库初始化脚本
-- 功能：创建完整的数据库结构和测试数据
-- 使用方法：mysql -u root -p < xiaoxinapi/sql/init_database.sql
-- ==========================================================

-- 创建数据库
CREATE DATABASE IF NOT EXISTS `xiaoxinapi` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 使用数据库
USE `xiaoxinapi`;

-- ==========================================================
-- 表结构创建
-- ==========================================================

-- 用户表
CREATE TABLE IF NOT EXISTS `user` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `userName` varchar(256) DEFAULT NULL COMMENT '用户昵称',
    `userAccount` varchar(256) NOT NULL COMMENT '用户账号',
    `userAvatar` varchar(1024) DEFAULT NULL COMMENT '用户头像URL',
    `gender` tinyint DEFAULT NULL COMMENT '性别(0-女 1-男)',
    `userRole` varchar(256) NOT NULL DEFAULT 'user' COMMENT '用户角色：user/admin',
    `userPassword` varchar(512) NOT NULL COMMENT '用户密码',
    `accessKey` varchar(512) DEFAULT NULL COMMENT 'API访问密钥',
    `secretKey` varchar(512) DEFAULT NULL COMMENT 'API签名密钥',
    `createTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `isDelete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除(0-未删除 1-已删除)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uni_userAccount` (`userAccount`),
    KEY `idx_accessKey` (`accessKey`(255))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 接口信息表（支持动态代理架构）
CREATE TABLE IF NOT EXISTS `interface_info` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `name` varchar(256) NOT NULL COMMENT '接口名称',
    `description` varchar(512) DEFAULT NULL COMMENT '接口描述',
    `url` varchar(512) NOT NULL COMMENT '平台统一API路径（对外暴露）',
    `providerUrl` varchar(512) NOT NULL COMMENT '真实接口地址（内部使用，不对外暴露）',
    `requestParams` text COMMENT '请求参数说明',
    `requestHeader` text COMMENT '请求头说明',
    `responseHeader` text COMMENT '响应头说明',
    `status` int NOT NULL DEFAULT '0' COMMENT '接口状态（0-关闭 1-开启）',
    `method` varchar(256) NOT NULL COMMENT 'HTTP请求方法',
    `userId` bigint NOT NULL COMMENT '创建用户ID',
    `createTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `isDelete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除(0-未删除 1-已删除)',
    -- 动态代理扩展字段
    `category` varchar(128) DEFAULT NULL COMMENT '接口分类',
    `tags` varchar(512) DEFAULT NULL COMMENT '接口标签，逗号分隔',
    `version` varchar(32) DEFAULT '1.0.0' COMMENT '接口版本',
    `requestSchema` longtext COMMENT '请求参数JSON Schema',
    `responseSchema` longtext COMMENT '响应参数JSON Schema',
    `authType` varchar(32) DEFAULT 'NONE' COMMENT '认证类型：NONE/API_KEY/BASIC/BEARER',
    `authConfig` text COMMENT '认证配置JSON（存储访问真实接口的认证信息）',
    `timeout` int DEFAULT 30000 COMMENT '转发超时时间（毫秒）',
    `retryCount` int DEFAULT 3 COMMENT '失败重试次数',
    `rateLimit` int DEFAULT 1000 COMMENT '频率限制（次/分钟）',
    `price` decimal(10,4) DEFAULT 0.0000 COMMENT '调用单价（元/次）',
    `providerUserId` bigint DEFAULT NULL COMMENT '接口提供者用户ID',
    `approvalStatus` varchar(32) DEFAULT 'APPROVED' COMMENT '审核状态：PENDING/APPROVED/REJECTED',
    `documentation` longtext COMMENT '接口使用文档',
    `exampleRequest` text COMMENT '请求示例',
    `exampleResponse` text COMMENT '响应示例',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uni_url_method` (`url`, `method`),
    KEY `idx_userId` (`userId`),
    KEY `idx_providerUserId` (`providerUserId`),
    KEY `idx_category` (`category`),
    KEY `idx_approvalStatus` (`approvalStatus`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='接口信息表 - 支持网关代理架构';

-- 用户接口调用表
CREATE TABLE IF NOT EXISTS `user_interface_info` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `userId` bigint NOT NULL COMMENT '用户ID',
    `interfaceInfoId` bigint NOT NULL COMMENT '接口ID',
    `totalNum` int NOT NULL DEFAULT '0' COMMENT '总调用次数',
    `leftNum` int NOT NULL DEFAULT '0' COMMENT '剩余调用次数',
    `status` int NOT NULL DEFAULT '0' COMMENT '状态（0-禁用 1-启用）',
    `createTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `isDelete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除(0-未删除 1-已删除)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uni_user_interface` (`userId`, `interfaceInfoId`),
    KEY `idx_userId` (`userId`),
    KEY `idx_interfaceInfoId` (`interfaceInfoId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户接口调用关系表';

-- 帖子表（系统原有功能）
CREATE TABLE IF NOT EXISTS `post` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `title` varchar(512) DEFAULT NULL COMMENT '帖子标题',
    `content` text COMMENT '帖子内容',
    `tags` varchar(1024) DEFAULT NULL COMMENT '标签列表json',
    `thumbNum` int NOT NULL DEFAULT '0' COMMENT '点赞数',
    `favourNum` int NOT NULL DEFAULT '0' COMMENT '收藏数',
    `userId` bigint NOT NULL COMMENT '创建用户ID',
    `createTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `isDelete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除',
    PRIMARY KEY (`id`),
    KEY `idx_userId` (`userId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子表';

-- 网关路由配置表（可选，用于动态路由）
CREATE TABLE IF NOT EXISTS `gateway_route` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `routeId` varchar(128) NOT NULL COMMENT '路由ID',
    `uri` varchar(512) NOT NULL COMMENT '目标URI',
    `predicates` text NOT NULL COMMENT '断言配置JSON',
    `filters` text DEFAULT NULL COMMENT '过滤器配置JSON',
    `metadata` text DEFAULT NULL COMMENT '元数据JSON',
    `order` int DEFAULT 0 COMMENT '路由优先级',
    `status` int NOT NULL DEFAULT '1' COMMENT '状态（0-禁用 1-启用）',
    `createTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uni_routeId` (`routeId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='网关路由配置表';

-- ==========================================================
-- 测试数据插入
-- ==========================================================

-- 插入测试用户
INSERT IGNORE INTO `user` (
    `id`, `userName`, `userAccount`, `userAvatar`, `gender`, `userRole`, `userPassword`, 
    `accessKey`, `secretKey`, `createTime`, `updateTime`, `isDelete`
) VALUES (
    1, 'API测试用户', 'testuser', 'https://example.com/avatar.jpg', 0, 'user',
    -- 密码为: testpassword (实际项目中应使用BCrypt)
    'b109f3bbbc244eb82441917ed06d618b9008dd09b3befd1b5e07394c706a8bb980b1d7785e5976ec049b46df5f1326af5a2ea6d103fd07c95385ffab0cacbc86',
    'xiaoxinAccessKey', 'xiaoxinSecretKey', NOW(), NOW(), 0
);

-- 插入管理员用户
INSERT IGNORE INTO `user` (
    `id`, `userName`, `userAccount`, `userAvatar`, `gender`, `userRole`, `userPassword`, 
    `accessKey`, `secretKey`, `createTime`, `updateTime`, `isDelete`
) VALUES (
    2, '系统管理员', 'admin', 'https://example.com/admin.jpg', 1, 'admin',
    -- 密码为: admin123 (实际项目中应使用BCrypt)
    'ac9689e2272427085e35b9d3e3e8bed88cb3434828b43b86fc0596cad4c6e270', 
    'adminAccessKey', 'adminSecretKey', NOW(), NOW(), 0
);

-- 插入测试接口数据（可用的HTTP API接口）
DELETE FROM `interface_info` WHERE `url` IN ('/api/geo/query', '/api/sample/random', '/api/data/test');

INSERT INTO `interface_info` (
    `name`, `description`, `url`, `providerUrl`, `method`, `userId`, `providerUserId`,
    `category`, `tags`, `version`, `requestSchema`, `responseSchema`, 
    `authType`, `authConfig`, `timeout`, `retryCount`, `rateLimit`, `price`, 
    `approvalStatus`, `documentation`, `exampleRequest`, `exampleResponse`, `status`
) VALUES 
-- IP地址查询接口
('IP地址查询', '查询当前IP地址的地理位置信息', '/api/geo/query', 'http://ip-api.com/json', 'GET', 
 1, 1, '数据服务', '地理位置,IP查询', '1.0.0',
 '{"type":"object","properties":{"ip":{"type":"string","description":"可选，要查询的IP地址"}}}',
 '{"type":"object","properties":{"query":{"type":"string"},"country":{"type":"string"},"city":{"type":"string"}}}',
 'NONE', '{}', 10000, 2, 1000, 0.0001, 'APPROVED',
 '# IP地址查询接口\n\n查询指定IP地址或当前IP的地理位置信息。\n\n## 请求参数\n- ip: 可选，要查询的IP地址，不传则查询当前IP\n\n## 返回字段\n- query: 查询的IP地址\n- country: 国家\n- city: 城市\n- lat/lon: 经纬度',
 '{}', 
 '{"query":"127.0.0.1","country":"Local","city":"Unknown"}', 1),

-- HTTPBin JSON测试接口
('HTTPBin JSON测试', '获取结构化的JSON测试数据', '/api/sample/random', 'http://httpbin.org/json', 'GET',
 1, 1, '测试工具', '测试数据,JSON', '1.0.0',
 '{"type":"object","properties":{}}',
 '{"type":"object","properties":{"slideshow":{"type":"object"}}}',
 'NONE', '{}', 8000, 1, 1000, 0.0001, 'APPROVED',
 '# HTTPBin JSON测试接口\n\n返回标准的JSON测试数据，用于接口测试和调试。',
 '{}',
 '{"slideshow":{"title":"Sample Slide Show"}}', 1),

-- HTTPBin UUID生成器
('HTTPBin UUID生成器', '生成随机UUID标识符', '/api/data/test', 'http://httpbin.org/uuid', 'GET',
 1, 1, '测试工具', '测试数据,UUID', '1.0.0',
 '{"type":"object","properties":{}}',
 '{"type":"object","properties":{"uuid":{"type":"string"}}}',
 'NONE', '{}', 8000, 1, 1000, 0.0001, 'APPROVED',
 '# HTTPBin UUID生成器\n\n生成随机的UUID标识符，用于测试和调试。',
 '{}',
 '{"uuid":"550e8400-e29b-41d4-a716-446655440000"}', 1);

-- 为测试用户分配接口调用权限
INSERT IGNORE INTO `user_interface_info` (`userId`, `interfaceInfoId`, `totalNum`, `leftNum`, `status`)
SELECT 1, `id`, 0, 1000, 1 
FROM `interface_info` 
WHERE `url` IN ('/api/geo/query', '/api/sample/random', '/api/data/test');

-- 为管理员分配所有接口权限
INSERT IGNORE INTO `user_interface_info` (`userId`, `interfaceInfoId`, `totalNum`, `leftNum`, `status`)
SELECT 2, `id`, 0, 9999, 1 
FROM `interface_info`;

-- ==========================================================
-- 数据验证查询
-- ==========================================================

-- 验证用户数据
SELECT '=== 用户信息验证 ===' as section;
SELECT `id`, `userName`, `userAccount`, `accessKey`, `userRole`, `createTime` 
FROM `user` WHERE `accessKey` IN ('xiaoxinAccessKey', 'adminAccessKey');

-- 验证接口数据
SELECT '=== 接口信息验证 ===' as section;
SELECT `id`, `name`, `url`, `providerUrl`, `method`, `authType`, `status`, `timeout` 
FROM `interface_info` 
WHERE `url` IN ('/api/geo/query', '/api/sample/random', '/api/data/test')
ORDER BY `id`;

-- 验证用户权限
SELECT '=== 用户接口权限验证 ===' as section;
SELECT 
    u.`userName`,
    ii.`name` as interfaceName,
    ii.`url`,
    ii.`providerUrl`,
    uii.`leftNum`,
    uii.`status` as permissionStatus
FROM `user` u
JOIN `user_interface_info` uii ON u.`id` = uii.`userId`
JOIN `interface_info` ii ON uii.`interfaceInfoId` = ii.`id`
WHERE u.`accessKey` = 'xiaoxinAccessKey'
ORDER BY ii.`id`;

SELECT '=== 数据库初始化完成 ===' as section;
SELECT 'API开放平台数据库初始化成功！可以启动应用进行测试。' as message;

-- ==========================================================
-- 使用说明
-- ==========================================================

/*
数据库初始化完成后，您可以：

1. 启动应用服务：
   - xiaoxinapi (主服务): http://localhost:8101
   - xiaoxinapi-gateway (网关): http://localhost:9999

2. 使用测试账号：
   - 测试用户: testuser / testpassword
   - 管理员: admin / admin123
   - API测试密钥: xiaoxinAccessKey / xiaoxinSecretKey

3. 测试接口：
   - GET http://localhost:9999/api/geo/query (IP查询)
   - GET http://localhost:9999/api/sample/random (JSON测试)
   - GET http://localhost:9999/api/data/test (数据测试)

4. 管理后台：
   - http://localhost:8101 (Swagger文档)
   - 接口管理、用户管理、调用统计等功能

注意：请确保MySQL、Redis、Nacos等依赖服务已启动。
*/
