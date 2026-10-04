package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 冷链药品运输权限收紧/恢复请求。
 * permitted=FALSE 即权限被收紧：分仓交接建单/核验/提交与批次继续派送一律停止推进，
 * 但已经确认的封签核验事实原样保留、仍可查询追溯；permitted=TRUE 恢复放行。
 */
public class PermissionChangeRequest {
    /** 权限键（不传默认冷链分仓交接权限 COLD_CHAIN_HANDOVER） */
    private String permissionKey;
    @NotNull(message = "放行标志不能为空（false=收紧，true=放行）")
    private Boolean permitted;
    @NotBlank(message = "权限变更原因不能为空")
    private String reason;

    public String getPermissionKey() { return permissionKey; }
    public void setPermissionKey(String permissionKey) { this.permissionKey = permissionKey; }
    public Boolean getPermitted() { return permitted; }
    public void setPermitted(Boolean permitted) { this.permitted = permitted; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
