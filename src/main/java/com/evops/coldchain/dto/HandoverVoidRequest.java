package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 交接单作废请求：仅 DRAFT 可作废（已提交的责任转移不可撤销）。
 * 作废只置 VOIDED 状态与原因/时间，不删除封签核验明细与操作日志，原始记录全部保留。
 */
public class HandoverVoidRequest {
    @NotNull(message = "交接单ID不能为空")
    private Long handoverId;
    @NotBlank(message = "作废原因不能为空")
    private String reason;

    public Long getHandoverId() { return handoverId; }
    public void setHandoverId(Long handoverId) { this.handoverId = handoverId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
