package com.xiaoxin.api.platform.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaoxin.api.platform.annotation.AuthCheck;
import com.xiaoxin.api.platform.common.*;
import com.xiaoxin.api.platform.constant.CommonConstant;
import com.xiaoxin.api.platform.context.UserContextHolder;
import com.xiaoxin.api.platform.exception.BusinessException;
import com.xiaoxin.api.platform.model.dto.interfaceinfo.InterfaceInfoAddRequest;
import com.xiaoxin.api.platform.model.dto.interfaceinfo.InterfaceInfoInvokeRequest;
import com.xiaoxin.api.platform.model.dto.interfaceinfo.InterfaceInfoQueryRequest;
import com.xiaoxin.api.platform.model.dto.interfaceinfo.InterfaceInfoUpdateRequest;
import com.xiaoxin.api.platform.model.entity.InterfaceInfo;
import com.xiaoxin.api.platform.model.entity.User;
import com.xiaoxin.api.platform.model.enums.InterfaceInfoStatusEnum;
import com.xiaoxin.api.platform.service.InterfaceInfoService;
import com.xiaoxin.api.platform.service.UserService;
import com.xiaoxin.api.platform.utils.CryptoUtils;
import com.xiaoxin.client.sdk.client.XiaoxinApiClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API接口
 */
@RestController
@RequestMapping("/interfaceInfo")
@Slf4j
@Tag(name = "API功能")
public class InterfaceInfoController{

    @Resource
    private InterfaceInfoService interfaceInfoService;

    @Resource
    private UserService userService;

    @Resource
    private ObjectMapper objectMapper;

    @Value("${security.authcfg.master-key:}")
    private String authcfgMasterKey;

    // region 增删改查

    /**
     * 创建
     *
     * @param interfaceInfoAddRequest
     *
     * @return
     */
    @Operation(summary = "创建")
    @PostMapping("/add")
    public BaseResponse<Long> addInterfaceInfo(
            @RequestBody InterfaceInfoAddRequest interfaceInfoAddRequest){
        if(interfaceInfoAddRequest == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        InterfaceInfo interfaceInfo = new InterfaceInfo();
        BeanUtils.copyProperties(interfaceInfoAddRequest, interfaceInfo);
        // 校验
        interfaceInfoService.validInterfaceInfo(interfaceInfo, true);
        User loginUser = UserContextHolder.requireCurrentUser();
        interfaceInfo.setUserId(loginUser.getId());
        boolean result = interfaceInfoService.save(interfaceInfo);
        if(!result){
            throw new BusinessException(ErrorCode.OPERATION_ERROR);
        }
        long newInterfaceInfoId = interfaceInfo.getId();
        return ResultUtils.success(newInterfaceInfoId);
    }

    /**
     * 删除
     *
     * @param deleteRequest
     *
     * @return
     */
    @Operation(summary = "删除")
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteInterfaceInfo(
            @RequestBody DeleteRequest deleteRequest){
        if(deleteRequest == null || deleteRequest.getId() <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User user = UserContextHolder.requireCurrentUser();
        long id = deleteRequest.getId();
        // 判断是否存在
        InterfaceInfo oldInterfaceInfo = interfaceInfoService.getById(id);
        if(oldInterfaceInfo == null){
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        // 仅本人或管理员可删除
        if(!oldInterfaceInfo.getUserId().equals(user.getId()) && !UserContextHolder.isCurrentUserAdmin()){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        boolean b = interfaceInfoService.removeById(id);
        return ResultUtils.success(b);
    }

    /**
     * 更新
     *
     * @param interfaceInfoUpdateRequest
     *
     * @return
     */
    @Operation(summary = "更新")
    @PostMapping("/update")
    public BaseResponse<Boolean> updateInterfaceInfo(
            @RequestBody InterfaceInfoUpdateRequest interfaceInfoUpdateRequest){
        if(interfaceInfoUpdateRequest == null || interfaceInfoUpdateRequest.getId() <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        InterfaceInfo interfaceInfo = new InterfaceInfo();
        BeanUtils.copyProperties(interfaceInfoUpdateRequest, interfaceInfo);
        // 参数校验
        interfaceInfoService.validInterfaceInfo(interfaceInfo, false);
        User user = UserContextHolder.requireCurrentUser();
        long id = interfaceInfoUpdateRequest.getId();
        // 判断是否存在
        InterfaceInfo oldInterfaceInfo = interfaceInfoService.getById(id);
        if(oldInterfaceInfo == null){
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        // 仅本人或管理员可修改
        if(!oldInterfaceInfo.getUserId().equals(user.getId()) && !UserContextHolder.isCurrentUserAdmin()){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        boolean result = interfaceInfoService.updateById(interfaceInfo);
        return ResultUtils.success(result);
    }

    /**
     * 根据 id 获取
     *
     * @param id
     * @return
     */
    @Operation(summary = "根据 id 获取")
    @GetMapping("/get")
    public BaseResponse<InterfaceInfo> getInterfaceInfoById(long id){
        if(id <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        InterfaceInfo interfaceInfo = interfaceInfoService.getById(id);
        return ResultUtils.success(interfaceInfo);
    }

    /**
     * 根据 id 获取（含解密后的认证配置，仅管理员）
     */
    @Operation(summary = "根据 id 获取（含解密认证配置）")
    @AuthCheck(mustRole = "admin")
    @GetMapping("/get/decrypted")
    public BaseResponse<InterfaceInfo> getInterfaceInfoDecrypted(long id){
        if(id <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        InterfaceInfo info = interfaceInfoService.getById(id);
        if (info == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        try {
            String cfg = info.getAuthConfig();
            String authType = info.getAuthType();
            if (authcfgMasterKey != null && !authcfgMasterKey.isBlank()
                    && cfg != null && !"NONE".equalsIgnoreCase(authType)
                    && CryptoUtils.isEncrypted(cfg)) {
                String aad = safeStr(info.getProviderUrl()) + "|" + safeStr(info.getUrl()) + "|" + safeStr(info.getMethod());
                String plain = CryptoUtils.aesGcmDecryptToString(authcfgMasterKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        aad.getBytes(java.nio.charset.StandardCharsets.UTF_8), cfg);
                info.setAuthConfig(plain);
            }
        } catch (Exception e) {
            log.warn("解密认证配置失败: {}", e.getMessage());
        }
        return ResultUtils.success(info);
    }

    private String safeStr(String s){
        return s == null ? "" : s;
    }

    /**
     * 获取列表（仅管理员可使用）
     *
     * @param interfaceInfoQueryRequest
     * @return
     */
    @Operation(summary = "获取列表")
    @AuthCheck(mustRole = "admin")
    @GetMapping("/list")
    public BaseResponse<List<InterfaceInfo>> listInterfaceInfo(
            @ParameterObject InterfaceInfoQueryRequest interfaceInfoQueryRequest){
        InterfaceInfo interfaceInfoQuery = new InterfaceInfo();
        if(interfaceInfoQueryRequest != null){
            BeanUtils.copyProperties(interfaceInfoQueryRequest, interfaceInfoQuery);
        }
        QueryWrapper<InterfaceInfo> queryWrapper = new QueryWrapper<>(interfaceInfoQuery);
        List<InterfaceInfo> interfaceInfoList = interfaceInfoService.list(queryWrapper);
        return ResultUtils.success(interfaceInfoList);
    }

    /**
     * 分页获取列表
     *
     * @param interfaceInfoQueryRequest
     *
     * @return
     */
    @Operation(summary = "分页获取列表")
    @GetMapping("/list/page")
    public BaseResponse<Page<InterfaceInfo>> listInterfaceInfoByPage(
            @ParameterObject InterfaceInfoQueryRequest interfaceInfoQueryRequest){
        if(interfaceInfoQueryRequest == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        InterfaceInfo interfaceInfoQuery = new InterfaceInfo();
        BeanUtils.copyProperties(interfaceInfoQueryRequest, interfaceInfoQuery);
        long current = interfaceInfoQueryRequest.getCurrent();
        long size = interfaceInfoQueryRequest.getPageSize();
        String sortField = interfaceInfoQueryRequest.getSortField();
        String sortOrder = interfaceInfoQueryRequest.getSortOrder();
        String description = interfaceInfoQuery.getDescription();
        // description 需支持模糊搜索
        interfaceInfoQuery.setDescription(null);
        // 限制爬虫
        if(size > 50){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        QueryWrapper<InterfaceInfo> queryWrapper = new QueryWrapper<>(interfaceInfoQuery);
        queryWrapper.like(StringUtils.isNotBlank(description), "description", description);
        queryWrapper.orderBy(StringUtils.isNotBlank(sortField),
                sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                sortField);
        Page<InterfaceInfo> interfaceInfoPage = interfaceInfoService.page(new Page<>(current, size), queryWrapper);
        return ResultUtils.success(interfaceInfoPage);
    }

    /**
     * 接口发布
     *
     * @param idRequest
     *
     * @return
     */
    @Operation(summary = "接口发布")
    @PostMapping("/online")
    @AuthCheck(mustRole = "admin")
    public BaseResponse<Boolean> onlineInterfaceInfo(@RequestBody IdRequest idRequest) {
        if (idRequest == null || idRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        long id = idRequest.getId();
        // 判断是否存在
        InterfaceInfo oldInterfaceInfo = interfaceInfoService.getById(id);
        if (oldInterfaceInfo == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        // 简单验证接口配置的完整性，不进行实际调用测试
        // 实际的接口测试应该由管理员在前端手动进行，或通过专门的测试接口
        if (StringUtils.isAnyBlank(oldInterfaceInfo.getUrl(), oldInterfaceInfo.getMethod(), oldInterfaceInfo.getProviderUrl())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "接口配置不完整，无法发布");
        }
        log.info("接口配置验证通过: {} {} -> {}", oldInterfaceInfo.getMethod(), oldInterfaceInfo.getUrl(), oldInterfaceInfo.getProviderUrl());
        // 仅本人或管理员可修改
        InterfaceInfo interfaceInfo = new InterfaceInfo();
        interfaceInfo.setId(id);
        interfaceInfo.setStatus(InterfaceInfoStatusEnum.ONLINE.getValue());
        boolean result = interfaceInfoService.updateById(interfaceInfo);
        return ResultUtils.success(result);
    }

    /**
     * 下线
     *
     * @param idRequest
     *
     * @return
     */
    @Operation(summary = "接口下线")
    @PostMapping("/offline")
    @AuthCheck(mustRole = "admin")
    public BaseResponse<Boolean> offlineInterfaceInfo(@RequestBody IdRequest idRequest) {
        if (idRequest == null || idRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        long id = idRequest.getId();
        // 判断是否存在
        InterfaceInfo oldInterfaceInfo = interfaceInfoService.getById(id);
        if (oldInterfaceInfo == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        // 仅本人或管理员可修改
        InterfaceInfo interfaceInfo = new InterfaceInfo();
        interfaceInfo.setId(id);
        interfaceInfo.setStatus(InterfaceInfoStatusEnum.OFFLINE.getValue());
        boolean result = interfaceInfoService.updateById(interfaceInfo);
        return ResultUtils.success(result);
    }

    /**
     * 接口调用
     *
     * @param interfaceInfoInvokeRequest
     *
     * @return
     */
    @Operation(summary = "接口调用")
    @PostMapping("/invoke")
    public ResponseEntity<String> invokeInterfaceInfo(@RequestBody InterfaceInfoInvokeRequest interfaceInfoInvokeRequest) {
        if (interfaceInfoInvokeRequest == null || interfaceInfoInvokeRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        long id = interfaceInfoInvokeRequest.getId();
        Object userRequestParamsObj = interfaceInfoInvokeRequest.getUserRequestParams();
        
        // 判断是否存在
        InterfaceInfo oldInterfaceInfo = interfaceInfoService.getById(id);
        if (oldInterfaceInfo == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        if (oldInterfaceInfo.getStatus() == InterfaceInfoStatusEnum.OFFLINE.getValue()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "接口已关闭");
        }
        User loginUser = UserContextHolder.requireCurrentUser();
        String accessKey = loginUser.getAccessKey();
        String secretKey = loginUser.getSecretKey();
        XiaoxinApiClient tempClient = new XiaoxinApiClient(accessKey, secretKey);
        
        // 调用真实接口 - 使用企业级智能参数转换
        try {
            String result = tempClient.invokeInterface(
                oldInterfaceInfo.getUrl(),           // 平台路径：/api/geo/query
                oldInterfaceInfo.getMethod(),        // HTTP方法：GET/POST
                userRequestParamsObj                 // 用户参数：支持JSON对象自动转换
            );
            // 格式化响应，提升可读性
            String formattedResult = formatApiResponse(result);
            
            // 返回格式化后的响应
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(formattedResult);
        } catch (Exception e) {
            log.error("接口调用失败", e);
            // 构建错误响应，保持与网关统一的格式
            String errorResponse = String.format(
                "{\"code\":500,\"data\":null,\"message\":\"接口调用失败: %s\",\"timestamp\":%d}",
                e.getMessage().replace("\"", "\\\""), System.currentTimeMillis()
            );
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(errorResponse);
        }
    }

    /**
     * 格式化API响应，优化JSON字符串的可读性
     * @param rawResponse 原始响应字符串
     * @return 格式化后的响应
     */
    private String formatApiResponse(String rawResponse) {
        try {
            // 解析响应为JsonNode
            var responseNode = objectMapper.readTree(rawResponse);
            
            // 检查是否有data字段包含转义的JSON字符串
            if (responseNode.has("data") && responseNode.get("data").has("data")) {
                var dataNode = responseNode.get("data").get("data");
                if (dataNode.isTextual()) {
                    String dataString = dataNode.asText();
                    // 尝试解析data字段中的JSON字符串
                    try {
                        var parsedData = objectMapper.readTree(dataString);
                        // 替换原来的字符串为解析后的JSON对象
                        ((com.fasterxml.jackson.databind.node.ObjectNode) responseNode.get("data"))
                            .set("data", parsedData);
                    } catch (Exception e) {
                        // 如果解析失败，保持原样
                        log.debug("数据字段不是有效的JSON，保持原格式: {}", dataString);
                    }
                }
            }
            
            // 检查headers中的Body字段
            if (responseNode.has("data") && responseNode.get("data").has("headers") 
                && responseNode.get("data").get("headers").has("Body")) {
                var bodyNode = responseNode.get("data").get("headers").get("Body");
                if (bodyNode.isTextual()) {
                    String bodyString = bodyNode.asText();
                    try {
                        var parsedBody = objectMapper.readTree(bodyString);
                        // 替换原来的字符串为解析后的JSON对象
                        ((com.fasterxml.jackson.databind.node.ObjectNode) responseNode.get("data").get("headers"))
                            .set("Body", parsedBody);
                    } catch (Exception e) {
                        // 如果解析失败，保持原样
                        log.debug("Body字段不是有效的JSON，保持原格式: {}", bodyString);
                    }
                }
            }
            
            // 返回格式化后的JSON字符串
            return objectMapper.writeValueAsString(responseNode);
            
        } catch (Exception e) {
            log.warn("响应格式化失败，返回原始响应: {}", e.getMessage());
            return rawResponse;
        }
    }

}
