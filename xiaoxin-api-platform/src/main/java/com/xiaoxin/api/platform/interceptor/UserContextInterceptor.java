package com.xiaoxin.api.platform.interceptor;

import com.xiaoxin.api.platform.context.UserContextHolder;
import com.xiaoxin.api.platform.model.entity.User;
import com.xiaoxin.api.platform.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 用户上下文拦截器
 * 
 * 参考Spring Security的SecurityContextPersistenceFilter设计理念
 * 在请求处理链的开始和结束进行用户上下文的设置和清理
 * 
 * 核心职责：
 * - 请求开始时：从Session中解析用户信息并存储到ThreadLocal
 * - 请求结束时：清理ThreadLocal防止内存泄漏
 * - 为整个请求链路提供统一的用户信息访问
 * 
 * 技术实现：
 * - 实现HandlerInterceptor接口，利用Spring MVC拦截器机制
 * - 在preHandle中设置用户上下文
 * - 在afterCompletion中清理上下文
 * - 使用try-catch确保异常情况下也能正常处理
 * 
 * 业务价值：
 * - 消除Controller中重复的用户获取逻辑
 * - 统一用户信息的获取和解密处理
 * - 提供线程安全的用户信息访问
 * - 性能优化：避免重复的Session查询和SK解密
 * 
 * 调用链路：
 * DispatcherServlet → UserContextInterceptor.preHandle → Controller → UserContextInterceptor.afterCompletion
 * 
 * @author xiaoxin
 * @since 1.0.0
 */
@Slf4j
@Component
public class UserContextInterceptor implements HandlerInterceptor {
    
    @Resource
    private UserService userService;
    
    /**
     * 请求处理前置方法
     * 
     * 执行时机：Controller方法执行前，但在参数解析之后
     * 
     * 核心逻辑：
     * 1. 尝试从Session中获取用户信息（复用现有的getLoginUser逻辑）
     * 2. 如果用户已登录，设置到UserContextHolder中
     * 3. 如果未登录，不设置（保持null状态）
     * 4. 记录调试日志便于问题排查
     * 
     * 技术特性：
     * - 非强制性：未登录也允许通过，由具体业务方法决定是否需要登录
     * - 容错性：即使获取用户信息失败也不阻断请求
     * - 性能优化：一次获取，整个请求链复用
     * 
     * 异常处理策略：
     * - 捕获所有异常，避免用户信息获取失败影响正常业务
     * - 记录调试日志便于开发期间问题定位
     * - 让具体的业务方法来决定是否需要登录
     * 
     * @param request 当前HTTP请求
     * @param response 当前HTTP响应
     * @param handler 请求处理器（通常是Controller方法）
     * @return true-继续处理请求，false-中断请求
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        try {
            // 复用现有的用户获取逻辑，包括Session检查、数据库查询、SK解密等
            User currentUser = userService.getLoginUser(request);
            
            if (currentUser != null) {
                // 设置用户信息到ThreadLocal，供后续业务逻辑使用
                UserContextHolder.setCurrentUser(currentUser);
                log.debug("用户上下文已设置: userId={}, userRole={}, accessKey={}", 
                         currentUser.getId(), 
                         currentUser.getUserRole(),
                         currentUser.getAccessKey() != null ? 
                             currentUser.getAccessKey().substring(0, Math.min(8, currentUser.getAccessKey().length())) + "..." : "null");
            } else {
                log.debug("当前请求无登录用户信息，URI: {}", request.getRequestURI());
            }
            
        } catch (Exception e) {
            // 获取用户信息失败不应该阻断请求
            // 这允许：
            // 1. 匿名访问的接口正常工作
            // 2. 登录接口本身正常工作
            // 3. 公开API接口正常工作
            log.debug("获取用户信息失败，继续处理请求. URI: {}, 错误: {}", 
                     request.getRequestURI(), e.getMessage());
        }
        
        // 无论是否成功获取用户信息，都继续处理请求
        // 这符合"非侵入式"的设计原则
        return true;
    }
    
    /**
     * 请求完成后的清理方法
     * 
     * 执行时机：
     * - Controller方法执行完成后
     * - 视图渲染完成后
     * - 异常处理完成后
     * - 无论请求成功或失败都会执行
     * 
     * 核心职责：
     * - 清理ThreadLocal中的用户信息
     * - 防止内存泄漏
     * - 避免线程池复用时的数据污染
     * 
     * 技术保障：
     * - 使用try-catch确保清理操作不会失败
     * - 记录清理操作日志便于问题排查
     * - ThreadLocal.remove()比设置null更彻底
     * 
     * 为什么必须清理ThreadLocal：
     * 1. Web服务器使用线程池处理请求
     * 2. 线程处理完请求后会被回收到线程池中
     * 3. 如果不清理，下次复用该线程时会获取到上次的用户信息
     * 4. 这会导致严重的安全问题：用户A可能获取到用户B的信息
     * 
     * @param request 当前HTTP请求
     * @param response 当前HTTP响应  
     * @param handler 请求处理器
     * @param ex 处理过程中的异常（如果有）
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, 
                               Object handler, Exception ex) {
        try {
            // 获取用户信息用于日志记录（清理前）
            User currentUser = UserContextHolder.getCurrentUser();
            
            // 清理ThreadLocal，防止内存泄漏和数据污染
            UserContextHolder.clear();
            
            if (currentUser != null) {
                log.debug("用户上下文已清理: userId={}, URI: {}", 
                         currentUser.getId(), request.getRequestURI());
            }
            
            // 如果请求处理过程中有异常，记录额外信息
            if (ex != null) {
                log.debug("请求处理异常，用户上下文已清理: {}", ex.getMessage());
            }
            
        } catch (Exception e) {
            // 清理操作失败也要记录日志，但不抛出异常
            // 避免影响正常的响应返回
            log.error("清理用户上下文时发生异常, URI: {}", request.getRequestURI(), e);
        }
    }
}
