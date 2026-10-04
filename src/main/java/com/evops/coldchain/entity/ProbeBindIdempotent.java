package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

/**
 * “车载探头校准凭证关联”幂等登记：request_id 唯一。
 * 第一次请求落业务绑定并写本行；同 requestId 的重复请求直接回 bind_id 指向的第一次结果，
 * 不再插入绑定行、不改变替换链。
 */
@TableName("t_cc_probe_bind_request")
public class ProbeBindIdempotent extends CreateOnlyEntity {
    private String requestId;
    private Long vehicleId;
    private Long probeId;
    /** 第一次请求生成的车辆-探头绑定行ID（t_cc_vehicle_probe.id） */
    private Long bindId;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public Long getProbeId() { return probeId; }
    public void setProbeId(Long probeId) { this.probeId = probeId; }
    public Long getBindId() { return bindId; }
    public void setBindId(Long bindId) { this.bindId = bindId; }
}
