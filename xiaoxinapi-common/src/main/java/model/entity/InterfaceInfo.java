package model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 接口信息
 * @TableName interface_info
 */
@TableName(value ="interface_info")
@Data
public class InterfaceInfo implements Serializable{
    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
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
     * 创建人
     */
    private Long userId;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 认证类型：NONE/API_KEY/OAUTH2/BASIC
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
     * 接口提供者用户ID
     */
    private Long providerUserId;

    /**
     * 接口分类
     */
    private String category;

    /**
     * 接口标签，逗号分隔
     */
    private String tags;

    /**
     * 接口版本
     */
    private String version;

    /**
     * 请求参数JSON Schema
     */
    private String requestSchema;

    /**
     * 响应参数JSON Schema
     */
    private String responseSchema;

    /**
     * 频率限制（次/分钟）
     */
    private Integer rateLimit;

    /**
     * 调用单价（元/次）
     */
    private BigDecimal price;

    /**
     * 审核状态：PENDING/APPROVED/REJECTED
     */
    private String approvalStatus;

    /**
     * 接口使用文档
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

    /**
     * 是否删除(0-未删, 1-已删)
     */
    @TableLogic
    private Integer isDelete;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}