package com.evops.coldchain.dto;

import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 途中温度上报请求，按测温时间留痕 */
public class TempReportRequest {
    @NotNull(message = "运输批次ID不能为空")
    private Long batchId;
    @NotNull(message = "测温时间不能为空")
    private LocalDateTime recordTime;
    @NotNull(message = "温度值不能为空")
    private BigDecimal temperature;
    private String recordedBy;
    private String remark;
    /** 当前里程标（公里）：批次有路线分段时必填，用于命中分段温区与缓冲判定 */
    private BigDecimal mileageKm;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public LocalDateTime getRecordTime() { return recordTime; }
    public void setRecordTime(LocalDateTime recordTime) { this.recordTime = recordTime; }
    public BigDecimal getTemperature() { return temperature; }
    public void setTemperature(BigDecimal temperature) { this.temperature = temperature; }
    public String getRecordedBy() { return recordedBy; }
    public void setRecordedBy(String recordedBy) { this.recordedBy = recordedBy; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public BigDecimal getMileageKm() { return mileageKm; }
    public void setMileageKm(BigDecimal mileageKm) { this.mileageKm = mileageKm; }
}
