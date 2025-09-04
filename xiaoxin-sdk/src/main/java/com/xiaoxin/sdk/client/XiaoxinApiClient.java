package com.xiaoxin.sdk.client;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;

import java.util.HashMap;
import java.util.Map;

import static com.xiaoxin.sdk.utils.SignUtils.genSign;

/**
 * 小新API客户端 - 企业级统一接口调用SDK
 * 
 * 核心特性：
 * - 统一JSON参数格式，前端传入什么格式就是什么格式
 * - 智能HTTP方法适配：GET自动转Query参数，POST自动转JSON Body
 * - 完整的认证和签名机制
 * - 企业级错误处理和日志
 * 
 * @author xiaoxin
 */
@AllArgsConstructor
public class XiaoxinApiClient {
    
    private final String accessKey;
    private final String secretKey;
    
    private static final String GATEWAY_HOST = "http://localhost:9999";

    /**
     * 统一接口调用方法
     * 
     * 使用示例：
     * <pre>
     * // GET请求：JSON对象自动转换为Query参数
     * Map&lt;String, Object&gt; params = Map.of("city", "北京", "date", "2024-01-01");
     * client.invokeInterface("/api/weather", "GET", params);
     * // 实际请求：GET /api/weather?city=北京&date=2024-01-01
     * 
     * // POST请求：JSON对象自动转换为JSON Body  
     * Map&lt;String, Object&gt; userData = Map.of("name", "张三", "age", 25);
     * client.invokeInterface("/api/users", "POST", userData);
     * // 实际请求：POST /api/users Body: {"name":"张三","age":25}
     * </pre>
     * 
     * @param path 接口路径，如：/api/weather, /api/users
     * @param method HTTP方法：GET, POST, PUT, DELETE（大小写不敏感）
     * @param requestParams 请求参数，支持：Map对象、JSON字符串、基础类型、null
     * @return 接口响应结果（JSON字符串）
     * @throws RuntimeException 当接口调用失败时抛出，包含详细错误信息
     */
    public String invokeInterface(String path, String method, Object requestParams) {
        try {
            if ("GET".equalsIgnoreCase(method)) {
                return handleGetRequest(path, requestParams);
            } else {
                return handleNonGetRequest(path, method, requestParams);
            }
        } catch (Exception e) {
            String errorMsg = String.format("接口调用失败 [%s %s]: %s", method, path, e.getMessage());
            System.err.println(errorMsg);
            throw new RuntimeException(errorMsg, e);
        }
    }

    /**
     * 处理GET请求 - 将参数转换为URL查询参数
     */
    private String handleGetRequest(String path, Object requestParams) {
        Map<String, Object> queryParams = convertToQueryParams(requestParams);
        Map<String, String> headers = createAuthHeaders(""); // GET请求body为空
        
        String result = HttpUtil.createGet(GATEWAY_HOST + path)
                .addHeaders(headers)
                .form(queryParams)
                .execute()
                .body();
                
        System.out.printf("GET %s -> 成功 (%d字符)%n", path, result.length());
        return result;
    }
    
    /**
     * 处理POST/PUT/DELETE请求 - 将参数转换为JSON Body
     */
    private String handleNonGetRequest(String path, String method, Object requestParams) {
        String jsonBody = convertToJsonBody(requestParams);
        Map<String, String> headers = createAuthHeaders(jsonBody);
        
        HttpRequest request = createHttpRequest(method, path);
        
        try (HttpResponse response = request.addHeaders(headers).body(jsonBody).execute()) {
            String result = response.body();
            System.out.printf("%s %s -> 成功 (%d字符)%n", method, path, result.length());
            return result;
        }
    }
    
    /**
     * 创建HTTP请求对象
     */
    private HttpRequest createHttpRequest(String method, String path) {
        String url = GATEWAY_HOST + path;
        
        return switch (method.toUpperCase()) {
            case "POST" -> HttpRequest.post(url);
            case "PUT" -> HttpRequest.put(url);
            case "DELETE" -> HttpRequest.delete(url);
            default -> throw new IllegalArgumentException("不支持的HTTP方法: " + method);
        };
    }
    
    /**
     * 创建认证请求头
     */
    private Map<String, String> createAuthHeaders(String body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("accessKey", accessKey);
        headers.put("nonce", RandomUtil.randomNumbers(4));
        headers.put("body", body);
        headers.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        headers.put("sign", genSign(body, secretKey));
        return headers;
    }
    
    /**
     * 智能参数转换器 - 将任意类型参数转换为Query参数Map
     * 
     * 支持的参数格式：
     * - Map对象: {name: "张三", age: 25} -> name=张三&age=25
     * - JSON字符串: '{"name":"张三","age":25}' -> name=张三&age=25  
     * - Query字符串: "name=张三&age=25" -> name=张三&age=25
     * - 基础类型: "张三" -> value=张三
     * - null: 空参数
     */
    private Map<String, Object> convertToQueryParams(Object params) {
        if (params == null) {
            return new HashMap<>();
        }
        
        try {
            if (params instanceof Map) {
                // Map对象直接使用 - 最高效的方式
                @SuppressWarnings("unchecked")
                Map<String, Object> paramMap = (Map<String, Object>) params;
                return new HashMap<>(paramMap); // 防御性拷贝
                
            } else if (params instanceof String) {
                // 字符串参数智能解析
                return parseStringToQueryParams((String) params);
                
            } else {
                // 其他类型对象：序列化为JSON后解析
                String jsonStr = JSONUtil.toJsonStr(params);
                @SuppressWarnings("unchecked")
                Map<String, Object> jsonMap = JSONUtil.toBean(jsonStr, HashMap.class);
                return jsonMap;
            }
        } catch (Exception e) {
            System.err.printf("参数转换失败，使用默认处理: %s%n", e.getMessage());
            // 容错处理：作为单个值
            Map<String, Object> fallbackMap = new HashMap<>();
            fallbackMap.put("value", params.toString());
            return fallbackMap;
        }
    }
    
    /**
     * 字符串参数智能解析器
     */
    private Map<String, Object> parseStringToQueryParams(String params) {
        Map<String, Object> paramMap = new HashMap<>();
        
        if (params.trim().isEmpty()) {
            return paramMap;
        }
        
        params = params.trim();
        
        if (JSONUtil.isTypeJSON(params)) {
            // JSON格式：{"name":"张三","age":25}
            @SuppressWarnings("unchecked")
            Map<String, Object> jsonMap = JSONUtil.toBean(params, HashMap.class);
            return jsonMap;
            
        } else if (params.contains("=")) {
            // Query字符串格式：name=张三&age=25
            String[] pairs = params.split("&");
            for (String pair : pairs) {
                String[] keyValue = pair.split("=", 2);
                if (keyValue.length == 2) {
                    paramMap.put(keyValue[0].trim(), keyValue[1].trim());
                } else if (keyValue.length == 1) {
                    paramMap.put(keyValue[0].trim(), "");
                }
            }
            return paramMap;
            
        } else {
            // 单个值：作为"value"参数
            paramMap.put("value", params);
            return paramMap;
        }
    }
    
    /**
     * 智能JSON Body转换器
     * 
     * 确保输出标准JSON格式，供POST/PUT/DELETE请求使用
     */
    private String convertToJsonBody(Object params) {
        if (params == null) {
            return "{}";
        }
        
        try {
            if (params instanceof String) {
                String paramStr = (String) params;
                if (JSONUtil.isTypeJSON(paramStr)) {
                    // 已经是JSON格式，直接使用
                    return paramStr;
                } else {
                    // 普通字符串，包装为JSON对象
                    Map<String, Object> wrapper = new HashMap<>();
                    wrapper.put("value", paramStr);
                    return JSONUtil.toJsonStr(wrapper);
                }
            } else {
                // 对象类型，直接序列化为JSON
                return JSONUtil.toJsonStr(params);
            }
        } catch (Exception e) {
            System.err.printf("JSON转换失败，使用容错处理: %s%n", e.getMessage());
            // 容错处理：包装为简单JSON
            Map<String, Object> wrapper = new HashMap<>();
            wrapper.put("value", params.toString());
            return JSONUtil.toJsonStr(wrapper);
        }
    }
}