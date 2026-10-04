package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

/** 模板单个温区的阈值配置（本类别“在该温区显式覆盖”上级阈值） */
public class ZoneDraftRequest {
    @NotBlank(message = "温区代码不能为空")
    private String zoneCode;
    @NotBlank(message = "温区名称不能为空")
    private String zoneName;
    @NotNull(message = "温区下限不能为空")
    private BigDecimal tempMin;
    @NotNull(message = "温区上限不能为空")
    private BigDecimal tempMax;
    /** 连续超限容忍时长（分钟）：持续超过它才预警 */
    @NotNull(message = "连续超限容忍时长不能为空")
    @PositiveOrZero(message = "容忍时长不能为负")
    private Integer tolerateMinutes;
    /** 短暂抖动合并窗（分钟）：回正常不超过它则连续超限合并 */
    @PositiveOrZero(message = "抖动合并窗不能为负")
    private Integer jitterGapMinutes = 0;

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
}
