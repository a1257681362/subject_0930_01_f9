package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.math.BigDecimal;

/**
 * 路线温区分段行：属于某个季节版本，随版本只追加、永不修改/删除。
 * 分段按里程 [fromKm, toKm) 相邻排列：必须从 0 连续覆盖到路线 totalKm，
 * 不允许空档或重叠；每段固化允许温区、越限缓冲时长与责任人。
 */
@TableName("t_cc_route_segment")
public class RouteSegment extends CreateOnlyEntity {
    private Long versionId;
    private Integer segmentSeq;
    private BigDecimal fromKm;
    private BigDecimal toKm;
    private BigDecimal tempMin;
    private BigDecimal tempMax;
    /** 越限缓冲时长（分钟）：在某段内连续越限不超过该时长不开异常 */
    private Integer bufferMinutes;
    private String ownerName;
    private String ownerPhone;
    private String remark;

    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
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
