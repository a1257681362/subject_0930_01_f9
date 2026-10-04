package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.PermissionChangeRequest;
import com.evops.coldchain.entity.PermissionSetting;
import com.evops.coldchain.mapper.PermissionSettingMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

/**
 * 业务权限开关服务：以“权限键 + permitted 开/关”表达可被收紧的冷链业务权限。
 *
 * 语义：
 * - 无开关记录时默认放行（permitted=TRUE）。
 * - 冷链药品运输权限（COLD_CHAIN_HANDOVER）被收紧（permitted=FALSE）后，分仓交接的
 *   建单/封签核验/提交、以及批次继续派送（签收闭环）一律停止推进；
 *   但已经确认的封签核验事实不被改写或删除，查询/追溯不受影响。
 * - 每次收紧/恢复都写 t_cc_op_log，留下变更原因与操作者。
 */
@Service
public class PermissionService {

    @Resource
    private PermissionSettingMapper permissionMapper;
    @Resource
    private OpLogService opLogService;

    /** 当前是否放行：无记录视为放行 */
    public boolean isPermitted(String permissionKey) {
        PermissionSetting setting = find(permissionKey);
        return setting == null || !Boolean.FALSE.equals(setting.getPermitted());
    }

    public PermissionSetting get(String permissionKey) {
        return find(permissionKey);
    }

    /**
     * 推进类操作卡口：权限被收紧时直接给出明确停点说明并拒绝。
     * 不影响只读查询与已经确认的封签核验事实。
     */
    public void requirePermitted(String permissionKey) {
        if (!isPermitted(permissionKey)) {
            PermissionSetting setting = find(permissionKey);
            String reason = setting == null ? "" : setting.getChangeReason();
            throw new BusinessException(String.format(
                    "冷链药品运输权限（%s）已被收紧，分仓交接停止推进（不能建单/核验/提交/继续派送）；"
                            + "已确认的封签核验事实保留可查。收紧原因：%s",
                    permissionKey, reason == null ? "" : reason));
        }
    }

    /** 收紧（permitted=FALSE）或恢复（permitted=TRUE）权限，并逐次留痕 */
    @Transactional
    public PermissionSetting change(PermissionChangeRequest req) {
        String key = (req.getPermissionKey() == null || req.getPermissionKey().trim().isEmpty())
                ? CcConstants.PERM_COLD_CHAIN_HANDOVER : req.getPermissionKey().trim();
        boolean permitted = Boolean.TRUE.equals(req.getPermitted());

        PermissionSetting setting = find(key);
        boolean created = setting == null;
        if (created) {
            setting = new PermissionSetting();
            setting.setPermissionKey(key);
        }
        setting.setPermitted(permitted);
        setting.setChangeReason(req.getReason());
        if (created) {
            permissionMapper.insert(setting);
        } else {
            permissionMapper.updateById(setting);
        }

        String action = permitted ? CcConstants.ACT_PERMISSION_RESTORE : CcConstants.ACT_PERMISSION_TIGHTEN;
        opLogService.log(CcConstants.ENT_PERMISSION, setting.getId(), null, action,
                String.format("冷链运输权限 %s %s：%s",
                        key, permitted ? "恢复放行" : "收紧（停止推进，已确认事实保留）", req.getReason()));
        return setting;
    }

    private PermissionSetting find(String permissionKey) {
        return permissionMapper.selectOne(new QueryWrapper<PermissionSetting>()
                .eq("permission_key", permissionKey)
                .last("LIMIT 1"));
    }
}
