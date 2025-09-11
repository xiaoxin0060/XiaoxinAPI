package com.xiaoxin.api.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Mock测试接口
 * 提供两个通用测试接口，用于网关功能验证
 */
@RestController
@RequestMapping("/test")
@Slf4j
public class TestController {

    /**
     * GET通用测试接口
     * 用于测试GET请求、参数传递、网关路由等
     */
    @GetMapping("/get")
    public Map<String, Object> testGet(@RequestParam(required = false) String name,
                                       @RequestParam(required = false) String userId,
                                       HttpServletRequest request) {
        log.info("GET测试请求 - name: {}, userId: {}, IP: {}", name, userId, getClientIP(request));
        
        Map<String, Object> result = new HashMap<>();
        result.put("method", "GET");
        result.put("path", request.getRequestURI());
        result.put("name", name != null ? name : "默认用户");
        result.put("userId", userId != null ? userId : "1001");
        result.put("message", "GET请求处理成功");
        result.put("clientIP", getClientIP(request));
        result.put("timestamp", LocalDateTime.now().toString());
        result.put("success", true);
        
        return result;
    }

    /**
     * POST通用测试接口  
     * 用于测试POST请求、JSON处理、数据传输等
     */
    @PostMapping("/post")
    public Map<String, Object> testPost(@RequestBody(required = false) Map<String, Object> data,
                                        HttpServletRequest request) {
        log.info("POST测试请求 - 数据: {}, IP: {}", data, getClientIP(request));
        
        Map<String, Object> result = new HashMap<>();
        result.put("method", "POST");
        result.put("path", request.getRequestURI());
        result.put("receivedData", data != null ? data : new HashMap<>());
        result.put("dataSize", data != null ? data.size() : 0);
        result.put("message", "POST请求处理成功");
        result.put("clientIP", getClientIP(request));
        result.put("timestamp", LocalDateTime.now().toString());
        result.put("success", true);
        
        // 简单处理接收到的数据
        if (data != null && !data.isEmpty()) {
            Map<String, Object> processedData = new HashMap<>();
            data.forEach((key, value) -> {
                if (value instanceof String) {
                    processedData.put(key + "_length", ((String) value).length());
                } else if (value instanceof Number) {
                    processedData.put(key + "_double", ((Number) value).doubleValue() * 2);
                }
            });
            result.put("processedData", processedData);
        }
        
        return result;
    }

    /**
     * 获取客户端真实IP
     */
    private String getClientIP(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
