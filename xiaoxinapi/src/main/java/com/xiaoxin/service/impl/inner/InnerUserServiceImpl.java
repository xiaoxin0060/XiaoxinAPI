package com.xiaoxin.service.impl.inner;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xiaoxin.common.ErrorCode;
import com.xiaoxin.exception.BusinessException;
import com.xiaoxin.mapper.UserMapper;
import jakarta.annotation.Resource;
import model.entity.User;
import org.apache.commons.lang3.StringUtils;
import org.apache.dubbo.config.annotation.DubboService;
import service.InnerUserService;

/**
 * 内部用户服务
 */
@DubboService
public class InnerUserServiceImpl implements InnerUserService{

    @Resource
    private UserMapper userMapper;

    /**
     * 数据库中查是否已分配给用户秘钥（accessKey）
     * @param accessKey
     * @return
     */
    @Override
    public User getInvokeUser(String accessKey) {
        if (StringUtils.isAnyBlank(accessKey)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("accessKey", accessKey);
        return userMapper.selectOne(queryWrapper);
    }
}
