package com.xiaoxin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import model.entity.UserInterfaceInfo;

/**
* @author 小新
* @description 针对表【user_interface_info(用户调用接口关系)】的数据库操作Service
* @createDate 2025-08-27 01:48:04
*/
public interface UserInterfaceInfoService extends IService<UserInterfaceInfo>{

    void validUserInterfaceInfo(UserInterfaceInfo userInterfaceInfo, boolean add);

    boolean invokeCount(long interfaceInfoId, long userId);
}
