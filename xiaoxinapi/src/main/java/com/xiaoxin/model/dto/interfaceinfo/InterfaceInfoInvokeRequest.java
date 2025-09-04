package com.xiaoxin.model.dto.interfaceinfo;

import lombok.Data;
import jakarta.validation.Valid;

import java.io.Serializable;

/**
 * 接口调用请求 - 支持统一JSON参数格式
 */
@Data
public class InterfaceInfoInvokeRequest implements Serializable{

    /**
     * 主键
     */
    private Long id;

    /**
     * 用户请求参数 - 支持JSON对象格式，自动适配GET/POST请求
     * 示例：{"name": "张三", "age": 25, "city": "北京"}
     */
    @Valid
    private Object userRequestParams;

    private static final long serialVersionUID = 1L;
}