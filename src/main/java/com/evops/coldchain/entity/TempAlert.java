package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 温控预警（只追加）：一条连续超限产生一条，唯一键（批次×温区×起点）兜底重启/并发重复。
 * 触发时把模板阈值快照复制进来，此后模板变更不改本预警、预警也不消失；
 * 闭环只追加 resolve* 字段。
 */
@TableName("t_cc_temp_alert")
public class TempAlert extends BaseEntity {
    private Long batchId;
    /** 触发采样点（探头） */
    private Long probeId;
    private String zoneCode;
    private String zoneName;
    /** 触发时采用的模板阈值快照（模板后续变更不影响本预警） */
    private BigDecimal tempMinSnap;
    private BigDecimal tempMaxSnap;
    private Integer tolerateMinutes;
    private Integer jitterGapMinutes;
    /** 连续超限起点（合并抖动后） */
    private LocalDateTime startTime;
    /** 达到容忍时长、产生预警的采样点时间 */
    private LocalDateTime triggerTime;
    private LocalDateTime lastExceedTime;
    /** 触发时连续超限持续分钟 */
    private Long durationMinutes;
    /** 连续段内峰值（最偏离）温度 */
    private BigDecimal peakTemperature;
    private String description;
    /** 阈值来源类别（继承时为上级类别编号） */
    private String sourceCategoryNo;
    /** OPEN / RESOLVED */
    private String status;
    private String resolveNote;
    private Long resolveBy;
    private LocalDateTime resolveTime;
    /**
     * V6 首次发现时间（临界时刻）：连续超限达到容忍时长、温度异常客观成立的那一刻。
     * 温度异常的 foundTime/confirmTime 与本列同一口径，处置时钟自该临界时刻起算。
     * 与 triggerTime 同值落库（保留两列：预警语义=触发，时钟口径=首次发现）。
     */
    private LocalDateTime firstFoundTime;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Long getProbeId() { return probeId; }
    public void setProbeId(Long probeId) { this.probeId = probeId; }
    public String getZoneCode() { return zoneCode; }
    public void setZoneCode(String zoneCode) { this.zoneCode = zoneCode; }
    public String getZoneName() { return zoneName; }
    public void setZoneName(String zoneName) { this.zoneName = zoneName; }
    public BigDecimal getTempMinSnap() { return tempMinSnap; }
    public void setTempMinSnap(BigDecimal tempMinSnap) { this.tempMinSnap = tempMinSnap; }
    public BigDecimal getTempMaxSnap() { return tempMaxSnap; }
    public void setTempMaxSnap(BigDecimal tempMaxSnap) { this.tempMaxSnap = tempMaxSnap; }
    public Integer getTolerateMinutes() { return tolerateMinutes; }
    public void setTolerateMinutes(Integer tolerateMinutes) { this.tolerateMinutes = tolerateMinutes; }
    public Integer getJitterGapMinutes() { return jitterGapMinutes; }
    public void setJitterGapMinutes(Integer jitterGapMinutes) { this.jitterGapMinutes = jitterGapMinutes; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getTriggerTime() { return triggerTime; }
    public void setTriggerTime(LocalDateTime triggerTime) { this.triggerTime = triggerTime; }
    public LocalDateTime getLastExceedTime() { return lastExceedTime; }
    public void setLastExceedTime(LocalDateTime lastExceedTime) { this.lastExceedTime = lastExceedTime; }
    public Long getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Long durationMinutes) { this.durationMinutes = durationMinutes; }
    public BigDecimal getPeakTemperature() { return peakTemperature; }
    public void setPeakTemperature(BigDecimal peakTemperature) { this.peakTemperature = peakTemperature; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSourceCategoryNo() { return sourceCategoryNo; }
    public void setSourceCategoryNo(String sourceCategoryNo) { this.sourceCategoryNo = sourceCategoryNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResolveNote() { return resolveNote; }
    public void setResolveNote(String resolveNote) { this.resolveNote = resolveNote; }
    public Long getResolveBy() { return resolveBy; }
    public void setResolveBy(Long resolveBy) { this.resolveBy = resolveBy; }
    public LocalDateTime getResolveTime() { return resolveTime; }
    public void setResolveTime(LocalDateTime resolveTime) { this.resolveTime = resolveTime; }
    public LocalDateTime getFirstFoundTime() { return firstFoundTime; }
    public void setFirstFoundTime(LocalDateTime firstFoundTime) { this.firstFoundTime = firstFoundTime; }
}
