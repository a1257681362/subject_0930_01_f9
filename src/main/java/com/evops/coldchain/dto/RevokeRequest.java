package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 批次撤回请求：只作作废标记并留痕，不删除任何原始记录 */
public class RevokeRequest {
    @NotNull(message = "运输批次ID不能为空")
    private Long batchId;
    @NotBlank(message = "撤回原因不能为空")
    private String reason;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
