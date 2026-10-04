package com.evops.coldchain.dto;

import javax.validation.constraints.NotNull;

/** 设备安装/换绑车辆请求（V9） */
public class DeviceBindRequest {
    @NotNull(message = "设备ID不能为空")
    private Long deviceId;
    @NotNull(message = "车辆ID不能为空")
    private Long vehicleId;

    public Long getDeviceId() { return deviceId; }
    public void setDeviceId(Long deviceId) { this.deviceId = deviceId; }
    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
}
