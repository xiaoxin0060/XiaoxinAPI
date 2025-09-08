package com.xiaoxin.api.platform.aop;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.xiaoxin.api.platform.annotation.AuthCheck;
import com.xiaoxin.api.platform.common.ErrorCode;
import com.xiaoxin.api.platform.context.UserContextHolder;
import com.xiaoxin.api.platform.exception.BusinessException;
import com.xiaoxin.api.platform.model.entity.User;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;


/**
 * 权限校验 AOP
 * 
 * 优化后的实现：
 * - 从ThreadLocal中获取用户信息，避免重复的Session查询
 * - 移除了对HttpServletRequest的依赖
 * - 提高了性能，简化了代码逻辑
 * 
 * 前置条件：
 * - UserContextInterceptor已经设置了用户上下文
 * - 需要权限校验的方法必须在已登录的情况下访问
 */
@Aspect
@Component
public class AuthInterceptor {

    /**
     * 执行权限拦截
     * 
     * 优化说明：
     * 1. 不再需要UserService注入，直接从ThreadLocal获取用户
     * 2. 不再需要RequestContextHolder获取HttpServletRequest
     * 3. 性能提升：避免重复的Session查询和数据库查询
     * 4. 代码简化：移除了复杂的请求上下文处理
     *
     * @param joinPoint AOP连接点
     * @param authCheck 权限检查注解
     * @return 方法执行结果
     * @throws Throwable 执行异常
     */
    @Around("@annotation(authCheck)")
    public Object doInterceptor(ProceedingJoinPoint joinPoint, AuthCheck authCheck) throws Throwable {
        List<String> anyRole = Arrays.stream(authCheck.anyRole()).filter(StringUtils::isNotBlank).collect(Collectors.toList());
        String mustRole = authCheck.mustRole();
        
        // 从ThreadLocal中获取当前登录用户
        // 此时UserContextInterceptor已经设置了用户信息
        User user = UserContextHolder.requireCurrentUser();
        // 拥有任意权限即通过
        if (CollectionUtils.isNotEmpty(anyRole)) {
            String userRole = user.getUserRole();
            if (!anyRole.contains(userRole)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
            }
        }
        // 必须有所有权限才通过
        if (StringUtils.isNotBlank(mustRole)) {
            String userRole = user.getUserRole();
            if (!mustRole.equals(userRole)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
            }
        }
        // 通过权限校验，放行
        return joinPoint.proceed();
    }
}

