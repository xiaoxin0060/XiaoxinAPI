-- ==========================================================
-- 🎯 小新API开放平台 - 完整数据库初始化脚本 
-- 设计目标：Docker一键部署 + 完整测试环境 + 面试演示
-- 技术栈：MySQL 8.2 + MyBatis Plus + Spring Cloud Gateway
-- 包含：表结构 + 基础数据 + 网关测试数据 + 用户权限配置
-- ==========================================================

-- 🎯 强制设置客户端字符集 - 解决中文乱码
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET character_set_client = utf8mb4;
SET character_set_connection = utf8mb4;
SET character_set_results = utf8mb4;

-- 🎯 确保root用户可以从任何地方连接 - 解决Docker连接问题
ALTER USER 'root'@'localhost' IDENTIFIED WITH mysql_native_password BY 'root';
CREATE USER IF NOT EXISTS 'root'@'%' IDENTIFIED WITH mysql_native_password BY 'root';
GRANT ALL PRIVILEGES ON *.* TO 'root'@'%' WITH GRANT OPTION;
FLUSH PRIVILEGES;

-- 创建主业务数据库
CREATE DATABASE IF NOT EXISTS `xiaoxinapi` 
DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 🎯 创建Nacos配置中心数据库 - 微服务架构核心组件
CREATE DATABASE IF NOT EXISTS `nacos_config` 
DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 使用数据库
USE `xiaoxinapi`;

-- ==========================================================
-- 📊 表结构创建 - 面试亮点：规范化设计 + 索引优化
-- ==========================================================

-- 🧑 用户表 - 支持BCrypt密码加密 + API密钥管理
CREATE TABLE IF NOT EXISTS `user` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `userName` varchar(256) DEFAULT NULL COMMENT '用户昵称',
    `userAccount` varchar(256) NOT NULL COMMENT '用户账号',
    `userAvatar` varchar(1024) DEFAULT NULL COMMENT '用户头像URL',
    `gender` tinyint DEFAULT NULL COMMENT '性别(0-女 1-男)',
    `userRole` varchar(256) NOT NULL DEFAULT 'user' COMMENT '用户角色：user/admin',
    `userPassword` varchar(512) NOT NULL COMMENT '用户密码(支持BCrypt+MD5兼容)',
    `accessKey` varchar(512) DEFAULT NULL COMMENT 'API访问密钥(AK)',
    `secretKey` varchar(512) DEFAULT NULL COMMENT 'API签名密钥(SK,支持AES-GCM加密)',
    `createTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `isDelete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除(0-未删除 1-已删除)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uni_userAccount` (`userAccount`),
    KEY `idx_accessKey` (`accessKey`(255)),
    KEY `idx_userRole` (`userRole`),
    KEY `idx_createTime` (`createTime`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci 
COMMENT='用户表 - 支持API密钥管理和角色权限控制';

-- 🔗 接口信息表 - 支持网关动态代理架构
CREATE TABLE IF NOT EXISTS `interface_info` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `name` varchar(256) NOT NULL COMMENT '接口名称',
    `description` varchar(512) DEFAULT NULL COMMENT '接口描述',
    `url` varchar(512) NOT NULL COMMENT '平台统一API路径(对外暴露)',
    `providerUrl` varchar(512) NOT NULL COMMENT '真实接口地址(内部使用)',
    `requestParams` text COMMENT '请求参数说明',
    `requestHeader` text COMMENT '请求头说明', 
    `responseHeader` text COMMENT '响应头说明',
    `status` int NOT NULL DEFAULT '0' COMMENT '接口状态(0-关闭 1-开启)',
    `method` varchar(256) NOT NULL COMMENT 'HTTP请求方法',
    `userId` bigint NOT NULL COMMENT '创建用户ID',
    `createTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `isDelete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除(0-未删除 1-已删除)',
    
    -- 🎯 面试亮点：动态代理扩展字段 - 企业级API网关特性
    `category` varchar(128) DEFAULT NULL COMMENT '接口分类(数据服务/支付服务/AI服务等)',
    `tags` varchar(512) DEFAULT NULL COMMENT '接口标签，逗号分隔',
    `version` varchar(32) DEFAULT '1.0.0' COMMENT '接口版本(支持版本管理)',
    `requestSchema` longtext COMMENT '请求参数JSON Schema(接口文档生成)',
    `responseSchema` longtext COMMENT '响应参数JSON Schema(接口文档生成)',
    `authType` varchar(32) DEFAULT 'NONE' COMMENT '认证类型：NONE/API_KEY/BASIC/BEARER',
    `authConfig` text COMMENT '认证配置JSON(存储访问真实接口的认证信息)',
    `timeout` int DEFAULT 30000 COMMENT '转发超时时间(毫秒)',
    `retryCount` int DEFAULT 3 COMMENT '失败重试次数',
    `rateLimit` int DEFAULT 1000 COMMENT '频率限制(次/分钟)',
    `price` decimal(10,4) DEFAULT 0.0000 COMMENT '调用单价(元/次)',
    `providerUserId` bigint DEFAULT NULL COMMENT '接口提供者用户ID',
    `approvalStatus` varchar(32) DEFAULT 'APPROVED' COMMENT '审核状态：PENDING/APPROVED/REJECTED',
    `documentation` longtext COMMENT '接口使用文档(Markdown格式)',
    `exampleRequest` text COMMENT '请求示例',
    `exampleResponse` text COMMENT '响应示例',
    
    PRIMARY KEY (`id`),
    UNIQUE KEY `uni_url_method` (`url`, `method`),
    KEY `idx_userId` (`userId`),
    KEY `idx_providerUserId` (`providerUserId`),
    KEY `idx_category` (`category`),
    KEY `idx_approvalStatus` (`approvalStatus`),
    KEY `idx_status` (`status`),
    KEY `idx_createTime` (`createTime`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci 
COMMENT='接口信息表 - 支持网关代理架构和企业级管理';

-- 📈 用户接口调用关系表 - 配额管理 + 统计分析
CREATE TABLE IF NOT EXISTS `user_interface_info` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `userId` bigint NOT NULL COMMENT '用户ID',
    `interfaceInfoId` bigint NOT NULL COMMENT '接口ID',
    `totalNum` int NOT NULL DEFAULT '0' COMMENT '总调用次数',
    `leftNum` int NOT NULL DEFAULT '0' COMMENT '剩余调用次数',
    `status` int NOT NULL DEFAULT '0' COMMENT '状态(0-正常 1-禁用)',
    `createTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `isDelete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除(0-未删除 1-已删除)',
    
    PRIMARY KEY (`id`),
    UNIQUE KEY `uni_user_interface` (`userId`, `interfaceInfoId`),
    KEY `idx_userId` (`userId`),
    KEY `idx_interfaceInfoId` (`interfaceInfoId`),
    KEY `idx_status` (`status`),
    KEY `idx_updateTime` (`updateTime`),
    
    -- 🎯 面试亮点：外键约束确保数据一致性
    CONSTRAINT `fk_user_interface_userId` FOREIGN KEY (`userId`) REFERENCES `user` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_user_interface_interfaceId` FOREIGN KEY (`interfaceInfoId`) REFERENCES `interface_info` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci 
COMMENT='用户接口调用关系表 - 配额管理和统计分析';

-- 💕 约会交友帖子表 - 根据实体类实际字段定义
CREATE TABLE IF NOT EXISTS `post` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `age` int DEFAULT NULL COMMENT '年龄',
    `gender` int DEFAULT NULL COMMENT '性别(0-男 1-女)',
    `education` varchar(512) DEFAULT NULL COMMENT '学历',
    `place` varchar(512) DEFAULT NULL COMMENT '地点',
    `job` varchar(512) DEFAULT NULL COMMENT '职业',
    `contact` varchar(512) DEFAULT NULL COMMENT '联系方式',
    `loveExp` text COMMENT '感情经历',
    `content` text COMMENT '内容(个人介绍)',
    `photo` varchar(1024) DEFAULT NULL COMMENT '照片地址',
    `reviewStatus` int NOT NULL DEFAULT '0' COMMENT '状态(0-待审核 1-通过 2-拒绝)',
    `reviewMessage` varchar(512) DEFAULT NULL COMMENT '审核信息',
    `viewNum` int NOT NULL DEFAULT '0' COMMENT '浏览数',
    `thumbNum` int NOT NULL DEFAULT '0' COMMENT '点赞数',
    `userId` bigint NOT NULL COMMENT '创建用户ID',
    `createTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `isDelete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除',
    
    PRIMARY KEY (`id`),
    KEY `idx_userId` (`userId`),
    KEY `idx_reviewStatus` (`reviewStatus`),
    KEY `idx_gender` (`gender`),
    KEY `idx_place` (`place`(255)),
    KEY `idx_createTime` (`createTime`),
    CONSTRAINT `fk_post_userId` FOREIGN KEY (`userId`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci 
COMMENT='约会交友帖子表 - 与实体类字段完全匹配';

-- ==========================================================
-- 🎯 完整测试数据插入 - Docker部署 + 面试演示
-- ==========================================================

-- 🧑‍💼 插入测试用户（密码：MD5+salt兼容格式，首次登录自动升级BCrypt）
INSERT INTO `user` (
    `id`, `userName`, `userAccount`, `userRole`, `userPassword`, 
    `accessKey`, `secretKey`, `createTime`, `updateTime`, `isDelete`
) VALUES 
-- 管理员账户 (admin/admin123)
(1, '系统管理员', 'admin', 'admin', 
 MD5(CONCAT('xiaoxin', 'admin123')), 
 'ak_admin_20240315098765432', 'sk_admin_87654321098765432109876543210987654321',
 NOW(), NOW(), 0),

-- 开发者账户 (xiaoxin/12345678) 
(2, '小新开发者', 'xiaoxin', 'user',
 MD5(CONCAT('xiaoxin', '12345678')),
 'ak_xiaoxin_20240315123456789', 'sk_xiaoxin_12345678901234567890123456789012345678',
 NOW(), NOW(), 0),

-- 测试用户账户 (testuser/12345678)
(3, '测试用户', 'testuser', 'user',
 MD5(CONCAT('xiaoxin', '12345678')),
 'ak_test_20240315567890123', 'sk_test_56789012345678901234567890123456789012',
 NOW(), NOW(), 0);

-- ==========================================================
-- 🔗 生产可用的真实接口数据 + 网关测试接口
-- ==========================================================

INSERT INTO `interface_info` (
    `id`, `name`, `description`, `url`, `providerUrl`, `method`, `userId`, `providerUserId`,
    `category`, `tags`, `version`, `requestSchema`, `responseSchema`, 
    `authType`, `authConfig`, `timeout`, `retryCount`, `rateLimit`, `price`, 
    `approvalStatus`, `documentation`, `exampleRequest`, `exampleResponse`, `status`
) VALUES 

-- 🌐 第三方真实接口 - 生产环境可用
(1, 'IP地址查询API', '查询IP地址的地理位置信息，支持IPv4/IPv6', 
 '/api/geo/query', 'http://ip-api.com/json', 'GET', 
 1, 1, '地理位置服务', 'IP查询,地理位置,网络服务', '1.0.0',
 '{"type":"object","properties":{"ip":{"type":"string","description":"可选，要查询的IP地址，不传则查询当前IP"}}}',
 '{"type":"object","properties":{"query":{"type":"string","description":"查询的IP"},"country":{"type":"string","description":"国家"},"city":{"type":"string","description":"城市"},"lat":{"type":"number","description":"纬度"},"lon":{"type":"number","description":"经度"}}}',
 'NONE', '{}', 10000, 2, 1000, 0.0010, 'APPROVED',
 '# IP地址查询API\n\n## 功能描述\n查询指定IP地址或当前IP的详细地理位置信息。\n\n## 请求参数\n- ip: 可选，要查询的IP地址，不传则查询当前IP\n\n## 返回字段\n- query: 查询的IP地址\n- country: 国家名称\n- city: 城市名称\n- lat/lon: 经纬度坐标\n\n## 应用场景\n- 用户地域分析\n- 内容个性化推荐\n- 安全风控检测',
 'GET /api/geo/query?ip=8.8.8.8', 
 '{"query":"8.8.8.8","country":"United States","city":"Mountain View","lat":37.4056,"lon":-122.0775}', 1),

(2, 'HTTPBin JSON数据', '获取结构化JSON测试数据，用于接口调试', 
 '/api/sample/json', 'http://httpbin.org/json', 'GET',
 1, 1, '开发工具', '测试数据,JSON,调试工具', '1.0.0',
 '{"type":"object","properties":{}}',
 '{"type":"object","properties":{"slideshow":{"type":"object","description":"测试数据结构"}}}',
 'NONE', '{}', 8000, 1, 2000, 0.0005, 'APPROVED',
 '# HTTPBin JSON数据API\n\n## 功能描述\n返回标准的JSON测试数据结构，适合用于接口测试和前端调试。\n\n## 特点\n- 无需参数\n- 返回固定结构的JSON\n- 响应速度快\n- 适合自动化测试\n\n## 应用场景\n- 前端开发调试\n- 自动化测试\n- API响应格式验证',
 'GET /api/sample/json',
 '{"slideshow":{"title":"Sample Slide Show","date":"date of publication","slides":[{"title":"Wake up to WonderWidgets!","type":"all"}]}}', 1),

(3, 'UUID生成器', '生成标准UUID标识符，支持全球唯一性', 
 '/api/tools/uuid', 'http://httpbin.org/uuid', 'GET',
 1, 1, '工具服务', 'UUID,标识符,工具', '1.0.0',
 '{"type":"object","properties":{}}',
 '{"type":"object","properties":{"uuid":{"type":"string","format":"uuid","description":"生成的UUID"}}}',
 'NONE', '{}', 5000, 1, 5000, 0.0001, 'APPROVED',
 '# UUID生成器API\n\n## 功能描述\n生成符合RFC 4122标准的UUID（通用唯一标识符）。\n\n## 特点\n- 全球唯一性保证\n- 符合国际标准\n- 高性能生成\n- 无状态服务\n\n## 应用场景\n- 数据库主键生成\n- 分布式系统标识\n- 临时文件命名\n- 会话标识符',
 'GET /api/tools/uuid',
 '{"uuid":"550e8400-e29b-41d4-a716-446655440000"}', 1),

-- 🌉 网关测试专用Mock接口 - Docker环境测试
(101, 'Mock GET测试接口', '验证网关过滤器链条的GET请求处理能力', 
 '/api/test/get', 'http://xiaoxin-mock:8081/api/test/get', 'GET',
 3, 3, '测试工具', '网关测试,过滤器链,GET请求', '1.0.0',
 
 '{"type":"object","properties":{"name":{"type":"string","description":"测试用户名称","example":"xiaoxin"},"userId":{"type":"string","description":"用户ID","example":"123"},"category":{"type":"string","description":"测试分类","example":"api"}}}',
 
 '{"type":"object","properties":{"method":{"type":"string","description":"请求方法"},"message":{"type":"string","description":"处理结果消息"},"clientIP":{"type":"string","description":"客户端IP地址"},"timestamp":{"type":"string","description":"处理时间戳"},"params":{"type":"object","description":"接收到的参数"},"headers":{"type":"object","description":"接收到的请求头"}}}',
 
 'NONE', '{}', 10000, 2, 100, 0.0001, 'APPROVED',
 
 '# Mock GET测试接口\n\n## 🎯 功能说明\n验证小新API网关的GET请求处理能力，测试完整的过滤器执行链条。\n\n## 📊 测试覆盖\n- ✅ 日志记录过滤器\n- ✅ IP白名单安全过滤器\n- ✅ 用户认证过滤器（签名验证）\n- ✅ 接口验证过滤器\n- ✅ 限流过滤器\n- ✅ 配额过滤器\n- ✅ 代理转发过滤器\n- ✅ 响应处理过滤器\n\n## 📝 请求参数\n- `name`: 测试用户名称（可选）\n- `userId`: 用户ID（可选）\n- `category`: 测试分类（可选）\n\n## 🧪 测试场景\n1. 正常调用验证完整处理链路\n2. 参数传递测试查询参数转发\n3. 头部信息验证请求头传递',
 
 'GET /api/test/get?name=xiaoxin&userId=123&category=api\nAuthorization: AK-SK签名\nX-Request-ID: test-12345',
 
 '{"method":"GET","message":"网关GET请求处理成功","clientIP":"172.20.0.1","timestamp":"2024-03-15T14:30:45.123Z","params":{"name":"xiaoxin","userId":"123","category":"api"},"headers":{"host":"xiaoxin-gateway:8090"}}', 1),

(102, 'Mock POST测试接口', '验证网关过滤器链条的POST请求处理能力',
 '/api/test/post', 'http://xiaoxin-mock:8081/api/test/post', 'POST',
 3, 3, '测试工具', '网关测试,过滤器链,POST请求,JSON数据', '1.0.0',
 
 '{"type":"object","required":["name"],"properties":{"name":{"type":"string","description":"用户名称","example":"小新测试"},"email":{"type":"string","format":"email","description":"邮箱地址","example":"xiaoxin@test.com"},"age":{"type":"integer","minimum":1,"maximum":150,"description":"用户年龄","example":25},"skills":{"type":"array","items":{"type":"string"},"description":"技能列表","example":["Java","Spring Boot","Docker"]}}}',
 
 '{"type":"object","properties":{"method":{"type":"string","description":"请求方法"},"message":{"type":"string","description":"处理结果消息"},"receivedData":{"type":"object","description":"接收到的JSON数据"},"dataSize":{"type":"integer","description":"数据字段数量"},"contentType":{"type":"string","description":"请求内容类型"},"timestamp":{"type":"string","description":"处理时间戳"}}}',
 
 'NONE', '{}', 15000, 2, 50, 0.0002, 'APPROVED',
 
 '# Mock POST测试接口\n\n## 🎯 功能说明\n验证小新API网关的POST请求处理能力，测试JSON数据传输和过滤器执行链条。\n\n## 📊 测试覆盖\n- ✅ 请求体解析和转发\n- ✅ JSON数据验证\n- ✅ Content-Type处理\n- ✅ 签名验证（包含Body）\n- ✅ 完整的过滤器链条\n\n## 📝 请求参数\n- `name`: 用户名称（必填）\n- `email`: 邮箱地址（可选，邮件格式）\n- `age`: 用户年龄（可选，1-150）\n- `skills`: 技能列表（可选，字符串数组）\n\n## 🧪 测试场景\n1. 完整数据测试所有字段的传输\n2. 必填验证测试name字段必填校验\n3. JSON处理测试数组和对象嵌套',
 
 'POST /api/test/post\nContent-Type: application/json\nAuthorization: AK-SK签名\n\n{"name": "小新测试用户","email": "xiaoxin@example.com","age": 25,"skills": ["Java", "Spring Boot", "Docker"]}',
 
 '{"method":"POST","message":"网关POST请求处理成功","receivedData":{"name":"小新测试用户","email":"xiaoxin@example.com","age":25},"dataSize":3,"contentType":"application/json","timestamp":"2024-03-15T14:35:20.456Z"}', 1);

-- ==========================================================
-- 📊 用户接口调用权限分配 - 完整配额管理
-- ==========================================================

INSERT INTO `user_interface_info` (`userId`, `interfaceInfoId`, `totalNum`, `leftNum`, `status`) VALUES 

-- 🧑‍💼 管理员(ID=1)：无限制调用权限  
(1, 1, 999999, 999999, 0),   -- IP查询：无限制
(1, 2, 999999, 999999, 0),   -- JSON数据：无限制
(1, 3, 999999, 999999, 0),   -- UUID生成：无限制
(1, 101, 999999, 999999, 0), -- Mock GET：无限制
(1, 102, 999999, 999999, 0), -- Mock POST：无限制

-- 👨‍💻 开发者xiaoxin(ID=2)：标准开发配额
(2, 1, 1000, 1000, 0),       -- IP查询：1000次
(2, 2, 2000, 2000, 0),       -- JSON数据：2000次
(2, 3, 5000, 5000, 0),       -- UUID生成：5000次
(2, 101, 1000, 1000, 0),     -- Mock GET：1000次
(2, 102, 500, 500, 0),       -- Mock POST：500次

-- 🧪 测试用户testuser(ID=3)：基础测试配额
(3, 1, 100, 100, 0),         -- IP查询：100次
(3, 2, 200, 200, 0),         -- JSON数据：200次  
(3, 3, 500, 500, 0),         -- UUID生成：500次
(3, 101, 100, 100, 0),       -- Mock GET：100次
(3, 102, 50, 50, 0);         -- Mock POST：50次

-- ==========================================================
-- 🎯 Docker部署 & 面试展示说明
-- ==========================================================
-- 
-- 🐳 Docker部署特点：
-- 1. 一键初始化：单SQL文件完成完整环境搭建
-- 2. 服务发现：xiaoxin-mock:8081 Docker内部网络通信
-- 3. 数据持久化：MySQL Volume确保数据不丢失
-- 4. 完整测试：包含真实接口 + Mock测试接口
-- 
-- 🎯 面试技术亮点：
-- 1. 🔐 密码安全：BCrypt+MD5兼容迁移，支持自动升级
-- 2. 🔑 API密钥：AK/SK体系，支持AES-GCM加密存储
-- 3. 🌉 代理架构：动态路由配置，支持多种认证方式
-- 4. 📊 配额管理：精细化权限控制，status字段支持临时禁用
-- 5. 🎯 数据完整性：外键约束，确保数据一致性
-- 6. 📈 索引优化：合理的索引设计，提升查询性能
-- 7. 🔄 兼容性：支持版本管理和向后兼容
-- 
-- 🧪 测试命令：
-- curl -X GET "http://localhost:8080/api/test/get?name=xiaoxin"
-- curl -X POST "http://localhost:8080/api/test/post" -H "Content-Type: application/json" -d '{"name":"test"}'
-- 
-- 👤 测试账号：
-- 管理员：admin/admin123 (无限制权限)
-- 开发者：xiaoxin/12345678 (标准权限)  
-- 测试员：testuser/12345678 (基础权限)
-- 
-- ==========================================================