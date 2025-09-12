package com.xiaoxin.api.platform.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xiaoxin.api.platform.annotation.AuthCheck;
import com.xiaoxin.api.platform.common.BaseResponse;
import com.xiaoxin.api.platform.common.DeleteRequest;
import com.xiaoxin.api.platform.common.ErrorCode;
import com.xiaoxin.api.platform.common.ResultUtils;
import com.xiaoxin.api.platform.constant.CommonConstant;
import com.xiaoxin.api.platform.context.UserContextHolder;
import com.xiaoxin.api.platform.exception.BusinessException;
import com.xiaoxin.api.platform.model.dto.userInterfaceInfo.UserInterfaceInfoAddQuotaRequest;
import com.xiaoxin.api.platform.model.dto.userInterfaceInfo.UserInterfaceInfoAddRequest;
import com.xiaoxin.api.platform.model.dto.userInterfaceInfo.UserInterfaceInfoQueryRequest;
import com.xiaoxin.api.platform.model.dto.userInterfaceInfo.UserInterfaceInfoUpdateRequest;
import com.xiaoxin.api.platform.model.entity.User;
import com.xiaoxin.api.platform.model.entity.UserInterfaceInfo;
import com.xiaoxin.api.platform.service.UserInterfaceInfoService;
import com.xiaoxin.api.platform.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API接口
 */
@RestController
@RequestMapping("/userInterfaceInfo")
@Slf4j
@Tag(name = "用户接口功能")
public class UserInterfaceInfoController{

    @Resource
    private UserInterfaceInfoService userInterfaceInfoService;

    @Resource
    private UserService userService;

    // region 增删改查

    /**
     * 创建
     *
     * @param userInterfaceInfoAddRequest
     *  
     * @return
     */
    @Operation(summary = "创建")
    @PostMapping("/add")
    public BaseResponse<Long> addUserInterfaceInfo(
            @RequestBody UserInterfaceInfoAddRequest userInterfaceInfoAddRequest){
        if(userInterfaceInfoAddRequest == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        UserInterfaceInfo userInterfaceInfo = new UserInterfaceInfo();
        BeanUtils.copyProperties(userInterfaceInfoAddRequest, userInterfaceInfo);
        // 校验
        userInterfaceInfoService.validUserInterfaceInfo(userInterfaceInfo, true);
        User loginUser = UserContextHolder.requireCurrentUser();
        userInterfaceInfo.setUserId(loginUser.getId());
        boolean result = userInterfaceInfoService.save(userInterfaceInfo);
        if(!result){
            throw new BusinessException(ErrorCode.OPERATION_ERROR);
        }
        long newUserInterfaceInfoId = userInterfaceInfo.getId();
        return ResultUtils.success(newUserInterfaceInfoId);
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
    public BaseResponse<Boolean> deleteUserInterfaceInfo(
            @RequestBody DeleteRequest deleteRequest){
        if(deleteRequest == null || deleteRequest.getId() <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User user = UserContextHolder.requireCurrentUser();
        long id = deleteRequest.getId();
        // 判断是否存在
        UserInterfaceInfo oldUserInterfaceInfo = userInterfaceInfoService.getById(id);
        if(oldUserInterfaceInfo == null){
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        // 仅本人或管理员可删除
        if(!oldUserInterfaceInfo.getUserId().equals(user.getId()) && !UserContextHolder.isCurrentUserAdmin()){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        boolean b = userInterfaceInfoService.removeById(id);
        return ResultUtils.success(b);
    }

    /**
     * 更新
     *
     * @param userInterfaceInfoUpdateRequest
     *  
     * @return
     */
    @Operation(summary = "更新")
    @PostMapping("/update")
    public BaseResponse<Boolean> updateUserInterfaceInfo(
            @RequestBody UserInterfaceInfoUpdateRequest userInterfaceInfoUpdateRequest){
        if(userInterfaceInfoUpdateRequest == null || userInterfaceInfoUpdateRequest.getId() <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        UserInterfaceInfo userInterfaceInfo = new UserInterfaceInfo();
        BeanUtils.copyProperties(userInterfaceInfoUpdateRequest, userInterfaceInfo);
        // 参数校验
        userInterfaceInfoService.validUserInterfaceInfo(userInterfaceInfo, false);
        User user = UserContextHolder.requireCurrentUser();
        long id = userInterfaceInfoUpdateRequest.getId();
        // 判断是否存在
        UserInterfaceInfo oldUserInterfaceInfo = userInterfaceInfoService.getById(id);
        if(oldUserInterfaceInfo == null){
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        // 仅本人或管理员可修改
        if(!oldUserInterfaceInfo.getUserId().equals(user.getId()) && !UserContextHolder.isCurrentUserAdmin()){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        boolean result = userInterfaceInfoService.updateById(userInterfaceInfo);
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
    public BaseResponse<UserInterfaceInfo> getUserInterfaceInfoById(long id){
        if(id <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        UserInterfaceInfo userInterfaceInfo = userInterfaceInfoService.getById(id);
        return ResultUtils.success(userInterfaceInfo);
    }

    /**
     * 获取列表（仅管理员可使用）
     *
     * @param userInterfaceInfoQueryRequest
     * @return
     */
    @Operation(summary = "获取列表")
    @AuthCheck(mustRole = "admin")
    @GetMapping("/list")
    public BaseResponse<List<UserInterfaceInfo>> listUserInterfaceInfo(
            @ParameterObject UserInterfaceInfoQueryRequest userInterfaceInfoQueryRequest){
        UserInterfaceInfo userInterfaceInfoQuery = new UserInterfaceInfo();
        if(userInterfaceInfoQueryRequest != null){
            BeanUtils.copyProperties(userInterfaceInfoQueryRequest, userInterfaceInfoQuery);
        }
        QueryWrapper<UserInterfaceInfo> queryWrapper = new QueryWrapper<>(userInterfaceInfoQuery);
        List<UserInterfaceInfo> userInterfaceInfoList = userInterfaceInfoService.list(queryWrapper);
        return ResultUtils.success(userInterfaceInfoList);
    }

    /**
     * 分页获取列表
     *
     * @param userInterfaceInfoQueryRequest
     *  
     * @return
     */
    @Operation(summary = "分页获取列表")
    @GetMapping("/list/page")
    public BaseResponse<Page<UserInterfaceInfo>> listUserInterfaceInfoByPage(
            @ParameterObject UserInterfaceInfoQueryRequest userInterfaceInfoQueryRequest){
        if(userInterfaceInfoQueryRequest == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        UserInterfaceInfo userInterfaceInfoQuery = new UserInterfaceInfo();
        BeanUtils.copyProperties(userInterfaceInfoQueryRequest, userInterfaceInfoQuery);
        long current = userInterfaceInfoQueryRequest.getCurrent();
        long size = userInterfaceInfoQueryRequest.getPageSize();
        String sortField = userInterfaceInfoQueryRequest.getSortField();
        String sortOrder = userInterfaceInfoQueryRequest.getSortOrder();
        // 限制爬虫
        if(size > 50){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        QueryWrapper<UserInterfaceInfo> queryWrapper = new QueryWrapper<>(userInterfaceInfoQuery);
        queryWrapper.orderBy(StringUtils.isNotBlank(sortField),
                sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                sortField);
        Page<UserInterfaceInfo> userInterfaceInfoPage = userInterfaceInfoService.page(new Page<>(current, size),
                queryWrapper);
        return ResultUtils.success(userInterfaceInfoPage);
    }

    // endregion

    /**
     * 管理员/本人增加接口调用额度
     */
    @Operation(summary = "增加接口调用额度")
    @PostMapping("/addQuota")
    public BaseResponse<Boolean> addQuota(@RequestBody UserInterfaceInfoAddQuotaRequest req) {
        if (req == null || req.getUserId() == null || req.getInterfaceInfoId() == null || req.getAddCount() == null
                || req.getUserId() <= 0 || req.getInterfaceInfoId() <= 0 || req.getAddCount() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = UserContextHolder.requireCurrentUser();
        // 仅本人或管理员可操作
        if (!loginUser.getId().equals(req.getUserId()) && !UserContextHolder.isCurrentUserAdmin()) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        boolean ok = userInterfaceInfoService.addQuota(req.getInterfaceInfoId(), req.getUserId(), req.getAddCount());
        return ResultUtils.success(ok);
    }

}
