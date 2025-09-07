package com.xiaoxin.api.platform.service;

/**
 * 内部用户接口信息服务
 */
public interface InnerUserInterfaceInfoService {

    /**
     * 调用接口统计
     * @param interfaceInfoId
     * @param userId
     * @return
     */
    boolean invokeCount(long interfaceInfoId, long userId);

    /**
     * 调用前配额预校验并预扣减（leftNum > 0 则 leftNum-1 并返回 true；否则返回 false）
     */
    boolean preConsume(long interfaceInfoId, long userId);

    // 成功统计沿用 invokeCount（仅 totalNum +1）
}
