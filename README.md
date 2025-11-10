# 高并发网关流量治理系统

> 基于 Spring Cloud Gateway + Dubbo 的企业级 API 网关，支持动态代理、分布式限流、熔断降级

---

## 项目介绍

### 项目定位
一个支持高并发的 API 网关系统，实现第三方接口的统一代理、安全认证、流量控制和配额管理。系统采用 WebFlux 响应式架构，通过 8 层过滤器链实现请求的安全验证、流量控制和动态转发。

### 核心功能
- **签名认证**：HMAC-SHA256 签名 + 防重放攻击 + 防时序攻击
- **分布式限流**：Redis 滑动窗口算法，支持多维度限流配置
- **配额管理**：预扣减机制，原子操作保障并发安全
- **服务熔断**：三态断路器，自动降级和恢复
- **微服务通信**：Dubbo RPC 解耦，异步化处理
- **密钥安全**：AES-GCM 加密存储，BCrypt 密码保护

### 技术栈
Spring Cloud Gateway · Dubbo · Redis · MyBatis-Plus · MySQL · Docker · WebFlux

---

## 功能特性

### 网关核心能力

| 功能模块 | 核心技术 | 应用场景 |
|---------|---------|---------|
| **签名认证** | HMAC-SHA256 + nonce + Redis | API 请求防篡改、防重放 |
| **分布式限流** | Redis Sorted Set 滑动窗口 | 保护后端服务，避免流量突刺 |
| **配额管理** | 预扣减 + 数据库行锁 | 接口调用次数控制，防配额穿透 |
| **服务熔断** | 三态断路器 + Redis 协调 | 下游服务异常时快速失败，避免雪崩 |
| **动态代理** | WebClient 响应式转发 | 统一代理第三方 API，支持多种认证方式 |

### 安全防护体系

| 防护层次 | 技术方案 | 防护效果 |
|---------|---------|---------|
| **防篡改** | HMAC-SHA256 签名 | 请求内容被修改立即检测 |
| **防重放** | nonce + Redis 去重 | 相同请求300秒内只能使用一次 |
| **防时序攻击** | 常量时间比较 | 避免通过响应时间推断密钥 |
| **防暴力破解** | Redis 锁定机制 | 5次失败锁定10分钟 |
| **密钥保护** | AES-GCM 加密存储 | 数据库泄露不会暴露签名密钥 |

### 完整功能列表

**API 管理**
- 接口注册与配置（URL、方法、限流、超时、重试）
- 接口审核流程（PENDING/APPROVED/REJECTED）
- 多种认证方式（NONE/API_KEY/BASIC/BEARER）
- 动态路由配置（无需重启即可生效）

**用户管理**
- 用户注册与登录（BCrypt 加密 + MD5 兼容迁移）
- AK/SK 密钥管理（自动生成 + AES-GCM 加密存储）
- 角色权限控制（user/admin，基于 AOP 切面）
- 防暴力破解（Redis 失败计数 + 自动锁定）

**流量控制**
- 滑动窗口限流（用户+接口双维度，Redis Sorted Set）
- 配额预扣减（原子 SQL + 数据库行锁）
- 服务熔断降级（三态机制 + 自动恢复）
- 优雅降级策略（Redis/配额服务异常时的兜底方案）

**监控统计**
- 接口调用统计（总次数、剩余次数）
- 全链路日志追踪（UUID 请求 ID + AOP 拦截器）
- 性能监控（接口响应时间、成功率）
- 配额使用分析

**开发支持**
- Knife4j 接口文档（OpenAPI 3 规范，在线调试）
- 客户端 SDK（Spring Boot Starter 自动装配）
- Mock 测试服务（验证网关功能）
- Docker Compose 一键部署

---

## 技术架构

### 系统分层

```
客户端 SDK ─── 自动签名、请求封装
      ↓
网关层 (Gateway) ──── 8层过滤器链（响应式处理）
      ↓
业务层 (Platform) ─── 用户、接口、配额管理
      ↓
数据层 ──────────── MySQL、Redis、Dubbo
```

### 8层过滤器链

| Order | 过滤器 | 职责 | 技术要点 |
|-------|--------|------|---------|
| -100 | LoggingFilter | 请求日志记录 | UUID 请求 ID 生成 |
| -90 | SecurityFilter | IP 白名单验证 | 可配置开关 |
| -80 | AuthenticationFilter | 签名认证 + 防重放 | Dubbo 查询用户 + Redis 去重 |
| -70 | InterfaceFilter | 接口元数据验证 | Dubbo 查询接口状态 |
| -60 | RateLimitFilter | 滑动窗口限流 | Redis Sorted Set |
| -50 | QuotaFilter | 配额预扣减 | Dubbo RPC + 原子 SQL |
| -40 | ProxyFilter | 动态代理转发 | WebClient 响应式转发 |
| -30 | ResponseFilter | 响应处理 + 统计 | Dubbo 记录调用次数 |

### 核心技术

| 技术 | 用途 | 选型理由 |
|------|------|---------|
| **Spring Cloud Gateway** | 响应式网关 | WebFlux 全链路非阻塞，高并发性能优秀 |
| **Dubbo 3.3.0** | 微服务 RPC | 高性能，网关与平台解耦，避免直接访问数据库 |
| **Redis** | 分布式缓存 | 限流、防重放、熔断状态共享，支持多网关实例 |
| **MyBatis-Plus** | 数据访问 | Lambda 语法，支持 SQL 表达式原子更新 |
| **WebFlux** | 响应式编程 | Reactor 链式异步调用，boundedElastic 线程池隔离 |

---

## 核心设计

### 1. 滑动窗口限流算法

**设计目标**

传统固定窗口限流存在临界点流量突刺问题：
- 假设限流 100 次/分钟
- 00:59 秒：99 次请求
- 01:00 秒：窗口重置
- 01:01 秒：100 次请求
- **实际**：2 秒内 199 次请求，严重超限

**实现方案**

采用 Redis Sorted Set 实现精确的滑动窗口限流。

核心机制：
- **时间戳作为 score**：自动排序，便于范围查询
- **请求记录作为 member**："{timestamp}:{uuid}" 确保唯一性
- **滑动窗口统计**：统计过去 N 秒的请求数，无临界点问题
- **自动清理**：ZREMRANGEBYSCORE 清理过期记录，EXPIRE 设置 key 过期

算法步骤：
1. 清理窗口外的过期记录（ZREMRANGEBYSCORE 命令）
2. 添加当前请求时间戳（ZADD 命令，member 为 "{时间戳}:{uuid}"）
3. 设置 key 过期时间（EXPIRE 命令，防止内存泄漏）
4. 统计窗口内请求数（ZCOUNT 命令，统计指定时间范围内的记录数）
5. 判断是否超过限制（请求数 ≤ 限流阈值则允许通过）

多维度限流：
- Redis key 格式：`xiaoxin:rate_limit:{userId}:{interfaceId}`
- 支持全局限流、用户限流、接口限流等多种策略
- 接口可自定义限流阈值（interface_info.rateLimit 字段）

降级策略：
- Redis 异常时通过响应式流的异常处理（onErrorResume）允许请求通过
- 保证限流服务故障不影响核心业务

**设计亮点**
- 滑动窗口比固定窗口更平滑，避免临界点流量突刺
- 多维度限流配置，灵活适应不同场景
- 优雅降级，保证系统可用性

---

### 2. 配额预扣减机制

**设计目标**

配额管理需要解决三个核心问题：
- **编辑场景的累计误差**：重复增加导致配额虚高
- **并发场景的配额穿透**：多人同时上传超过限制（类似超卖问题）
- **数据一致性问题**：扣减配额和记录操作必须原子

传统"先查后减"方案存在竞态条件（Check-Then-Act）：两个线程同时查询到 leftNum=1，然后都执行扣减，导致配额变成 -1，出现超用问题。

**实现方案**

采用预扣减 + 原子操作 + 事务保障的策略。

预扣减机制：
- **请求开始时**：调用 preConsume() 预扣减配额
- **请求成功后**：调用 invokeCount() 记录 totalNum + 1
- **请求失败时**：配额已扣减（业务可选择是否回滚）

原子 SQL 操作：
- 使用 `UPDATE user_interface_info SET leftNum = leftNum - 1 WHERE userId = ? AND interfaceInfoId = ? AND leftNum > 0`
- WHERE 子句的 `leftNum > 0` 条件实现 CAS（Compare-And-Swap）语义
- 返回影响行数：0 表示配额不足，1 表示扣减成功

数据库行锁保障：
- `UPDATE WHERE id = ?` 自动加行锁
- 多个并发请求串行执行
- 利用 MySQL InnoDB 的 ACID 特性

懒创建机制：
- 免费接口（配置在 `xiaoxin.quota.free-interfaces`）首次调用自动创建配额记录
- 通过 try-catch 捕获 `DuplicateKeyException` 处理并发创建冲突

降级策略：
- **严格模式**（strictMode=true）：配额服务异常时拒绝请求，保证数据一致性
- **宽松模式**（strictMode=false）：配额服务异常时允许请求通过，标记 `quota.bypass`

**设计亮点**
- 预扣减机制避免"检查-使用"竞态条件
- 原子操作 + 数据库行锁保障并发安全
- 懒创建机制提升用户体验，降低运维成本

---

### 3. Redis 分布式断路器

**设计目标**

网关代理第三方 API，下游服务不可控。需要解决：
- **雪崩效应**：下游服务异常时，请求堆积导致网关资源耗尽
- **快速失败**：避免超时等待，快速返回错误
- **自动恢复**：服务恢复后，断路器自动关闭

**实现方案**

采用三态熔断机制 + Redis 分布式协调。

三态机制：
- **CLOSED（关闭）**：正常工作，滑动窗口统计失败率
- **OPEN（开启）**：失败率超阈值（如 50%），拒绝所有请求
- **HALF_OPEN（半开）**：冷却后试探性放行请求，验证服务是否恢复

状态转换流程：
- CLOSED 状态：失败率超过阈值时切换到 OPEN
- OPEN 状态：冷却时间到后切换到 HALF_OPEN
- HALF_OPEN 状态：探测成功切换到 CLOSED，探测失败重新熔断切换回 OPEN

滑动窗口统计：
- Redis Sorted Set 存储失败记录，时间戳作为 score
- 统计窗口内（如最近 60 秒）的失败率
- 自动清理过期记录，防止内存泄漏

探测锁机制：
- 使用 Redis 分布式锁（`SETNX`）
- 只允许一个网关实例进行探测
- 探测成功后切换到 CLOSED 状态，恢复正常流量

分布式协调：
- Redis 存储熔断状态，多网关实例共享
- 保证一个实例熔断，所有实例生效
- 避免单点故障

**设计亮点**
- 三态机制 + 自动恢复，无需人工干预
- 滑动窗口统计失败率，比计数器更精确
- Redis 分布式协调，支持多网关实例部署

---

### 4. HMAC-SHA256 签名认证体系

**设计目标**

API 网关需要解决三大安全问题：
- **防篡改**：请求内容被修改
- **防重放**：截获请求后重复发送
- **防时序攻击**：通过响应时间差异推断密钥

**实现方案**

采用 HMAC-SHA256 签名 + nonce + Redis 去重 + 常量时间比较。

签名算法（Canonical String）：
- 构建规范字符串：method、path、contentSha256（POST时）、timestamp、nonce 按顺序用换行符拼接
- 计算签名：使用用户的 SecretKey 对规范字符串进行 HMAC-SHA256 哈希
- 客户端和服务端使用相同算法，签名匹配则验证通过

防重放机制：
- **时间戳验证**：300 秒有效期，超时请求自动拒绝
- **nonce 去重**：16 位随机字符串，Redis `SETNX` 原子操作
- **过期时间**：nonce 在 Redis 中的 TTL = 签名有效期
- **唯一性保证**：相同 nonce 在有效期内只能使用一次

防时序攻击：
- 使用 `MessageDigest.isEqual()` 进行常量时间比较
- 避免通过响应时间差异推断签名是否匹配
- 标准 `String.equals()` 会因字符位置不同导致时间差异

Dubbo RPC 认证：
- 网关通过 Dubbo 调用平台服务的 `InnerUserService.getInvokeUser(accessKey)`
- 使用 `Mono.fromCallable` + `subscribeOn(boundedElastic)` 避免阻塞 WebFlux 事件循环

**设计亮点**
- HMAC-SHA256 签名防篡改，对称加密性能高
- nonce + Redis 去重防重放，无需服务端存储大量历史请求
- 常量时间比较防时序攻击，符合密码学安全规范

---

### 5. ThreadLocal 用户上下文管理

**设计目标**

传统方案中，每个方法都需要传递 HttpServletRequest 或 User 参数，这导致方法签名污染、层层传递麻烦、Service 层与 Web 层耦合等问题。

**实现方案**

参考 Spring Security 的 SecurityContextHolder 设计模式，使用 ThreadLocal 管理用户上下文。

核心机制：
- **ThreadLocal 存储**：`ThreadLocal<User> USER_CONTEXT`，每个线程独立存储
- **拦截器管理**：`UserContextInterceptor` 在请求开始时设置，结束时清理
- **API 设计**：`getCurrentUser()`、`requireCurrentUser()`、`isCurrentUserAdmin()` 等便捷方法

生命周期管理：
- **preHandle**：拦截器从 Session 获取用户，存入 ThreadLocal
- **Controller/Service**：通过 getCurrentUser() 直接获取当前用户
- **afterCompletion**：拦截器清理 ThreadLocal（使用 remove() 而非 set(null)）

防止内存泄漏：
- 使用 `ThreadLocal.remove()` 而非 `set(null)`，更彻底释放内存
- 在拦截器 `afterCompletion` 中必须清理，避免线程池复用时数据污染
- `setCurrentUser()` 方法设为 package-private，防止业务代码滥用

**设计亮点**
- 消除重复参数，方法签名更简洁
- 参考 Spring Security 行业标准做法
- 拦截器自动管理生命周期，防止内存泄漏

---

## 技术要点

### WebFlux 响应式架构
- **全链路非阻塞**：Reactor 响应式编程实现链式异步调用
- **线程池隔离**：boundedElastic 线程池处理阻塞操作（Dubbo RPC、数据库查询）
- **事件循环保护**：避免阻塞 Netty 事件循环线程，保证并发性能

### Dubbo 微服务解耦
- **三层服务接口**：InnerUserService（用户）、InnerInterfaceInfoService（接口）、InnerUserInterfaceInfoService（配额）
- **异步化处理**：响应式包装同步 Dubbo 调用，切换到专用线程池执行
- **职责分离**：网关不直接访问数据库，通过 Dubbo 调用平台服务

### 密码安全管理
- **BCrypt 加密**：新用户注册使用 BCrypt 慢哈希算法（防彩虹表攻击）
- **MD5 兼容迁移**：旧用户首次登录自动升级到 BCrypt
- **平滑过渡**：用户无感知，零停机升级

### AES-GCM 密钥保护
- **主密钥加密**：masterKey 加密所有用户的 SecretKey
- **认证加密**：GCM 模式既加密又防篡改
- **合规要求**：符合 PCI-DSS、等保三级敏感信息加密存储要求

### Redis 防暴力破解
- **失败计数**：Redis 记录登录失败次数
- **自动锁定**：5 次失败锁定 10 分钟
- **自动解锁**：TTL 过期自动解除，无需人工干预

### 统一异常处理
- **分层处理**：网关层和平台层分别处理不同类型异常
- **响应式异常**：异常流式传播，优雅降级处理
- **统一格式**：统一返回 JSON 格式响应（code、message、data）

### AOP 日志拦截
- **UUID 请求 ID**：唯一标识每个请求，便于日志关联
- **StopWatch 计时**：精确记录接口响应时间
- **全链路追踪**：可扩展为分布式链路追踪系统

---

## 项目信息

### 项目结构

```
xiaoxin-api/
├── xiaoxin-api-common/          # 公共模块
│   ├── entity/                  # 实体类（User、InterfaceInfo、UserInterfaceInfo）
│   ├── utils/                   # 工具类（ApiSignUtils、CryptoUtils）
│   └── constants/               # 常量定义
│
├── xiaoxin-api-gateway/         # 网关服务
│   ├── filter/                  # 8层过滤器
│   │   ├── LoggingFilter        # 请求日志记录
│   │   ├── SecurityFilter       # IP白名单验证
│   │   ├── AuthenticationFilter # 签名认证 + 防重放
│   │   ├── InterfaceFilter      # 接口元数据验证
│   │   ├── RateLimitFilter      # 滑动窗口限流
│   │   ├── QuotaFilter          # 配额预扣减
│   │   ├── ProxyFilter          # 动态代理转发
│   │   └── ResponseFilter       # 响应处理 + 统计
│   ├── circuit/                 # 断路器（RedisCircuitBreaker）
│   ├── config/                  # 配置类（WebClientConfig）
│   └── exception/               # 异常处理（GatewayExceptionHandler）
│
├── xiaoxin-api-platform/        # 平台服务
│   ├── controller/              # 控制器（User、InterfaceInfo、UserInterfaceInfo）
│   ├── service/                 # 业务服务
│   │   └── impl/inner/          # Dubbo服务实现
│   ├── mapper/                  # 数据访问层
│   ├── aop/                     # AOP切面（AuthInterceptor、LogInterceptor）
│   ├── context/                 # 用户上下文（UserContextHolder）
│   └── interceptor/             # 拦截器（UserContextInterceptor）
│
├── xiaoxin-client-sdk/          # 客户端SDK
│   ├── client/                  # XiaoxinApiClient
│   ├── config/                  # 自动配置类
│   └── properties/              # 配置属性
│
├── xiaoxin-mock-service/        # Mock服务
│   └── controller/              # 测试接口
│
└── docker-compose.yml           # Docker Compose编排
```

### 技术栈版本

**核心框架**
- Spring Boot 3.1.12（Platform）/ 3.5.5（Gateway）
- Spring Cloud Gateway 2025.0.0
- Dubbo 3.3.0

**数据存储**
- MySQL 8.2
- Redis 7.0
- MyBatis-Plus 3.5.7

**工具库**
- Hutool 5.8.39、Gson 2.13.1、Lombok 1.18.30

**文档与SDK**
- Knife4j 4.4.0（OpenAPI 3 规范）

**部署**
- Docker Compose（MySQL、Redis、Gateway、Platform、Mock）

---

## 架构特色

### 分层设计
- **职责清晰**：网关层（路由认证）→ 平台层（业务逻辑）→ SDK 层（客户端封装）
- **统一规范**：统一响应格式、统一异常处理、统一日志格式

### 安全设计
- **多层防护**：签名认证 → 防重放 → 防时序攻击 → 防暴力破解
- **密钥保护**：AES-GCM 加密存储 SK，masterKey 主密钥保护
- **密码安全**：BCrypt 慢哈希 + 随机盐 + 自动升级

### 性能优化
- **响应式架构**：WebFlux 全链路非阻塞，boundedElastic 线程池隔离
- **缓存策略**：Redis 缓存权限数据（TTL: 30分钟）
- **异步处理**：Dubbo RPC 异步化，避免阻塞事件循环
- **索引优化**：联合索引加速查询（uni_userAccount、idx_accessKey、uni_user_interface）

### 可扩展性
- **水平扩展**：网关无状态，可任意扩容
- **插件化**：过滤器可灵活增删，Order 控制顺序
- **配置化**：限流阈值、熔断参数、权限配置均可动态调整
- **多认证方式**：支持 NONE/API_KEY/BASIC/BEARER 等多种认证

---

**文档定位**：本文档介绍项目的功能特性、技术架构和核心设计方案。
