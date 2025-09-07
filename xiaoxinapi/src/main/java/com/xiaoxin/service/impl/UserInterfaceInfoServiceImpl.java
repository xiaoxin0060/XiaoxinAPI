package com.xiaoxin.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiaoxin.common.ErrorCode;
import com.xiaoxin.exception.BusinessException;
import com.xiaoxin.service.UserInterfaceInfoService;
import com.xiaoxin.mapper.UserInterfaceInfoMapper;
import model.entity.UserInterfaceInfo;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Service;

/**
* @author 小新
* @description 针对表【user_interface_info(用户调用接口关系)】的数据库操作Service实现
* @createDate 2025-08-27 01:48:04
*/
@Service
public class UserInterfaceInfoServiceImpl extends ServiceImpl<UserInterfaceInfoMapper,UserInterfaceInfo>
    implements UserInterfaceInfoService{

    @Override
    public void validUserInterfaceInfo(UserInterfaceInfo userInterfaceInfo, boolean add){
        if (userInterfaceInfo == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Long userId = userInterfaceInfo.getUserId();
        Long interfaceInfoId = userInterfaceInfo.getInterfaceInfoId();
        Integer totalNum = userInterfaceInfo.getTotalNum();
        Integer leftNum = userInterfaceInfo.getLeftNum();
        // 创建时，所有参数必须非空
        if (add) {
            if (ObjectUtils.anyNull(userId, interfaceInfoId, totalNum, leftNum)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR);
            }
        }
        if(leftNum<0) throw new BusinessException(ErrorCode.PARAMS_ERROR, "剩余次数不能小于0");
    }

    @Override
    public boolean invokeCount(long interfaceInfoId, long userId) {
        // 判断
        if (interfaceInfoId <= 0 || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        UpdateWrapper<UserInterfaceInfo> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("interfaceInfoId", interfaceInfoId);
        updateWrapper.eq("userId", userId);
        // 仅记录成功次数 +1（预扣在 preConsume 已经完成）
        updateWrapper.setSql("totalNum = totalNum + 1");
        return this.update(updateWrapper);
    }

    @Override
    public boolean preConsume(long interfaceInfoId, long userId) {
        if (interfaceInfoId <= 0 || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        UpdateWrapper<UserInterfaceInfo> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("interfaceInfoId", interfaceInfoId);
        updateWrapper.eq("userId", userId);
        updateWrapper.gt("leftNum", 0);
        updateWrapper.setSql("leftNum = leftNum - 1");
        return this.update(updateWrapper);
    }

    @Override
    public boolean addQuota(long interfaceInfoId, long userId, int addCount) {
        if (interfaceInfoId <= 0 || userId <= 0 || addCount <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        UpdateWrapper<UserInterfaceInfo> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("interfaceInfoId", interfaceInfoId);
        updateWrapper.eq("userId", userId);
        updateWrapper.setSql("leftNum = leftNum + " + addCount);
        return this.update(updateWrapper);
    }

}




