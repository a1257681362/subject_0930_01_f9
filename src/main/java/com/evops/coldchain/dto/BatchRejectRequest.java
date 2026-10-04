package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 收货拒签请求（V6）。
 * 拒签本身即“收货拒签”异常客观成立：登记即按 REJECTION 分级确认并启动处置时钟，
 * 批次不得签收闭环、未关闭前不得结算。
 */
public class BatchRejectRequest {
    @NotNull(message = "运输批次ID不能为空")
    private Long batchId;
    /** 拒签人（收货方） */
    @NotBlank(message = "拒签人不能为空")
    private String rejectBy;
    /** 拒签原因（异常描述，必须留下依据） */
    @NotBlank(message = "拒签原因不能为空")
    private String reason;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getRejectBy() { return rejectBy; }
    public void setRejectBy(String rejectBy) { this.rejectBy = rejectBy; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
