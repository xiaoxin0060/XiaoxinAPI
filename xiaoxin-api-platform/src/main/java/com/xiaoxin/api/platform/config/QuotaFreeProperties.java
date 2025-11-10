package com.xiaoxin.api.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 免费配额配置属性
 * 
 * 功能说明：
 * - 为新用户提供免费接口调用配额
 * - 首次调用时自动创建配额记录（懒创建）
 * - 支持接口白名单控制和开关配置
 * 
 * 面试友好特性：
 * - 面试官注册后无需手动开通即可直接调用接口
 * - 按需创建，不浪费数据库存储空间
 * - 灵活配置，可控制赠送策略
 * 
 * @author xiaoxin
 */
@Component
@ConfigurationProperties(prefix = "xiaoxin.platform.quota.free")
@Data
public class QuotaFreeProperties {
    
    /**
     * 是否启用免费配额功能
     * 
     * true: 新用户首次调用接口时自动赠送配额
     * false: 关闭免费配额，用户需手动开通
     */
    private boolean enabled = true;
    
    /**
     * 免费配额数量
     * 
     * 新用户每个接口的免费调用次数
     * 建议值：50-100次，足够面试官测试体验
     */
    private int leftNum = 50;
    
    /**
     * 接口白名单
     * 
     * 空列表：对所有已上线接口生效
     * 非空：仅对指定接口ID生效
     * 
     * 使用场景：
     * - 仅对核心展示接口赠送配额
     * - 控制免费额度的接口范围
     */
    private List<Long> interfaceIds = new ArrayList<>();
    
    /**
     * 判断指定接口是否允许免费配额
     * 
     * @param interfaceId 接口ID
     * @return true-允许赠送免费配额，false-不允许
     */
    public boolean isInterfaceAllowed(Long interfaceId) {
        // 空白名单表示对所有接口生效
        return interfaceIds.isEmpty() || interfaceIds.contains(interfaceId);
    }
    
    /**
     * 判断是否应该创建免费配额
     * 
     * @param interfaceId 接口ID
     * @return true-应该创建，false-不应该创建
     */
    public boolean shouldCreateFreeQuota(Long interfaceId) {
        return enabled && isInterfaceAllowed(interfaceId);
    }
}
