package com.xiaoxin.api.platform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xiaoxin.api.platform.model.entity.UserInterfaceInfo;

/**
* @author 小新
* @description 针对表【user_interface_info(用户调用接口关系)】的数据库操作Service
* @createDate 2025-08-27 01:48:04
*/
public interface UserInterfaceInfoService extends IService<UserInterfaceInfo>{

    void validUserInterfaceInfo(UserInterfaceInfo userInterfaceInfo, boolean add);

    boolean invokeCount(long interfaceInfoId, long userId);

    /**
     * 预扣减一次调用（leftNum>0 才执行），返回是否成功
     */
    boolean preConsume(long interfaceInfoId, long userId);

    // 记录成功调用改为沿用 invokeCount（仅 totalNum + 1）

    /** 增加用户某接口的调用额度（leftNum += addCount） */
    boolean addQuota(long interfaceInfoId, long userId, int addCount);
}
