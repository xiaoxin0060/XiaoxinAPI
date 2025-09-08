package com.xiaoxin.api.platform.config;

import com.xiaoxin.api.platform.interceptor.UserContextInterceptor;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 全局配置
 * 
 * 职责：
 * - 配置跨域策略
 * - 注册拦截器
 * - 其他Web MVC相关配置
 *
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    
    @Resource
    private UserContextInterceptor userContextInterceptor;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 覆盖所有请求
        registry.addMapping("/**")
                // 允许发送 Cookie
                .allowCredentials(true)
                // 放行哪些域名（必须用 patterns，否则 * 会和 allowCredentials 冲突）
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("*");
    }
    
    /**
     * 注册拦截器
     * 
     * 拦截器执行顺序：
     * 1. UserContextInterceptor - 设置用户上下文，优先级最高
     * 2. 其他业务拦截器
     * 
     * 拦截路径说明：
     * - "/**" 拦截所有请求
     * - 不排除任何路径，因为用户上下文设置是非强制的
     * - 允许匿名访问的接口也会经过，但用户信息为null
     * 
     * @param registry 拦截器注册器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(userContextInterceptor)
                .addPathPatterns("/**")  // 拦截所有请求
                .order(1);  // 设置为最高优先级，确保用户上下文最先设置
    }
}
