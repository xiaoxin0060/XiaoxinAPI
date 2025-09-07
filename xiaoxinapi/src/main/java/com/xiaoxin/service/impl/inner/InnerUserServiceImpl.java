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
import org.springframework.beans.factory.annotation.Value;
import util.CryptoUtils;
import java.nio.charset.StandardCharsets;

/**
 * 内部用户服务
 */
@DubboService
public class InnerUserServiceImpl implements InnerUserService{

    @Resource
    private UserMapper userMapper;

    @Value("${security.authcfg.master-key:}")
    private String masterKey;

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
        User user = userMapper.selectOne(queryWrapper);
        if (user == null) return null;
        try {
            String sk = user.getSecretKey();
            if (sk != null && CryptoUtils.isEncrypted(sk) && masterKey != null && !masterKey.isBlank()) {
                String plain = CryptoUtils.aesGcmDecryptToString(masterKey.getBytes(StandardCharsets.UTF_8), null, sk);
                user.setSecretKey(plain);
            }
        } catch (Exception ignored) {}
        return user;
    }
}
