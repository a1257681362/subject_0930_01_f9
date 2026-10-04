package com.evops.coldchain.dto;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 车载设备温度报文（V9）。设备断网恢复后可一次批量补传多个采样点。
 *
 * 签名口径（与服务端 {@code DeviceSignatureService} 完全一致）：
 * 对规范化报文串做 SHA256withRSA：
 * <pre>
 *   clientBatchNo + "|" + deviceSerial + "|" +
 *   各测点 "sampleTime,temperature,mileageKm" 以 ';' 连接（里程为空则末段为空）
 * </pre>
 * 签名为 Base64；certPem 为设备 X.509 证书 PEM 原文。验签失败整批进入隔离队列。
 */
public class DeviceTempIngestRequest {
    @NotBlank(message = "设备侧批次号 clientBatchNo 不能为空（断网重发幂等依据）")
    private String clientBatchNo;
    @NotBlank(message = "设备序列号不能为空")
    private String deviceSerial;
    @NotEmpty(message = "本批至少包含一个温度采样点")
    @Valid
    private List<DeviceTempPoint> points;
    @NotBlank(message = "报文签名不能为空")
    private String signature;
    @NotBlank(message = "设备证书不能为空")
    private String certPem;

    public String getClientBatchNo() { return clientBatchNo; }
    public void setClientBatchNo(String clientBatchNo) { this.clientBatchNo = clientBatchNo; }
    public String getDeviceSerial() { return deviceSerial; }
    public void setDeviceSerial(String deviceSerial) { this.deviceSerial = deviceSerial; }
    public List<DeviceTempPoint> getPoints() { return points; }
    public void setPoints(List<DeviceTempPoint> points) { this.points = points; }
    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
    public String getCertPem() { return certPem; }
    public void setCertPem(String certPem) { this.certPem = certPem; }
}
