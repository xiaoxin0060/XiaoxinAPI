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

}




