package com.xiaoxin.api.platform.model.dto.userInterfaceInfo;

import lombok.Data;

import java.io.Serializable;

@Data
public class UserInterfaceInfoAddQuotaRequest implements Serializable{
    /**
     * 调用用户 id
     */
    private Long userId;

    /**
     * 接口 id
     */
    private Long interfaceInfoId;
    /**
     * 接口 id
     */
    private Integer addCount;

}
