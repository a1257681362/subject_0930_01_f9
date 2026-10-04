package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 业务权限开关（一行一个可被收紧的权限键）。
 * permitted=TRUE 放行；permitted=FALSE 表示该权限被收紧——冷链药品运输权限一旦收紧，
 * 分仓交接的建单/核验/提交及批次继续派送停止推进，但已经确认的封签核验事实保留可查。
 */
@TableName("t_cc_permission_setting")
public class PermissionSetting extends BaseEntity {
    private String permissionKey;
    private Boolean permitted;
    private String changeReason;

    public String getPermissionKey() { return permissionKey; }
    public void setPermissionKey(String permissionKey) { this.permissionKey = permissionKey; }
    public Boolean getPermitted() { return permitted; }
    public void setPermitted(Boolean permitted) { this.permitted = permitted; }
    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }
}
