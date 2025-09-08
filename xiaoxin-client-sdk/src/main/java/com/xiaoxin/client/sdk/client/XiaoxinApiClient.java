package com.xiaoxin.client.sdk.client;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import com.xiaoxin.api.common.utils.ApiSignUtils;

import java.util.HashMap;
import java.util.Map;

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
public class XiaoxinApiClient {
    
    private final String accessKey;
    private final String secretKey;
    private final String host;

    public XiaoxinApiClient(String accessKey, String secretKey) {
        this(accessKey, secretKey, "http://localhost:9999");
    }

    public XiaoxinApiClient(String accessKey, String secretKey, String host) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.host = (host == null || host.isBlank()) ? "http://localhost:9999" : host;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String accessKey;
        private String secretKey;
        private String host = "http://localhost:9999";

        public Builder accessKey(String ak) { this.accessKey = ak; return this; }
        public Builder secretKey(String sk) { this.secretKey = sk; return this; }
        public Builder host(String host) { this.host = host; return this; }
        public XiaoxinApiClient build() { return new XiaoxinApiClient(accessKey, secretKey, host); }
    }

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
        Map<String, String> headers = createAuthHeaders("", path, "GET"); // GET：空Body
        
        String result = HttpUtil.createGet(host + path)
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
        Map<String, String> headers = createAuthHeaders(jsonBody, path, method);
        
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
        String url = host + path;
        
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
    private Map<String, String> createAuthHeaders(String body, String path, String method) {
        Map<String, String> headers = new HashMap<>();
        headers.put("accessKey", accessKey);
        headers.put("nonce", RandomUtil.randomString(16));
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        headers.put("timestamp", timestamp);
        String contentSha256 = ApiSignUtils.sha256Hex(body);
        headers.put("x-content-sha256", contentSha256);
        headers.put("x-sign-version", "v2");
        String canonical = ApiSignUtils.buildCanonicalString(method, path, contentSha256, timestamp, headers.get("nonce"));
        headers.put("sign", ApiSignUtils.hmacSha256Hex(canonical, secretKey));
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
            if (params instanceof String paramStr) {
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
                String raw = JSONUtil.toJsonStr(params);
                return canonicalizeJson(raw);
            }
        } catch (Exception e) {
            System.err.printf("JSON转换失败，使用容错处理: %s%n", e.getMessage());
            // 容错处理：包装为简单JSON
            Map<String, Object> wrapper = new HashMap<>();
            wrapper.put("value", params.toString());
            return canonicalizeJson(JSONUtil.toJsonStr(wrapper));
        }
    }

    // 固定签名 JSON 规范：对 JSON 对象按照键名字典序递归排序；数组顺序保持不变
    private String canonicalizeJson(String json) {
        if (json == null || json.isBlank()) return "{}";
        try {
            if (JSONUtil.isTypeJSON(json)) {
                Object node = JSONUtil.parse(json);
                Object normalized = normalizeNode(node);
                return JSONUtil.toJsonStr(normalized);
            }
            // 非 JSON 字符串：作为 value 包装
            Map<String, Object> wrapper = new HashMap<>();
            wrapper.put("value", json);
            return JSONUtil.toJsonStr(wrapper);
        } catch (Exception e) {
            return json; // 失败时返回原文，避免中断
        }
    }

    private Object normalizeNode(Object node) {
        if (node instanceof cn.hutool.json.JSONObject obj) {
            java.util.TreeMap<String, Object> sorted = new java.util.TreeMap<>();
            for (String key : obj.keySet()) {
                Object val = obj.get(key);
                sorted.put(key, normalizeNode(val));
            }
            java.util.LinkedHashMap<String, Object> ordered = new java.util.LinkedHashMap<>();
            sorted.forEach(ordered::put);
            return ordered;
        } else if (node instanceof cn.hutool.json.JSONArray arr) {
            java.util.List<Object> list = new java.util.ArrayList<>();
            for (Object v : arr) { list.add(normalizeNode(v)); }
            return list;
        } else {
            return node;
        }
    }
}