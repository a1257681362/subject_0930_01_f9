package com.evops.coldchain.dto;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 人工补录设备温度（V9）：无设备签名（如设备维修后由运维依据离线记录补录），
 * 必须有 coldchain:device:manual 权限；测点与设备签名上报走同一套
 * “设备序列 + 采样时间”去重与补传确定性对账，补录、补传、重试对同一测点只形成一条可用结果。
 */
public class DeviceManualIngestRequest {
    @NotBlank(message = "设备序列号不能为空")
    private String deviceSerial;
    /** 可空：为空时服务端生成 MANUAL 批次号；传入则按其幂等 */
    private String clientBatchNo;
    @NotEmpty(message = "本批至少包含一个温度采样点")
    @Valid
    private List<DeviceTempPoint> points;

    public String getDeviceSerial() { return deviceSerial; }
    public void setDeviceSerial(String deviceSerial) { this.deviceSerial = deviceSerial; }
    public String getClientBatchNo() { return clientBatchNo; }
    public void setClientBatchNo(String clientBatchNo) { this.clientBatchNo = clientBatchNo; }
    public List<DeviceTempPoint> getPoints() { return points; }
    public void setPoints(List<DeviceTempPoint> points) { this.points = points; }
}
