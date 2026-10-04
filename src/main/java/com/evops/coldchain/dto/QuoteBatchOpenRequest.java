package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.time.LocalDateTime;

/**
 * 发起报价批次（询价）请求（V8）。
 * 报价按 路线/温区/箱型/服务等级 四个维度计算；不指定路线时必须给起终点。
 * quoteExpireTime 为报价截止时刻，过期后该批次报价不能再被引用或锁价。
 */
public class QuoteBatchOpenRequest {
    private Long routeId;
    private String origin;
    private String destination;
    @NotBlank(message = "温区不能为空")
    private String tempZoneCode;
    @NotBlank(message = "箱型不能为空")
    private String boxType;
    @NotBlank(message = "服务等级不能为空")
    private String serviceLevel;
    @NotNull(message = "预计箱量不能为空")
    @Positive(message = "预计箱量必须大于0")
    private Integer boxQty;
    private LocalDateTime planShipTime;
    /** 报价截止时间（必填，且必须晚于当前时刻） */
    @NotNull(message = "报价截止时间不能为空")
    private LocalDateTime quoteExpireTime;
    private LocalDateTime quoteStartTime;
    private String remark;

    public Long getRouteId() { return routeId; }
    public void setRouteId(Long routeId) { this.routeId = routeId; }
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }
    public String getTempZoneCode() { return tempZoneCode; }
    public void setTempZoneCode(String tempZoneCode) { this.tempZoneCode = tempZoneCode; }
    public String getBoxType() { return boxType; }
    public void setBoxType(String boxType) { this.boxType = boxType; }
    public String getServiceLevel() { return serviceLevel; }
    public void setServiceLevel(String serviceLevel) { this.serviceLevel = serviceLevel; }
    public Integer getBoxQty() { return boxQty; }
    public void setBoxQty(Integer boxQty) { this.boxQty = boxQty; }
    public LocalDateTime getPlanShipTime() { return planShipTime; }
    public void setPlanShipTime(LocalDateTime planShipTime) { this.planShipTime = planShipTime; }
    public LocalDateTime getQuoteExpireTime() { return quoteExpireTime; }
    public void setQuoteExpireTime(LocalDateTime quoteExpireTime) { this.quoteExpireTime = quoteExpireTime; }
    public LocalDateTime getQuoteStartTime() { return quoteStartTime; }
    public void setQuoteStartTime(LocalDateTime quoteStartTime) { this.quoteStartTime = quoteStartTime; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
