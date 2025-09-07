package com.xiaoxin.api.platform.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiaoxin.api.platform.common.ErrorCode;
import com.xiaoxin.api.platform.exception.BusinessException;
import com.xiaoxin.api.platform.mapper.InterfaceInfoMapper;
import com.xiaoxin.api.platform.model.entity.InterfaceInfo;
import com.xiaoxin.api.platform.service.InterfaceInfoService;
import com.xiaoxin.api.platform.utils.CryptoUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
* @author 小新
* @description 针对表【interface_info(接口信息)】的数据库操作Service实现
* @createDate 2025-08-27 01:48:04
*/
@Service
public class InterfaceInfoServiceImpl extends ServiceImpl<InterfaceInfoMapper, InterfaceInfo>
    implements InterfaceInfoService{

    @Value("${security.authcfg.master-key:}")
    private String authcfgMasterKey;

    @Override
    public void validInterfaceInfo(InterfaceInfo interfaceInfo, boolean add){
        if (interfaceInfo == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String name = interfaceInfo.getName();
        String description = interfaceInfo.getDescription();
        String url = interfaceInfo.getUrl();
        String providerUrl = interfaceInfo.getProviderUrl();
        String method = interfaceInfo.getMethod();
        String authType = interfaceInfo.getAuthType();

        // 创建时，核心参数必须非空
        if (add) {
            if (StringUtils.isAnyBlank(name, url, providerUrl, method)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "接口名称、平台路径、真实接口地址、请求方法不能为空");
            }
        }
        
        // 验证字段长度和格式
        if (StringUtils.isNotBlank(description) && description.length() > 200) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "描述内容过长，最多200字符");
        }
        
        if (StringUtils.isNotBlank(name) && name.length() > 100) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "接口名称过长，最多100字符");
        }
        
        // 验证URL格式
        if (StringUtils.isNotBlank(url) && !url.startsWith("/")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "平台API路径必须以/开头");
        }
        
        if (StringUtils.isNotBlank(providerUrl) && !providerUrl.matches("^https?://.*")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "真实接口地址必须是有效的HTTP/HTTPS URL");
        }
        
        // 验证请求方法
        if (StringUtils.isNotBlank(method) && !Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD").contains(method.toUpperCase())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的HTTP方法");
        }
        
        // 验证认证类型
        if (StringUtils.isNotBlank(authType) && !Arrays.asList("NONE", "API_KEY", "BASIC", "BEARER").contains(authType)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的认证类型，支持：NONE/API_KEY/BASIC/BEARER");
        }
        
        // 验证超时时间
        Integer timeout = interfaceInfo.getTimeout();
        if (timeout != null && (timeout < 1000 || timeout > 60000)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "超时时间必须在1000-60000毫秒之间");
        }
        
        // 验证重试次数
        Integer retryCount = interfaceInfo.getRetryCount();
        if (retryCount != null && (retryCount < 0 || retryCount > 5)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "重试次数必须在0-5之间");
        }
        
        // 验证速率限制
        Integer rateLimit = interfaceInfo.getRateLimit();
        if (rateLimit != null && (rateLimit < 1 || rateLimit > 10000)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "速率限制必须在1-10000次/分钟之间");
        }
        
        // 为null的字段设置默认值
        if (interfaceInfo.getAuthType() == null) {
            interfaceInfo.setAuthType("NONE");
        }
        if (interfaceInfo.getTimeout() == null) {
            interfaceInfo.setTimeout(30000);
        }
        if (interfaceInfo.getRetryCount() == null) {
            interfaceInfo.setRetryCount(3);
        }
        if (interfaceInfo.getRateLimit() == null) {
            interfaceInfo.setRateLimit(1000);
        }
        if (interfaceInfo.getVersion() == null) {
            interfaceInfo.setVersion("1.0.0");
        }
    }

    @Override
    public boolean save(InterfaceInfo entity) {
        processAuthConfig(entity);
        return super.save(entity);
    }

    @Override
    public boolean updateById(InterfaceInfo entity) {
        processAuthConfig(entity);
        return super.updateById(entity);
    }

    private void processAuthConfig(InterfaceInfo entity) {
        try {
            if (entity == null) return;
            String authType = entity.getAuthType();
            String cfg = entity.getAuthConfig();
            if (StringUtils.isBlank(cfg) || StringUtils.isBlank(authType) || "NONE".equalsIgnoreCase(authType)) {
                return;
            }
            if (StringUtils.isBlank(authcfgMasterKey)) {
                return; // 未配置主密钥则不加密
            }
            if (CryptoUtils.isEncrypted(cfg)) {
                return; // 已加密，跳过
            }
            String aadStr = safeStr(entity.getProviderUrl()) + "|" + safeStr(entity.getUrl()) + "|" + safeStr(entity.getMethod());
            byte[] key = authcfgMasterKey.getBytes(StandardCharsets.UTF_8);
            String enc = CryptoUtils.aesGcmEncryptFromString(key, aadStr.getBytes(StandardCharsets.UTF_8), cfg);
            entity.setAuthConfig(enc);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "认证配置加密失败");
        }
    }

    private String safeStr(String s){
        return s == null ? "" : s;
    }
}




