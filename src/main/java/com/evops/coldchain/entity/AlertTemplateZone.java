package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;

/**
 * 模板温区行（只追加）：每个温区给预警阈值、连续超限容忍时长与短暂抖动合并窗。
 * 修改某温区 = 旧行置 SUPERSEDED + 插入新生效行，历史行原值永不修改。
 */
@TableName("t_cc_alert_template_zone")
public class AlertTemplateZone extends BaseEntity {
    private Long templateId;
    /** 温区代码，如 REFRIGERATE */
    private String zoneCode;
    private String zoneName;
    private BigDecimal tempMin;
    private BigDecimal tempMax;
    /** 连续超限容忍时长（分钟）：持续超过它才产生预警 */
    private Integer tolerateMinutes;
    /** 短暂抖动合并窗（分钟）：回正常不超过它则连续超限合并不断开 */
    private Integer jitterGapMinutes;
    /** EFFECTIVE / SUPERSEDED */
    private String status;

    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getZoneCode() { return zoneCode; }
    public void setZoneCode(String zoneCode) { this.zoneCode = zoneCode; }
    public String getZoneName() { return zoneName; }
    public void setZoneName(String zoneName) { this.zoneName = zoneName; }
    public BigDecimal getTempMin() { return tempMin; }
    public void setTempMin(BigDecimal tempMin) { this.tempMin = tempMin; }
    public BigDecimal getTempMax() { return tempMax; }
    public void setTempMax(BigDecimal tempMax) { this.tempMax = tempMax; }
    public Integer getTolerateMinutes() { return tolerateMinutes; }
    public void setTolerateMinutes(Integer tolerateMinutes) { this.tolerateMinutes = tolerateMinutes; }
    public Integer getJitterGapMinutes() { return jitterGapMinutes; }
    public void setJitterGapMinutes(Integer jitterGapMinutes) { this.jitterGapMinutes = jitterGapMinutes; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
