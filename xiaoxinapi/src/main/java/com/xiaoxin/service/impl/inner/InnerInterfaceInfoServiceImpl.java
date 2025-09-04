package com.xiaoxin.service.impl.inner;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xiaoxin.mapper.InterfaceInfoMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import model.entity.InterfaceInfo;
import org.apache.commons.lang3.StringUtils;
import org.apache.dubbo.config.annotation.DubboService;
import service.InnerInterfaceInfoService;

/**
 * 内部接口信息服务
 */
@DubboService
@Slf4j
public class InnerInterfaceInfoServiceImpl implements InnerInterfaceInfoService{
    @Resource
    private InterfaceInfoMapper interfaceInfoMapper;

    /**
     * 从数据库中查询接口是否存在（根据平台路径查询，支持动态代理）
     * @param url 平台统一API路径（如：/api/weather/current）
     * @param method HTTP方法
     * @return 接口信息（包含providerUrl真实地址）
     */
    @Override
    public InterfaceInfo getInterfaceInfo(String url, String method) {
        long startTime = System.currentTimeMillis();
        log.info("开始处理请求: url={}, method={}", url, method);

        try {
            if (StringUtils.isAnyBlank(url, method)) {
//                throw new BusinessException(ErrorCode.PARAMS_ERROR);
                return null;
            }

            QueryWrapper<InterfaceInfo> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("url", url);
            queryWrapper.eq("method", method);

            InterfaceInfo result = interfaceInfoMapper.selectOne(queryWrapper);

            long endTime = System.currentTimeMillis();
            log.info("请求处理完成，耗时: {} ms", (endTime - startTime));

            return result;
        } catch (Exception e) {
            log.error("处理请求异常{}", e.getMessage());
            throw e;
        }
    }
}
