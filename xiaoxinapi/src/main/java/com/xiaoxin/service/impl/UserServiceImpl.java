package com.xiaoxin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiaoxin.common.ErrorCode;
import com.xiaoxin.exception.BusinessException;
import com.xiaoxin.mapper.UserMapper;
import model.entity.User;
import com.xiaoxin.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import util.CryptoUtils;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;

import static com.xiaoxin.constant.UserConstant.ADMIN_ROLE;
import static com.xiaoxin.constant.UserConstant.USER_LOGIN_STATE;


/**
 * 用户服务实现类
 *
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
        implements UserService {

    @Resource
    private UserMapper userMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${security.authcfg.master-key:}")
    private String masterKey;

    /**
     * 盐值，混淆密码
     */
    private static final String SALT = "xiaoxin"; // 旧版MD5盐，仅用于兼容一次性迁移

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    private static final int MAX_LOGIN_FAIL = 5;
    private static final long LOCK_MINUTES = 10L;

    @Override
    public long userRegister(String userAccount, String userPassword, String checkPassword) {
        // 1. 校验
        if (StringUtils.isAnyBlank(userAccount, userPassword, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号过短");
        }
        if (userPassword.length() < 8 || checkPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码过短");
        }
        // 密码和校验密码相同
        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }
        synchronized (userAccount.intern()) {
            // 账户不能重复
            QueryWrapper<User> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("userAccount", userAccount);
            long count = userMapper.selectCount(queryWrapper);
            if (count > 0) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号重复");
            }
            // 2. 加密（使用BCrypt）
            String encryptPassword = PASSWORD_ENCODER.encode(userPassword);
            // 3. 插入数据
            User user = new User();
            user.setUserAccount(userAccount);
            user.setUserPassword(encryptPassword);
            // 3.1 生成 AK / SK，并对 SK 加密落库
            String accessKey = genKey("ak_", 24);
            String secretKey = genKey("sk_", 40);
            if (masterKey != null && !masterKey.isBlank()) {
                try {
                    String enc = CryptoUtils.aesGcmEncryptFromString(masterKey.getBytes(java.nio.charset.StandardCharsets.UTF_8), null, secretKey);
                    user.setSecretKey(enc);
                } catch (Exception e) {
                    // 加密异常则回退明文（不阻断注册），但建议生产环境强制要求配置主密钥
                    log.info("用户注册密钥加密失败，已回退为明文");
                    user.setSecretKey(secretKey);
                }
            } else {
                user.setSecretKey(secretKey);
            }
            user.setAccessKey(accessKey);
            boolean saveResult = this.save(user);
            if (!saveResult) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败，数据库错误");
            }
            return user.getId();
        }
    }

    @Override
    public User userLogin(String userAccount, String userPassword, HttpServletRequest request) {
        // 1. 校验
        if (StringUtils.isAnyBlank(userAccount, userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号错误");
        }
        if (userPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码错误");
        }
        // 登录失败限制（滑动窗口）
        String failKey = "login:fail:" + userAccount;
        String failStr = stringRedisTemplate.opsForValue().get(failKey);
        if (failStr != null) {
            try {
                int fail = Integer.parseInt(failStr);
                if (fail >= MAX_LOGIN_FAIL) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "账户暂时被锁定，请稍后再试");
                }
            } catch (NumberFormatException ignored) {}
        }

        // 2. 查询用户（先按账号查，再做密码校验）
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", userAccount);
        User user = userMapper.selectOne(queryWrapper);
        if (user == null) {
            recordLoginFail(failKey);
            log.info("用户登录失败：账号不存在");
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
        }

        // 3. 校验密码（优先BCrypt，兼容一次性MD5迁移）
        boolean pass = PASSWORD_ENCODER.matches(userPassword, user.getUserPassword());
        if (!pass) {
            String legacyMd5 = DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());
            if (legacyMd5.equals(user.getUserPassword())) {
                // 一次性迁移为BCrypt
                String newHash = PASSWORD_ENCODER.encode(userPassword);
                user.setUserPassword(newHash);
                this.updateById(user);
                pass = true;
            }
        }
        if (!pass) {
            recordLoginFail(failKey);
            log.info("用户登录失败：密码不匹配");
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
        }

        // 清理失败计数
        stringRedisTemplate.delete(failKey);
        // 4. 记录用户的登录态
        request.getSession().setAttribute(USER_LOGIN_STATE, user);
        return user;
    }

    private void recordLoginFail(String failKey) {
        try {
            Long val = stringRedisTemplate.opsForValue().increment(failKey);
            if (val != null && val == 1L) {
                stringRedisTemplate.expire(failKey, java.time.Duration.ofMinutes(LOCK_MINUTES));
            }
        } catch (Exception e) {
            log.warn("记录登录失败次数出错", e);
        }
    }

    /**
     * 获取当前登录用户
     *
     * @param request
     * @return
     */
    @Override
    public User getLoginUser(HttpServletRequest request) {
        // 先判断是否已登录
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        // 从数据库查询（追求性能的话可以注释，直接走缓存）
        long userId = currentUser.getId();
        currentUser = this.getById(userId);
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        // 解密返回 SK，避免控制台测试调用使用密文
        try {
            String sk = currentUser.getSecretKey();
            if (sk != null && CryptoUtils.isEncrypted(sk) && masterKey != null && !masterKey.isBlank()) {
                String plain = CryptoUtils.aesGcmDecryptToString(masterKey.getBytes(java.nio.charset.StandardCharsets.UTF_8), null, sk);
                currentUser.setSecretKey(plain);
            }
        } catch (Exception ignored) {}
        return currentUser;
    }

    /**
     * 是否为管理员
     *
     * @param request
     * @return
     */
    @Override
    public boolean isAdmin(HttpServletRequest request) {
        // 仅管理员可查询
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        User user = (User) userObj;
        return user != null && ADMIN_ROLE.equals(user.getUserRole());
    }

    private String genKey(String prefix, int len) {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        }
        return prefix + sb.toString();
    }

    /**
     * 用户注销
     *
     * @param request
     */
    @Override
    public boolean userLogout(HttpServletRequest request) {
        if (request.getSession().getAttribute(USER_LOGIN_STATE) == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "未登录");
        }
        // 移除登录态
        request.getSession().removeAttribute(USER_LOGIN_STATE);
        return true;
    }

    @Override
    public java.util.Map<String, String> resetAkSk(long userId) {
        if (userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User user = this.getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "目标用户不存在");
        }
        String newAk = genKey("ak_", 24);
        String newSkPlain = genKey("sk_", 40);
        String toSaveSk = newSkPlain;
        if (masterKey != null && !masterKey.isBlank()) {
            try {
                toSaveSk = CryptoUtils.aesGcmEncryptFromString(masterKey.getBytes(java.nio.charset.StandardCharsets.UTF_8), null, newSkPlain);
            } catch (Exception e) {
                // 如果加密失败，出于一致性可选择中断；这里保守返回错误
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "重置密钥失败，请稍后再试");
            }
        }
        user.setAccessKey(newAk);
        user.setSecretKey(toSaveSk);
        boolean ok = this.updateById(user);
        if (!ok) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "重置密钥失败，请稍后再试");
        }
        java.util.Map<String, String> res = new java.util.HashMap<>();
        res.put("accessKey", newAk);
        res.put("secretKey", newSkPlain); // 仅一次性返回明文
        return res;
    }

}




