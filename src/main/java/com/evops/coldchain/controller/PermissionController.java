package com.evops.coldchain.controller;

import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.PermissionChangeRequest;
import com.evops.coldchain.entity.PermissionSetting;
import com.evops.coldchain.service.PermissionService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 冷链药品运输权限开关：收紧后分仓交接停止推进（建单/核验/提交/继续派送），
 * 但已经确认的封签核验事实保留、仍可查询追溯；恢复后可继续推进。
 */
@RestController
@RequestMapping("/api/cold-chain/permissions")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /** 收紧（permitted=false）或恢复（permitted=true）冷链运输权限 */
    @PostMapping
    public ApiResponse<PermissionSetting> change(@Valid @RequestBody PermissionChangeRequest req) {
        return ApiResponse.ok(permissionService.change(req));
    }

    /** 查询冷链分仓交接权限当前状态（无记录默认放行） */
    @GetMapping("/cold-chain-handover")
    public ApiResponse<Map<String, Object>> status() {
        String key = CcConstants.PERM_COLD_CHAIN_HANDOVER;
        PermissionSetting setting = permissionService.get(key);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("permissionKey", key);
        data.put("permitted", permissionService.isPermitted(key));
        data.put("setting", setting);
        return ApiResponse.ok(data);
    }
}
