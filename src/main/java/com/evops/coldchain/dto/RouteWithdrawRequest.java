package com.evops.coldchain.dto;

import javax.validation.constraints.NotNull;

/** 撤回路线季节版本（只允许撤回尚未生效、未被批次引用的版本） */
public class RouteWithdrawRequest {
    @NotNull(message = "路线版本ID不能为空")
    private Long versionId;
    private String reason;

    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
