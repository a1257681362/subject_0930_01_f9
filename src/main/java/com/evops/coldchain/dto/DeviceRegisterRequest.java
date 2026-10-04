package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/**
 * 车载温控设备登记请求（V9）：登记即备案设备 X.509 证书（PEM），作为报文验签信任锚。
 */
public class DeviceRegisterRequest {
    @NotBlank(message = "设备序列号不能为空")
    private String deviceSerial;
    private String deviceModel;
    private String firmwareVersion;
    @NotBlank(message = "设备证书（X.509 PEM）不能为空")
    private String certPem;
    /** 可选：登记同时安装到在册车辆 */
    private Long vehicleId;
    private String remark;

    public String getDeviceSerial() { return deviceSerial; }
    public void setDeviceSerial(String deviceSerial) { this.deviceSerial = deviceSerial; }
    public String getDeviceModel() { return deviceModel; }
    public void setDeviceModel(String deviceModel) { this.deviceModel = deviceModel; }
    public String getFirmwareVersion() { return firmwareVersion; }
    public void setFirmwareVersion(String firmwareVersion) { this.firmwareVersion = firmwareVersion; }
    public String getCertPem() { return certPem; }
    public void setCertPem(String certPem) { this.certPem = certPem; }
    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
