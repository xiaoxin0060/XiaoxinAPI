package com.xiaoxin.api.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiaoxin.api.platform.common.ErrorCode;
import com.xiaoxin.api.platform.config.QuotaFreeProperties;
import com.xiaoxin.api.platform.exception.BusinessException;
import com.xiaoxin.api.platform.mapper.UserInterfaceInfoMapper;
import com.xiaoxin.api.platform.model.entity.UserInterfaceInfo;
import com.xiaoxin.api.platform.service.UserInterfaceInfoService;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
* @author 小新
* @description 针对表【user_interface_info(用户调用接口关系)】的数据库操作Service实现
* @createDate 2025-08-27 01:48:04
*/
@Service
public class UserInterfaceInfoServiceImpl extends ServiceImpl<UserInterfaceInfoMapper,UserInterfaceInfo>
    implements UserInterfaceInfoService{

    @Resource
    private QuotaFreeProperties quotaFreeProperties;

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
    @Transactional
    public boolean preConsume(long interfaceInfoId, long userId) {
        if (interfaceInfoId <= 0 || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        
        // 1. 查询现有配额记录
        UserInterfaceInfo existingRecord = getByUserAndInterface(userId, interfaceInfoId);
        
        // 2. 懒创建免费配额（如果记录不存在且配置允许）
        if (existingRecord == null && quotaFreeProperties.shouldCreateFreeQuota(interfaceInfoId)) {
            try {
                createFreeQuotaRecord(userId, interfaceInfoId);
            } catch (DuplicateKeyException e) {
                // 并发场景：唯一索引冲突，说明其他线程已创建，重新查询即可
                existingRecord = getByUserAndInterface(userId, interfaceInfoId);
            }
        }
        
        // 3. 原子预扣减配额（仅当剩余次数>0时才扣减）
        return atomicDecrementQuota(userId, interfaceInfoId);
    }

    @Override
    public boolean addQuota(long interfaceInfoId, long userId, int addCount) {
        if (interfaceInfoId <= 0 || userId <= 0 || addCount <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 先查询是否存在
        LambdaQueryWrapper<UserInterfaceInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(UserInterfaceInfo::getInterfaceInfoId, interfaceInfoId)
                    .eq(UserInterfaceInfo::getUserId, userId);

        UserInterfaceInfo userInterfaceInfo = this.getOne(queryWrapper);

        if (userInterfaceInfo == null) {
            // 不存在则插入新记录
            userInterfaceInfo = new UserInterfaceInfo();
            userInterfaceInfo.setInterfaceInfoId(interfaceInfoId);
            userInterfaceInfo.setUserId(userId);
            userInterfaceInfo.setLeftNum(addCount);
            userInterfaceInfo.setTotalNum(addCount);
            return this.save(userInterfaceInfo);
        } else {
            // 存在则更新
            userInterfaceInfo.setLeftNum(userInterfaceInfo.getLeftNum() + addCount);
            userInterfaceInfo.setTotalNum(userInterfaceInfo.getTotalNum() + addCount);
            return this.updateById(userInterfaceInfo);
        }
    }
    
    // ========== 懒创建免费配额相关方法 ==========
    
    /**
     * 根据用户ID和接口ID查询配额记录
     * 
     * @param userId 用户ID
     * @param interfaceInfoId 接口ID
     * @return 配额记录，不存在则返回null
     */
    private UserInterfaceInfo getByUserAndInterface(long userId, long interfaceInfoId) {
        LambdaQueryWrapper<UserInterfaceInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(UserInterfaceInfo::getUserId, userId)
                   .eq(UserInterfaceInfo::getInterfaceInfoId, interfaceInfoId);
        return this.getOne(queryWrapper);
    }
    
    /**
     * 创建免费配额记录
     * 
     * 面试官友好特性：
     * - 新用户首次调用接口时自动赠送免费配额
     * - leftNum: 免费调用次数（配置项决定）
     * - totalNum: 0（由后续成功调用incrementalNum增加）
     * 
     * @param userId 用户ID
     * @param interfaceInfoId 接口ID
     * @throws DuplicateKeyException 并发场景下唯一索引冲突
     */
    private void createFreeQuotaRecord(long userId, long interfaceInfoId) {
        UserInterfaceInfo freeRecord = new UserInterfaceInfo();
        freeRecord.setUserId(userId);
        freeRecord.setInterfaceInfoId(interfaceInfoId);
        freeRecord.setLeftNum(quotaFreeProperties.getLeftNum()); // 从配置获取免费次数
        freeRecord.setTotalNum(0);  // 总次数由invokeCount方法增加
        freeRecord.setStatus(0);    // 正常状态
        
        boolean saveResult = this.save(freeRecord);
        if (!saveResult) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "创建免费配额记录失败");
        }
    }
    
    /**
     * 原子预扣减配额
     * 
     * 使用数据库行锁确保并发安全：
     * - 仅当 leftNum > 0 时才执行扣减
     * - leftNum = leftNum - 1（原子操作）
     * - 返回是否扣减成功
     * 
     * @param userId 用户ID
     * @param interfaceInfoId 接口ID
     * @return true-扣减成功，false-配额不足
     */
    private boolean atomicDecrementQuota(long userId, long interfaceInfoId) {
        UpdateWrapper<UserInterfaceInfo> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("userId", userId)
                    .eq("interfaceInfoId", interfaceInfoId)
                    .gt("leftNum", 0)  // 仅当剩余次数>0时才扣减
                    .setSql("leftNum = leftNum - 1");
        
        return this.update(updateWrapper);
    }

}




