package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.math.BigDecimal;

/**
 * 批次路线分段快照：发运选定时点的季节生效版本后，把其全部分段逐段复制到本表。
 * 此后路线版本即便热替换或撤回，本批次的途中温度判定、越限责任追溯一律以快照为准。
 */
@TableName("t_cc_route_segment_snap")
public class RouteSegmentSnap extends CreateOnlyEntity {
    private Long batchId;
    private Integer segmentSeq;
    private BigDecimal fromKm;
    private BigDecimal toKm;
    private BigDecimal tempMin;
    private BigDecimal tempMax;
    private Integer bufferMinutes;
    private String ownerName;
    private String ownerPhone;
    private String remark;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Integer getSegmentSeq() { return segmentSeq; }
    public void setSegmentSeq(Integer segmentSeq) { this.segmentSeq = segmentSeq; }
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
