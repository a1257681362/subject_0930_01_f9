package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 连续超限计时状态：按“批次×温区”（发运时固化的探头即采样点）。
 * 采样点回正常但间隔不超过抖动合并窗时保持 ACTIVE（合并），超过合并窗置 BROKEN。
 * 定时任务重启后据此状态续算，做到不重复开预警、不漏算。
 */
@TableName("t_cc_alert_streak")
public class AlertStreak extends BaseEntity {
    private Long batchId;
    /** 采样点探头（发运时固化在批次上；仅作标签，全程不变） */
    private Long probeId;
    private String zoneCode;
    /** 连续超限起点（抖动合并后保持首个越限采样点时间） */
    private LocalDateTime streakStartTime;
    /** 最近一个越限采样点时间 */
    private LocalDateTime lastExceedTime;
    /** 最近一个采样点时间（正常/越限），用于判断正常间隔是否落在抖动合并窗 */
    private LocalDateTime lastSampleTime;
    /** 连续段内越限采样点数 */
    private Integer exceedCount;
    /** 该连续段已产生的预警ID（产生后置位；重扫不再开第二条；空=尚未达到容忍时长） */
    private Long alertId;
    /** ACTIVE 连续超限中（含抖动合并）/ BROKEN 已被超过合并窗的正常段断开 */
    private String status;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Long getProbeId() { return probeId; }
    public void setProbeId(Long probeId) { this.probeId = probeId; }
    public String getZoneCode() { return zoneCode; }
    public void setZoneCode(String zoneCode) { this.zoneCode = zoneCode; }
    public LocalDateTime getStreakStartTime() { return streakStartTime; }
    public void setStreakStartTime(LocalDateTime streakStartTime) { this.streakStartTime = streakStartTime; }
    public LocalDateTime getLastExceedTime() { return lastExceedTime; }
    public void setLastExceedTime(LocalDateTime lastExceedTime) { this.lastExceedTime = lastExceedTime; }
    public LocalDateTime getLastSampleTime() { return lastSampleTime; }
    public void setLastSampleTime(LocalDateTime lastSampleTime) { this.lastSampleTime = lastSampleTime; }
    public Integer getExceedCount() { return exceedCount; }
    public void setExceedCount(Integer exceedCount) { this.exceedCount = exceedCount; }
    public Long getAlertId() { return alertId; }
    public void setAlertId(Long alertId) { this.alertId = alertId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
