package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 车载探头校准凭证关联请求（车辆绑定/替换探头）。
 * requestId 为客户端幂等键：同 requestId 的重复提交只返回第一次的绑定结果，
 * 不产生第二条绑定行、不改变替换链。
 */
public class ProbeBindRequest {
    @NotBlank(message = "幂等请求号 requestId 不能为空")
    private String requestId;
    @NotNull(message = "车辆ID不能为空")
    private Long vehicleId;
    @NotNull(message = "探头ID不能为空")
    private Long probeId;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public Long getProbeId() { return probeId; }
    public void setProbeId(Long probeId) { this.probeId = probeId; }
}
