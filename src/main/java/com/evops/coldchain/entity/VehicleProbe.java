package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 车辆-探头绑定行（只追加）。
 * released_time 为空 = 当前生效绑定，每车至多一条；替换探头时旧绑定置 released_time
 * 并写 replaced_by_bind_id，新行指向新探头——前后关系双向可查，旧行永不删除。
 * 绑定时把凭证编号/摘要/有效期/量程/校准机构/序列号逐字段快照，后续凭证变化不回溯本行。
 */
@TableName("t_cc_vehicle_probe")
public class VehicleProbe extends CreateOnlyEntity {
    private Long vehicleId;
    private Long probeId;
    private LocalDateTime bindTime;
    private LocalDateTime validUntilSnap;
    private Long certId;
    private String certNoSnap;
    private String sha256Snap;
    private BigDecimal rangeMinSnap;
    private BigDecimal rangeMaxSnap;
    private String calibrateOrgSnap;
    private String serialNoSnap;
    /** 空=当前生效绑定；非空=已被替换/解除的时间 */
    private LocalDateTime releasedTime;
    /** 被哪条新绑定替换（前 -> 后） */
    private Long replacedByBindId;

    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public Long getProbeId() { return probeId; }
    public void setProbeId(Long probeId) { this.probeId = probeId; }
    public LocalDateTime getBindTime() { return bindTime; }
    public void setBindTime(LocalDateTime bindTime) { this.bindTime = bindTime; }
    public LocalDateTime getValidUntilSnap() { return validUntilSnap; }
    public void setValidUntilSnap(LocalDateTime validUntilSnap) { this.validUntilSnap = validUntilSnap; }
    public Long getCertId() { return certId; }
    public void setCertId(Long certId) { this.certId = certId; }
    public String getCertNoSnap() { return certNoSnap; }
    public void setCertNoSnap(String certNoSnap) { this.certNoSnap = certNoSnap; }
    public String getSha256Snap() { return sha256Snap; }
    public void setSha256Snap(String sha256Snap) { this.sha256Snap = sha256Snap; }
    public BigDecimal getRangeMinSnap() { return rangeMinSnap; }
    public void setRangeMinSnap(BigDecimal rangeMinSnap) { this.rangeMinSnap = rangeMinSnap; }
    public BigDecimal getRangeMaxSnap() { return rangeMaxSnap; }
    public void setRangeMaxSnap(BigDecimal rangeMaxSnap) { this.rangeMaxSnap = rangeMaxSnap; }
    public String getCalibrateOrgSnap() { return calibrateOrgSnap; }
    public void setCalibrateOrgSnap(String calibrateOrgSnap) { this.calibrateOrgSnap = calibrateOrgSnap; }
    public String getSerialNoSnap() { return serialNoSnap; }
    public void setSerialNoSnap(String serialNoSnap) { this.serialNoSnap = serialNoSnap; }
    public LocalDateTime getReleasedTime() { return releasedTime; }
    public void setReleasedTime(LocalDateTime releasedTime) { this.releasedTime = releasedTime; }
    public Long getReplacedByBindId() { return replacedByBindId; }
    public void setReplacedByBindId(Long replacedByBindId) { this.replacedByBindId = replacedByBindId; }
}
