package com.evops.coldchain.dto;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 季节版本草稿中的一个温区分段。
 * 分段里程 [fromKm, toKm) 必须与相邻段首尾相接（无空档、无重叠），
 * 全部分段从 0 连续覆盖到路线 totalKm。
 */
public class SegmentDraftRequest {
    @NotNull(message = "分段起点公里标不能为空")
    @DecimalMin(value = "0", message = "分段起点不能小于0")
    private BigDecimal fromKm;
    @NotNull(message = "分段终点公里标不能为空")
    @DecimalMin(value = "0", message = "分段终点不能小于0")
    private BigDecimal toKm;
    @NotNull(message = "分段允许温度下限不能为空")
    private BigDecimal tempMin;
    @NotNull(message = "分段允许温度上限不能为空")
    private BigDecimal tempMax;
    @NotNull(message = "缓冲时长不能为空")
    @DecimalMin(value = "0", message = "缓冲时长不能为负")
    private Integer bufferMinutes;
    @NotBlank(message = "分段责任人不能为空")
    private String ownerName;
    private String ownerPhone;
    private String remark;

    public BigDecimal getFromKm() { return fromKm; }
    public void setFromKm(BigDecimal fromKm) { this.fromKm = fromKm; }
    public BigDecimal getToKm() { return toKm; }
    public void setToKm(BigDecimal toKm) { this.toKm = toKm; }
    public BigDecimal getTempMin() { return tempMin; }
    public void setTempMin(BigDecimal tempMin) { this.tempMin = tempMin; }
    public BigDecimal getTempMax() { return tempMax; }
    public void setTempMax(BigDecimal tempMax) { this.tempMax = tempMax; }
    public Integer getBufferMinutes() { return bufferMinutes; }
    public void setBufferMinutes(Integer bufferMinutes) { this.bufferMinutes = bufferMinutes; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public String getOwnerPhone() { return ownerPhone; }
    public void setOwnerPhone(String ownerPhone) { this.ownerPhone = ownerPhone; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
