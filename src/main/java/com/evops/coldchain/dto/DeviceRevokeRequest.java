package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 设备作废请求（V9）：作废后该设备所有报文一律隔离；历史数据与证书原文保留 */
public class DeviceRevokeRequest {
    @NotNull(message = "设备ID不能为空")
    private Long deviceId;
    @NotBlank(message = "作废原因不能为空")
    private String reason;

    public Long getDeviceId() { return deviceId; }
    public void setDeviceId(Long deviceId) { this.deviceId = deviceId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
