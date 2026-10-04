package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 报价批次（V8）：一次询价比选。
 * 固化报价维度（路线/温区/箱型/服务等级/箱量），多家承运商在 OPEN 期间并行报价；
 * 截止时间过后报价过期（EXPIRED），不能再被新批次引用或锁价。
 */
@TableName("t_cc_quote_batch")
public class QuoteBatch extends BaseEntity {
    private String quoteBatchNo;
    private Long routeId;
    private String routeNameSnap;
    private String origin;
    private String destination;
    private String tempZoneCode;
    private String tempZoneName;
    private String boxType;
    private String boxTypeName;
    private String serviceLevel;
    private String serviceLevelName;
    private Integer boxQty;
    private LocalDateTime planShipTime;
    private LocalDateTime quoteStartTime;
    private LocalDateTime quoteExpireTime;
    /** OPEN / CLOSED / EXPIRED */
    private String status;
    private LocalDateTime closeTime;
    private String remark;

    public String getQuoteBatchNo() { return quoteBatchNo; }
    public void setQuoteBatchNo(String quoteBatchNo) { this.quoteBatchNo = quoteBatchNo; }
    public Long getRouteId() { return routeId; }
    public void setRouteId(Long routeId) { this.routeId = routeId; }
    public String getRouteNameSnap() { return routeNameSnap; }
    public void setRouteNameSnap(String routeNameSnap) { this.routeNameSnap = routeNameSnap; }
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }
    public String getTempZoneCode() { return tempZoneCode; }
    public void setTempZoneCode(String tempZoneCode) { this.tempZoneCode = tempZoneCode; }
    public String getTempZoneName() { return tempZoneName; }
    public void setTempZoneName(String tempZoneName) { this.tempZoneName = tempZoneName; }
    public String getBoxType() { return boxType; }
    public void setBoxType(String boxType) { this.boxType = boxType; }
    public String getBoxTypeName() { return boxTypeName; }
    public void setBoxTypeName(String boxTypeName) { this.boxTypeName = boxTypeName; }
    public String getServiceLevel() { return serviceLevel; }
    public void setServiceLevel(String serviceLevel) { this.serviceLevel = serviceLevel; }
    public String getServiceLevelName() { return serviceLevelName; }
    public void setServiceLevelName(String serviceLevelName) { this.serviceLevelName = serviceLevelName; }
    public Integer getBoxQty() { return boxQty; }
    public void setBoxQty(Integer boxQty) { this.boxQty = boxQty; }
    public LocalDateTime getPlanShipTime() { return planShipTime; }
    public void setPlanShipTime(LocalDateTime planShipTime) { this.planShipTime = planShipTime; }
    public LocalDateTime getQuoteStartTime() { return quoteStartTime; }
    public void setQuoteStartTime(LocalDateTime quoteStartTime) { this.quoteStartTime = quoteStartTime; }
    public LocalDateTime getQuoteExpireTime() { return quoteExpireTime; }
    public void setQuoteExpireTime(LocalDateTime quoteExpireTime) { this.quoteExpireTime = quoteExpireTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCloseTime() { return closeTime; }
    public void setCloseTime(LocalDateTime closeTime) { this.closeTime = closeTime; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
