package com.xiaoxin.model.dto.interfaceinfo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 更新请求
 *
 * @TableName product
 */
@Data
public class InterfaceInfoUpdateRequest implements Serializable{
    /**
     * 主键
     */
    private Long id;

    /**
     * 名称
     */
    private String name;

    /**
     * 描述
     */
    private String description;

    /**
     * 平台统一API路径（对外暴露）
     */
    private String url;

    /**
     * 真实接口地址（内部使用，不对外暴露）
     */
    private String providerUrl;

    /**
     * 请求参数
     */
    private String requestParams;

    /**
     * 请求头
     */
    private String requestHeader;

    /**
     * 响应头
     */
    private String responseHeader;

    /**
     * 接口状态（0-关闭，1-开启）
     */
    private Integer status;

    /**
     * 请求类型
     */
    private String method;

    /**
     * 接口分类
     */
    private String category;

    /**
     * 接口标签
     */
    private String tags;

    /**
     * 版本号
     */
    private String version;

    /**
     * 请求参数Schema（JSON格式）
     */
    private String requestSchema;

    /**
     * 响应参数Schema（JSON格式）
     */
    private String responseSchema;

    /**
     * 认证类型：NONE/API_KEY/BASIC/BEARER
     */
    private String authType;

    /**
     * 认证配置JSON（存储访问真实接口的认证信息）
     */
    private String authConfig;

    /**
     * 转发超时时间（毫秒）
     */
    private Integer timeout;

    /**
     * 失败重试次数
     */
    private Integer retryCount;

    /**
     * 速率限制（每分钟调用次数）
     */
    private Integer rateLimit;

    /**
     * 调用价格
     */
    private BigDecimal price;

    /**
     * 接口提供者用户ID
     */
    private Long providerUserId;

    /**
     * 审核状态：PENDING/APPROVED/REJECTED
     */
    private String approvalStatus;

    /**
     * 接口文档
     */
    private String documentation;

    /**
     * 请求示例
     */
    private String exampleRequest;

    /**
     * 响应示例
     */
    private String exampleResponse;
}