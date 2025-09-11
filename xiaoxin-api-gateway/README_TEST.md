# 网关过滤器链条集成测试指南

## 📋 概述

本测试方案验证小新API网关的完整过滤器链条执行，包含8个核心过滤器的集成测试，使用真实的Mock服务接口进行端到端验证。

## 🔧 测试环境配置

### 用户数据配置
- **用户ID**: 3 (xiaoxin)
- **AccessKey**: `ak_GZUxlpN8ZtHWGDqaqtao4jBU`
- **SecretKey**: `sk_eCclbpWXVc5bJuxiflTzzmsgEBXwq5gAX14Y93Lx`
- **角色**: 管理员

### Mock接口配置
- **GET接口**: `/api/test/get` → `http://localhost:8080/test/get`
- **POST接口**: `/api/test/post` → `http://localhost:8080/test/post`

## 🚀 快速开始

### 1. 准备数据库
```bash
# 执行测试数据脚本
mysql -u root -p xiaoxinapi < xiaoxin-api-platform/sql/gateway_test_data.sql
```

### 2. 启动服务
```bash
# 启动Mock服务（端口8080）
cd xiaoxin-mock-service
mvn spring-boot:run

# 启动网关服务（端口9999）
cd xiaoxin-api-gateway  
mvn spring-boot:run

# 确保Redis服务已启动（用于限流和防重放）
```

### 3. 运行测试
```bash
cd xiaoxin-api-gateway
mvn test -Dtest=GatewayFilterChainSimpleTest
```

## 🧪 测试用例覆盖

### ✅ 成功场景测试
- **GET请求完整链条**: 验证8个过滤器按顺序执行
- **POST请求完整链条**: 验证JSON数据处理和签名验证

### ❌ 异常场景测试
- **认证失败**: 无效AccessKey、错误签名
- **接口不存在**: 路径不匹配测试
- **配额不足**: 模拟用户余额不足
- **缺少认证头部**: 参数验证测试
- **签名验证失败**: HMAC-SHA256验证测试

## 🔄 过滤器执行顺序

1. **LoggingFilter(-100)** → 记录请求信息，解析客户端IP
2. **SecurityFilter(-90)** → IP白名单验证
3. **AuthenticationFilter(-80)** → HMAC-SHA256签名验证
4. **InterfaceFilter(-70)** → 接口查询和状态检查
5. **RateLimitFilter(-60)** → Redis滑动窗口限流
6. **QuotaFilter(-50)** → 用户配额管理和预扣减
7. **ProxyFilter(-40)** → 动态代理转发到Mock服务
8. **ResponseFilter(-30)** → 统一响应处理

## 🔐 签名计算示例

### 运行签名计算工具
```bash
# 查看签名计算示例
cd xiaoxin-api-gateway
mvn test -Dtest=SignatureTestHelper -Dtest.main=true
```

### 手动签名计算
```java
// Canonical字符串格式
String canonical = method + "\n" + path + "\n" + contentSha256 + "\n" + timestamp + "\n" + nonce;

// 计算HMAC-SHA256签名
String signature = ApiSignUtils.hmacSha256Hex(canonical, secretKey);
```

### GET请求示例
```bash
curl -X GET "http://localhost:9999/api/test/get?name=test&userId=123" \
  -H "accessKey: ak_GZUxlpN8ZtHWGDqaqtao4jBU" \
  -H "timestamp: 1706183445" \
  -H "nonce: abcd1234efgh5678" \
  -H "sign: [计算的签名值]"
```

### POST请求示例
```bash
curl -X POST "http://localhost:9999/api/test/post" \
  -H "Content-Type: application/json" \
  -H "accessKey: ak_GZUxlpN8ZtHWGDqaqtao4jBU" \
  -H "timestamp: 1706183445" \
  -H "nonce: wxyz9876stuv4321" \
  -H "x-content-sha256: [请求体SHA256哈希]" \
  -H "sign: [计算的签名值]" \
  -d '{"name":"测试用户","email":"test@example.com","age":25}'
```

## 📊 验证指标

### 功能验证
- [x] 所有过滤器按顺序执行
- [x] 认证签名验证正确
- [x] Mock服务正确响应
- [x] 限流机制生效
- [x] 配额管理正确
- [x] 异常处理完善

### 性能验证
- [x] 响应时间 < 2秒
- [x] 并发请求处理正常
- [x] Redis操作正常
- [x] 内存使用合理

## 🛠️ 故障排查

### 常见问题

1. **签名验证失败**
   - 检查AccessKey和SecretKey是否正确
   - 验证时间戳是否在5分钟内
   - 确认nonce为16位字母数字字符

2. **Mock服务连接失败**
   - 确认xiaoxin-mock-service在8080端口运行
   - 检查防火墙设置
   - 验证Mock服务健康状态

3. **Redis连接异常**
   - 确认Redis服务已启动
   - 检查Redis连接配置
   - 验证Redis权限设置

4. **测试数据问题**
   - 重新执行gateway_test_data.sql脚本
   - 检查用户ID为3的数据是否存在
   - 验证接口配置是否正确

### 日志分析
```bash
# 查看网关日志
tail -f xiaoxin-api-gateway/logs/spring.log

# 查看Mock服务日志  
tail -f xiaoxin-mock-service/logs/spring.log
```

## 📁 文件结构

```
xiaoxin-api-gateway/
├── src/test/java/com/xiaoxin/api/gateway/integration/
│   ├── GatewayFilterChainSimpleTest.java      # 主测试类
│   └── SignatureTestHelper.java               # 签名计算工具
├── README_TEST.md                             # 测试说明文档
└── ...

xiaoxin-api-platform/
└── sql/
    └── gateway_test_data.sql                  # 测试数据脚本
```

## ⚡ 扩展测试

### 性能压测
```bash
# 使用Apache Bench进行压力测试
ab -n 1000 -c 10 \
  -H "accessKey: ak_GZUxlpN8ZtHWGDqaqtao4jBU" \
  -H "timestamp: $(date +%s)" \
  -H "nonce: test1234test5678" \
  -H "sign: [计算签名]" \
  http://localhost:9999/api/test/get
```

### 并发测试
- 可在测试类中增加更多并发测试用例
- 验证Redis限流的准确性
- 测试防重放攻击机制

### 监控集成
- 集成Micrometer指标收集
- 添加链路追踪支持
- 接入外部监控系统

## 💡 最佳实践

1. **签名安全**: SecretKey只在内存中使用，不记录日志
2. **时间同步**: 确保客户端和服务端时间同步
3. **nonce唯一性**: 每次请求使用不同的随机nonce
4. **错误处理**: 统一的错误响应格式
5. **性能监控**: 记录关键指标用于性能分析

---

🎯 **测试目标**: 验证网关过滤器链条的完整性、正确性和稳定性，确保API网关能够正确处理认证、限流、代理等核心功能。
