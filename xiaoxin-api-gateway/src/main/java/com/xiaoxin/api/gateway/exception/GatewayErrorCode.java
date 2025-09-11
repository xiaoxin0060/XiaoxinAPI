package com.xiaoxin.api.gateway.exception;

/**
 * 网关错误码枚举 - 简化版
 * 
 * 学习要点：
 * 1. 枚举的基本使用和构造函数
 * 2. 错误码的分类和管理
 * 3. HTTP状态码的映射关系
 * 4. 枚举方法的实现和使用
 * 
 * 错误码分类：
 * - 1xxx：认证和安全相关错误
 * - 2xxx：业务逻辑相关错误  
 * - 3xxx：系统和技术相关错误
 * 
 * @author xiaoxin
 * @since 1.0.0
 */
public enum GatewayErrorCode {

    // ========== 认证和安全错误 (1xxx) ==========
    
    /** 认证失败 */
    AUTH_FAILED(1001, "认证失败", 401),
    
    /** 签名无效 */
    INVALID_SIGNATURE(1002, "签名验证失败", 401),
    
    /** 时间戳过期 */
    TIMESTAMP_EXPIRED(1003, "请求时间戳已过期", 401),
    
    /** IP不在白名单 */
    IP_FORBIDDEN(1004, "IP地址不在白名单中", 403),
    
    /** 重放攻击 */
    REPLAY_ATTACK(1005, "检测到重放攻击", 403),

    // ========== 业务逻辑错误 (2xxx) ==========
    
    /** 接口不存在 */
    INTERFACE_NOT_FOUND(2001, "请求的接口不存在", 404),
    
    /** 接口不可用 */
    INTERFACE_UNAVAILABLE(2002, "接口当前不可用", 503),
    
    /** 触发限流 */
    RATE_LIMITED(2003, "请求过于频繁，请稍后重试", 429),
    
    /** 配额不足 */
    QUOTA_EXCEEDED(2004, "调用配额不足", 403),
    
    /** 权限不足 */
    PERMISSION_DENIED(2005, "没有调用该接口的权限", 403),

    // ========== 系统技术错误 (3xxx) ==========
    
    /** 系统内部错误 */
    INTERNAL_ERROR(3001, "系统内部错误", 500),
    
    /** 上游服务异常 */
    UPSTREAM_ERROR(3002, "上游服务异常", 502),
    
    /** 网络超时 */
    TIMEOUT_ERROR(3003, "网络请求超时", 504),
    
    /** 数据库异常 */
    DATABASE_ERROR(3004, "数据访问异常", 500),
    
    /** Redis异常 */
    REDIS_ERROR(3005, "缓存服务异常", 500);

    /** 错误码 */
    private final int code;
    
    /** 错误消息 */
    private final String message;
    
    /** HTTP状态码 */
    private final int httpStatus;

    /**
     * 构造函数
     * 
     * @param code 错误码
     * @param message 错误消息
     * @param httpStatus HTTP状态码
     */
    GatewayErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    /**
     * 获取错误码
     * 
     * @return 错误码
     */
    public int getCode() {
        return code;
    }

    /**
     * 获取错误消息
     * 
     * @return 错误消息
     */
    public String getMessage() {
        return message;
    }

    /**
     * 获取HTTP状态码
     * 
     * @return HTTP状态码
     */
    public int getHttpStatus() {
        return httpStatus;
    }

    /**
     * 根据错误码查找对应的枚举
     * 
     * 学习要点：
     * - 枚举的遍历和查找
     * - 静态方法的使用
     * - 查找失败的处理
     * 
     * @param code 错误码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static GatewayErrorCode fromCode(int code) {
        for (GatewayErrorCode errorCode : values()) {
            if (errorCode.getCode() == code) {
                return errorCode;
            }
        }
        return null;
    }

    /**
     * 判断是否为认证错误
     * 
     * @return true-认证错误，false-其他错误
     */
    public boolean isAuthError() {
        return code >= 1000 && code < 2000;
    }

    /**
     * 判断是否为业务错误
     * 
     * @return true-业务错误，false-其他错误
     */
    public boolean isBusinessError() {
        return code >= 2000 && code < 3000;
    }

    /**
     * 判断是否为系统错误
     * 
     * @return true-系统错误，false-其他错误
     */
    public boolean isSystemError() {
        return code >= 3000 && code < 4000;
    }
}
