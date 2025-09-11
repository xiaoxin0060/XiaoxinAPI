package com.xiaoxin.api.platform.context;

import com.xiaoxin.api.platform.common.ErrorCode;
import com.xiaoxin.api.platform.exception.BusinessException;
import com.xiaoxin.api.platform.model.entity.User;

/**
 * 用户上下文持有者
 * 
 * 参考Spring Security的SecurityContextHolder设计模式
 * 使用ThreadLocal提供线程安全的用户信息访问
 * 
 * 业务价值：
 * - 消除Controller中重复的HttpServletRequest参数
 * - 统一用户信息获取入口，提高代码可维护性
 * - 避免重复的Session查询和SK解密操作
 * 
 * 技术实现：
 * - ThreadLocal确保多线程环境下的数据隔离
 * - 静态方法提供全局访问能力
 * - package-private方法确保只能通过拦截器设置
 * 
 * 使用示例：
 * <pre>
 * // 获取当前用户（可能为null）
 * User user = UserContextHolder.getCurrentUser();
 * 
 * // 要求必须登录（未登录抛异常）
 * User user = UserContextHolder.requireCurrentUser();
 * 
 * // 获取当前用户ID
 * Long userId = UserContextHolder.getCurrentUserId();
 * </pre>
 * 
 * @author xiaoxin
 * @since 1.0.0
 */
public class UserContextHolder {
    
    /**
     * 线程本地存储，保存当前线程的用户信息
     * 
     * ThreadLocal特性：
     * - 每个线程都有自己独立的变量副本
     * - 线程间数据不会相互干扰
     * - 适合Web请求这种单线程处理模式
     */
    private static final ThreadLocal<User> USER_CONTEXT = new ThreadLocal<>();
    
    /**
     * 获取当前线程的用户信息
     * 
     * 适用场景：
     * - 允许匿名访问的接口中判断用户是否登录
     * - 需要根据登录状态提供不同功能的业务场景
     * 
     * @return 当前登录用户，未登录时返回null
     */
    public static User getCurrentUser() {
        return USER_CONTEXT.get();
    }
    
    /**
     * 要求当前必须有用户登录，否则抛出异常
     * 
     * 适用场景：
     * - 必须登录才能访问的接口
     * - 简化登录状态检查代码
     * 
     * fail-fast原则：尽早发现问题并抛出异常
     * 
     * @return 当前登录用户
     * @throws BusinessException 未登录时抛出NOT_LOGIN_ERROR
     */
    public static User requireCurrentUser() {
        User user = getCurrentUser();
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return user;
    }
    
    /**
     * 获取当前用户ID
     * 
     * 便民方法，避免重复的null检查和ID提取
     * 
     * @return 当前用户ID，未登录时返回null
     */
    public static Long getCurrentUserId() {
        User user = getCurrentUser();
        return user != null ? user.getId() : null;
    }
    
    /**
     * 要求当前必须有用户登录，并返回用户ID
     * 
     * 适用场景：
     * - 业务逻辑只需要用户ID的场景
     * - 简化代码，避免重复的用户获取和ID提取
     * 
     * @return 当前用户ID
     * @throws BusinessException 未登录时抛出NOT_LOGIN_ERROR
     */
    public static Long requireCurrentUserId() {
        return requireCurrentUser().getId();
    }
    
    /**
     * 获取当前用户角色
     * 
     * 适用场景：
     * - 权限判断逻辑中快速获取用户角色
     * 
     * @return 当前用户角色，未登录时返回null
     */
    public static String getCurrentUserRole() {
        User user = getCurrentUser();
        return user != null ? user.getUserRole() : null;
    }
    
    /**
     * 判断当前用户是否为管理员
     * 
     * 业务逻辑封装，提高代码可读性
     * 参考UserConstant中的管理员角色定义
     * 
     * @return true-管理员，false-非管理员或未登录
     */
    public static boolean isCurrentUserAdmin() {
        String role = getCurrentUserRole();
        return "admin".equals(role);
    }
    
    /**
     * 检查当前是否有用户登录
     * 
     * 便民方法，用于快速判断登录状态
     * 
     * @return true-已登录，false-未登录
     */
    public static boolean hasCurrentUser() {
        return getCurrentUser() != null;
    }
    
    /**
     * 设置当前线程的用户信息
     * 
     * package-private访问级别，确保只能通过同包的UserContextInterceptor设置
     * 这样可以防止业务代码随意修改用户上下文，保证数据安全性
     * 
     * 注意：此方法仅供UserContextInterceptor使用
     * 
     * @param user 当前登录用户信息
     */
    public static void setCurrentUser(User user) {
        USER_CONTEXT.set(user);
    }
    
    /**
     * 清除当前线程的用户信息
     * 
     * public访问级别，允许UserContextInterceptor和测试类调用
     * 
     * 重要性：
     * - 防止ThreadLocal内存泄漏
     * - 避免线程池复用时的数据污染
     * - 确保请求结束后清理上下文
     * 
     * 技术说明：
     * - ThreadLocal.remove()会完全清除当前线程的数据
     * - 比设置为null更彻底，能真正释放内存
     * 
     * 使用场景：
     * - UserContextInterceptor在afterCompletion中调用
     * - 单元测试中的清理操作
     * - 特殊情况下的手动清理
     */
    public static void clear() {
        USER_CONTEXT.remove();
    }
}
