package com.evops.coldchain.dto;

import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设备温度报文中的单个采样点（V9）。
 * sampleTime=设备采样时间（不是服务端接收时间），断网补传时为历史时刻；
 * 同一设备序列 + sampleTime 全局去重。
 */
public class DeviceTempPoint {
    @NotNull(message = "采样时间不能为空")
    private LocalDateTime sampleTime;
    @NotNull(message = "温度值不能为空")
    private BigDecimal temperature;
    /** 当前里程标（公里）：批次有路线分段快照时用于命中分段，无路线批次可空 */
    private BigDecimal mileageKm;

    public LocalDateTime getSampleTime() { return sampleTime; }
    public void setSampleTime(LocalDateTime sampleTime) { this.sampleTime = sampleTime; }
    public BigDecimal getTemperature() { return temperature; }
    public void setTemperature(BigDecimal temperature) { this.temperature = temperature; }
    public BigDecimal getMileageKm() { return mileageKm; }
    public void setMileageKm(BigDecimal mileageKm) { this.mileageKm = mileageKm; }
}
