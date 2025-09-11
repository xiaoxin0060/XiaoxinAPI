package com.xiaoxin.api.gateway.exception;

/**
 * 网关异常基类 - 简化版
 * 
 * 学习要点：
 * 1. 继承RuntimeException，支持非检查异常
 * 2. 包含业务错误码，便于前端处理
 * 3. 包含HTTP状态码，便于REST API规范
 * 4. 提供多种构造方法，适应不同使用场景
 * 
 * 业务逻辑：
 * - 统一网关内所有异常的基础结构
 * - 提供错误码和HTTP状态码的映射
 * - 支持异常信息的结构化处理
 * 
 * 使用场景：
 * - 过滤器中的业务异常处理
 * - 系统异常的统一包装
 * - 客户端错误信息的标准化返回
 * 
 * @author xiaoxin
 * @since 1.0.0
 */
public class GatewayException extends RuntimeException {

    /**
     * 业务错误码
     */
    private final int errorCode;

    /**
     * HTTP状态码
     */
    private final int httpStatus;

    /**
     * 构造函数 - 完整参数
     * 
     * @param errorCode 业务错误码
     * @param message 错误消息
     * @param httpStatus HTTP状态码
     * @param cause 原始异常
     */
    public GatewayException(int errorCode, String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    /**
     * 构造函数 - 不包含原始异常
     * 
     * @param errorCode 业务错误码
     * @param message 错误消息
     * @param httpStatus HTTP状态码
     */
    public GatewayException(int errorCode, String message, int httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    /**
     * 构造函数 - 使用错误码枚举（推荐方式）
     * 
     * @param errorCode 错误码枚举
     */
    public GatewayException(GatewayErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode.getCode();
        this.httpStatus = errorCode.getHttpStatus();
    }

    /**
     * 构造函数 - 使用错误码枚举和原始异常
     * 
     * @param errorCode 错误码枚举
     * @param cause 原始异常
     */
    public GatewayException(GatewayErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode.getCode();
        this.httpStatus = errorCode.getHttpStatus();
    }

    /**
     * 获取业务错误码
     * 
     * @return 错误码
     */
    public int getErrorCode() {
        return errorCode;
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
     * 重写toString方法，提供更友好的异常信息
     * 
     * @return 异常描述字符串
     */
    @Override
    public String toString() {
        return String.format("GatewayException[errorCode=%d, httpStatus=%d, message=%s]", 
                           errorCode, httpStatus, getMessage());
    }
}
