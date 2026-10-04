package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 途中温度留痕：按测温时间追加。
 * 只 INSERT，永不 UPDATE/DELETE；越限由服务层判定并自动追加异常事件。
 */
@TableName("t_cc_temperature_log")
public class TemperatureLog extends CreateOnlyEntity {
    private Long batchId;
    private LocalDateTime recordTime;
    private BigDecimal temperature;
    private Boolean inRange;
    private String recordedBy;
    private String remark;
    /** 上报时点车辆所处里程（有路线分段时必填，用于命中快照分段） */
    private BigDecimal mileageKm;
    /** 命中的批次路线分段序号；无路线批次为空 */
    private Integer segmentSeq;
    /** 采集该测点的探头ID（发运时固化在批次上的探头）；替换探头后历史行不回写 */
    private Long probeId;
    /** V9：数据来源 REALTIME 实时上报 / BACKFILL 设备补传 */
    private String source;
    /** V9：产生本行的设备数据批次ID（实时上报为空） */
    private Long ingestBatchId;
    /** V9：补传保护标记——已审核窗口内的补传点不参与预警评估 */
    private Boolean alertExempt;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public LocalDateTime getRecordTime() { return recordTime; }
    public void setRecordTime(LocalDateTime recordTime) { this.recordTime = recordTime; }
    public BigDecimal getTemperature() { return temperature; }
    public void setTemperature(BigDecimal temperature) { this.temperature = temperature; }
    public Boolean getInRange() { return inRange; }
    public void setInRange(Boolean inRange) { this.inRange = inRange; }
    public String getRecordedBy() { return recordedBy; }
    public void setRecordedBy(String recordedBy) { this.recordedBy = recordedBy; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public BigDecimal getMileageKm() { return mileageKm; }
    public void setMileageKm(BigDecimal mileageKm) { this.mileageKm = mileageKm; }
    public Integer getSegmentSeq() { return segmentSeq; }
    public void setSegmentSeq(Integer segmentSeq) { this.segmentSeq = segmentSeq; }
    public Long getProbeId() { return probeId; }
    public void setProbeId(Long probeId) { this.probeId = probeId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Long getIngestBatchId() { return ingestBatchId; }
    public void setIngestBatchId(Long ingestBatchId) { this.ingestBatchId = ingestBatchId; }
    public Boolean getAlertExempt() { return alertExempt; }
    public void setAlertExempt(Boolean alertExempt) { this.alertExempt = alertExempt; }
}
