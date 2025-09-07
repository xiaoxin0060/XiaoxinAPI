package com.xiaoxin.api.platform.service.impl.inner;

import com.xiaoxin.api.platform.service.InnerUserInterfaceInfoService;
import com.xiaoxin.api.platform.service.UserInterfaceInfoService;
import jakarta.annotation.Resource;
import org.apache.dubbo.config.annotation.DubboService;

/**
 * 内部用户接口信息服务
 */
@DubboService
public class InnerUserInterfaceInfoServiceImpl implements InnerUserInterfaceInfoService{

    @Resource
    private UserInterfaceInfoService userInterfaceInfoService;

    /**
     * 调用接口统计
     * @param interfaceInfoId
     * @param userId
     * @return
     */
    @Override
    public boolean invokeCount(long interfaceInfoId, long userId) {
        return userInterfaceInfoService.invokeCount(interfaceInfoId, userId);
    }

    @Override
    public boolean preConsume(long interfaceInfoId, long userId) {
        return userInterfaceInfoService.preConsume(interfaceInfoId, userId);
    }

    // 成功统计改回 invokeCount 统一语义（仅 totalNum +1）


}
