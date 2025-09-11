package com.xiaoxin.api.platform.interceptor;

import com.xiaoxin.api.platform.common.ErrorCode;
import com.xiaoxin.api.platform.context.UserContextHolder;
import com.xiaoxin.api.platform.exception.BusinessException;
import com.xiaoxin.api.platform.model.entity.User;
import com.xiaoxin.api.platform.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * UserContextInterceptor 单元测试
 * 
 * 测试目标：
 * - 验证拦截器的用户上下文设置逻辑
 * - 验证拦截器的异常处理机制
 * - 验证ThreadLocal的正确清理
 * - 验证与UserService的集成
 * 
 * 技术栈说明：
 * - JUnit 5：现代化的Java测试框架，支持注解驱动和断言
 * - Mockito：Java Mock框架，用于模拟依赖对象
 * - MockitoExtension：Mockito与JUnit 5的集成（纯单元测试）
 * - @Mock：创建Mock对象，模拟外部依赖
 * - @InjectMocks：将Mock对象注入到被测试类中
 * 
 * 性能优势：
 * - 快速启动：无需启动Spring Boot容器和Dubbo服务
 * - 资源节约：不启动数据库、Redis等外部依赖
 * - 测试隔离：使用Mock对象，避免外部服务的影响
 * - CI/CD友好：更快的测试执行速度
 * 
 * Mock技术说明：
 * - when().thenReturn()：定义Mock对象的行为
 * - when().thenThrow()：定义Mock对象抛出异常
 * - verify()：验证Mock对象的方法调用
 * - times()：验证方法调用次数
 * 
 * 日志处理说明：
 * - @Slf4j生成的静态final Logger字段在纯Mockito环境中正常工作
 * - 测试专注于业务逻辑验证，不验证日志输出
 * - 日志异常不会影响测试结果，符合拦截器设计原则
 * 
 * @author xiaoxin
 * @since 1.0.0
 */
@ExtendWith(MockitoExtension.class)
class UserContextInterceptorTest {
    
    @Mock
    private UserService userService;
    
    @Mock
    private HttpServletRequest request;
    
    @Mock
    private HttpServletResponse response;
    
    @Mock
    private Object handler;
    
    @InjectMocks
    private UserContextInterceptor userContextInterceptor;
    
    private User testUser;
    
    /**
     * 测试前准备
     * 
     * 创建测试数据，确保每个测试用例的独立性
     */
    @BeforeEach
    void setUp() {
        // 创建测试用户
        testUser = new User();
        testUser.setId(1L);
        testUser.setUserAccount("testuser");
        testUser.setUserRole("user");
        testUser.setAccessKey("ak_test123456789012345678");
        testUser.setSecretKey("sk_test123456789012345678901234567890123456");
        
        // 确保每个测试开始时ThreadLocal是干净的
        UserContextHolder.clear();
    }
    
    /**
     * 测试后清理
     * 
     * 防止ThreadLocal内存泄漏，确保测试间的隔离
     */
    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }
    
    /**
     * 测试成功获取用户信息的场景
     * 
     * 验证点：
     * - preHandle返回true（继续处理请求）
     * - 用户信息正确设置到ThreadLocal
     * - UserService.getLoginUser被正确调用
     * 
     * Mock技术：
     * - when(userService.getLoginUser(request)).thenReturn(testUser)
     *   模拟UserService返回用户信息
     */
    @Test
    void testPreHandle_WithValidUser_ShouldSetUserContext() throws Exception {
        // Given: UserService返回有效用户
        when(userService.getLoginUser(request)).thenReturn(testUser);
        
        // When: 调用preHandle方法
        boolean result = userContextInterceptor.preHandle(request, response, handler);
        
        // Then: 验证结果和副作用
        assertTrue(result, "preHandle应该返回true继续处理请求");
        
        // 验证用户信息已设置到ThreadLocal
        User contextUser = UserContextHolder.getCurrentUser();
        assertNotNull(contextUser, "用户信息应该被设置到ThreadLocal");
        assertEquals(testUser.getId(), contextUser.getId(), "设置的用户ID应该正确");
        assertEquals(testUser.getUserAccount(), contextUser.getUserAccount(), "设置的用户账号应该正确");
        
        // 验证UserService被正确调用
        verify(userService, times(1)).getLoginUser(request);
    }
    
    /**
     * 测试用户未登录的场景
     * 
     * 验证点：
     * - UserService抛出异常时不阻断请求处理
     * - preHandle仍然返回true
     * - ThreadLocal中没有用户信息
     * - 异常被正确捕获和处理
     * 
     * 业务逻辑：
     * - 拦截器不应该因为用户未登录而阻断请求
     * - 具体的业务方法来决定是否需要登录
     */
    @Test
    void testPreHandle_WithUnauthorizedUser_ShouldContinueRequest() throws Exception {
        // Given: UserService抛出未登录异常
        when(userService.getLoginUser(request))
            .thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));
        
        // When: 调用preHandle方法
        boolean result = userContextInterceptor.preHandle(request, response, handler);
        
        // Then: 验证结果
        assertTrue(result, "即使未登录也应该返回true，让业务方法决定是否需要登录");
        
        // 验证ThreadLocal中没有用户信息
        assertNull(UserContextHolder.getCurrentUser(), "未登录时ThreadLocal中不应该有用户信息");
        
        // 验证UserService被调用
        verify(userService, times(1)).getLoginUser(request);
    }
    
    /**
     * 测试UserService返回null的场景
     * 
     * 验证点：
     * - 当UserService返回null时请求继续处理
     * - ThreadLocal中没有用户信息
     * 
     * 边界情况：
     * - 某些情况下UserService可能返回null而不是抛出异常
     */
    @Test
    void testPreHandle_WithNullUser_ShouldContinueRequest() throws Exception {
        // Given: UserService返回null
        when(userService.getLoginUser(request)).thenReturn(null);
        
        // When: 调用preHandle方法
        boolean result = userContextInterceptor.preHandle(request, response, handler);
        
        // Then: 验证结果
        assertTrue(result, "UserService返回null时也应该继续处理请求");
        assertNull(UserContextHolder.getCurrentUser(), "ThreadLocal中不应该有用户信息");
        
        verify(userService, times(1)).getLoginUser(request);
    }
    
    /**
     * 测试afterCompletion方法的清理功能
     * 
     * 验证点：
     * - 无论请求成功还是失败都会清理ThreadLocal
     * - 清理操作不会抛出异常
     * 
     * 技术要点：
     * - afterCompletion是请求处理的最后阶段
     * - 必须确保ThreadLocal被清理，防止内存泄漏
     */
    @Test
    void testAfterCompletion_ShouldClearUserContext() throws Exception {
        // Given: 设置了用户上下文
        UserContextHolder.setCurrentUser(testUser);
        assertTrue(UserContextHolder.hasCurrentUser(), "预设条件：应该有用户信息");
        
        // When: 调用afterCompletion（模拟请求完成）
        userContextInterceptor.afterCompletion(request, response, handler, null);
        
        // Then: 验证用户上下文被清理
        assertNull(UserContextHolder.getCurrentUser(), "afterCompletion后用户信息应该被清理");
        assertFalse(UserContextHolder.hasCurrentUser(), "afterCompletion后应该没有用户");
    }
    
    /**
     * 测试afterCompletion处理异常的场景
     * 
     * 验证点：
     * - 即使请求处理过程中有异常，也要清理ThreadLocal
     * - afterCompletion方法本身不应该抛出异常
     * 
     * 异常处理：
     * - 最后一个参数ex表示请求处理过程中的异常
     * - 无论是否有异常都要进行清理
     */
    @Test
    void testAfterCompletion_WithException_ShouldStillClearUserContext() throws Exception {
        // Given: 设置了用户上下文，并模拟请求处理异常
        UserContextHolder.setCurrentUser(testUser);
        Exception requestException = new RuntimeException("请求处理异常");
        
        // When: 调用afterCompletion（带异常）
        userContextInterceptor.afterCompletion(request, response, handler, requestException);
        
        // Then: 验证用户上下文仍然被清理
        assertNull(UserContextHolder.getCurrentUser(), "即使有异常也应该清理用户信息");
    }
    
    /**
     * 测试完整的请求生命周期
     * 
     * 验证点：
     * - preHandle设置用户信息
     * - 业务逻辑期间可以获取用户信息
     * - afterCompletion清理用户信息
     * 
     * 集成测试：
     * - 模拟完整的HTTP请求处理流程
     * - 验证拦截器在整个流程中的正确性
     */
    @Test
    void testCompleteRequestLifecycle() throws Exception {
        // Given: UserService配置为返回用户信息
        when(userService.getLoginUser(request)).thenReturn(testUser);
        
        // Step 1: preHandle - 设置用户上下文
        boolean preHandleResult = userContextInterceptor.preHandle(request, response, handler);
        assertTrue(preHandleResult, "preHandle应该返回true");
        assertEquals(testUser.getId(), UserContextHolder.getCurrentUser().getId(), 
                    "preHandle后应该能获取到用户信息");
        
        // Step 2: 模拟业务逻辑执行期间
        User businessUser = UserContextHolder.requireCurrentUser();
        assertEquals(testUser.getId(), businessUser.getId(), 
                    "业务逻辑中应该能获取到用户信息");
        
        // Step 3: afterCompletion - 清理用户上下文
        userContextInterceptor.afterCompletion(request, response, handler, null);
        assertNull(UserContextHolder.getCurrentUser(), 
                  "afterCompletion后用户信息应该被清理");
        
        // 验证UserService只被调用了一次
        verify(userService, times(1)).getLoginUser(request);
    }
    
    /**
     * 测试多次请求的场景
     * 
     * 验证点：
     * - 多个请求之间的用户信息不会相互影响
     * - 每次请求都正确设置和清理用户信息
     * 
     * 模拟场景：
     * - 同一个拦截器实例处理多个不同用户的请求
     * - 验证ThreadLocal的正确隔离
     */
    @Test
    void testMultipleRequests() throws Exception {
        // 创建第二个用户
        User user2 = new User();
        user2.setId(2L);
        user2.setUserAccount("user2");
        user2.setUserRole("admin");
        
        // 第一个请求
        when(userService.getLoginUser(request)).thenReturn(testUser);
        
        userContextInterceptor.preHandle(request, response, handler);
        assertEquals(testUser.getId(), UserContextHolder.getCurrentUser().getId(),
                    "第一个请求应该设置正确的用户");
        
        userContextInterceptor.afterCompletion(request, response, handler, null);
        assertNull(UserContextHolder.getCurrentUser(), "第一个请求完成后应该清理用户信息");
        
        // 第二个请求
        when(userService.getLoginUser(request)).thenReturn(user2);
        
        userContextInterceptor.preHandle(request, response, handler);
        assertEquals(user2.getId(), UserContextHolder.getCurrentUser().getId(),
                    "第二个请求应该设置正确的用户，不受第一个请求影响");
        
        userContextInterceptor.afterCompletion(request, response, handler, null);
        assertNull(UserContextHolder.getCurrentUser(), "第二个请求完成后应该清理用户信息");
        
        // 验证UserService被调用了两次
        verify(userService, times(2)).getLoginUser(request);
    }
    
    /**
     * 测试异常情况下的鲁棒性
     * 
     * 验证点：
     * - UserService抛出任何异常都不应该阻断请求
     * - 拦截器本身不会抛出未处理的异常
     * 
     * 异常类型：
     * - BusinessException：业务异常
     * - RuntimeException：运行时异常
     * - 其他类型的异常
     */
    @Test
    void testRobustnessWithDifferentExceptions() throws Exception {
        // Test 1: BusinessException
        when(userService.getLoginUser(request))
            .thenThrow(new BusinessException(ErrorCode.SYSTEM_ERROR, "业务异常"));
        
        boolean result1 = userContextInterceptor.preHandle(request, response, handler);
        assertTrue(result1, "BusinessException不应该阻断请求");
        
        // 验证第一次调用后ThreadLocal是干净的
        assertNull(UserContextHolder.getCurrentUser(), "BusinessException后不应该有用户信息");
        
        // Test 2: RuntimeException - 重置Mock行为
        reset(userService);
        when(userService.getLoginUser(request))
            .thenThrow(new RuntimeException("运行时异常"));
        
        boolean result2 = userContextInterceptor.preHandle(request, response, handler);
        assertTrue(result2, "RuntimeException不应该阻断请求");
        
        // 验证第二次调用后ThreadLocal是干净的
        assertNull(UserContextHolder.getCurrentUser(), "RuntimeException后不应该有用户信息");
        
        // Test 3: 其他异常 - 重置Mock行为
        reset(userService);
        when(userService.getLoginUser(request))
            .thenThrow(new IllegalStateException("状态异常"));
        
        boolean result3 = userContextInterceptor.preHandle(request, response, handler);
        assertTrue(result3, "任何异常都不应该阻断请求");
        
        // 验证最终ThreadLocal都是干净的
        assertNull(UserContextHolder.getCurrentUser(), "异常情况下不应该有用户信息");
    }
}
