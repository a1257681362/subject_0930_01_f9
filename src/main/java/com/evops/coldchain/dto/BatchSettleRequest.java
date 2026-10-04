package com.evops.coldchain.dto;

import javax.validation.constraints.NotNull;

/**
 * 批次结算请求（V6）。
 * 前置：批次已签收闭环，且不存在未关闭异常（待确认/处置中/待复核一律拦截）。
 */
public class BatchSettleRequest {
    @NotNull(message = "运输批次ID不能为空")
    private Long batchId;
    private String settleRemark;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getSettleRemark() { return settleRemark; }
    public void setSettleRemark(String settleRemark) { this.settleRemark = settleRemark; }
}
