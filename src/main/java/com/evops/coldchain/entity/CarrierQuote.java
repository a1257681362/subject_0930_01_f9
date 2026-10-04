package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 承运商报价单（V8）。
 *
 * 同一报价批次内一家承运商可多次修订：每次修订追加新版本行（version_no 递增），
 * 旧版本置 SUPERSEDED，原值永不修改。金额固定拆为 基础运费 + 附加费 + 税率，
 * 不含税小计/税额/含税总价由统一口径计算。
 */
@TableName("t_cc_carrier_quote")
public class CarrierQuote extends BaseEntity {
    private Long quoteBatchId;
    private Integer versionNo;
    private Long carrierId;
    private String carrierNameSnap;
    // 报价维度快照（自报价批次复制）
    private Long routeId;
    private String routeNameSnap;
    private String tempZoneCode;
    private String tempZoneName;
    private String boxType;
    private String boxTypeName;
    private String serviceLevel;
    private String serviceLevelName;
    private Integer boxQty;
    // 金额拆分
    private BigDecimal baseAmount;
    private BigDecimal surchargeAmount;
    private BigDecimal subtotalAmount;
    private BigDecimal taxRate;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private String currency;
    private LocalDateTime validUntil;
    private String quoteRemark;
    private Long submittedBy;
    private String submittedByName;
    private LocalDateTime submitTime;
    /** SUBMITTED / SUPERSEDED / LOCKED / REJECTED / EXPIRED */
    private String status;
    private LocalDateTime supersededTime;
    private Long lockedPriceId;

    public Long getQuoteBatchId() { return quoteBatchId; }
    public void setQuoteBatchId(Long quoteBatchId) { this.quoteBatchId = quoteBatchId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public Long getCarrierId() { return carrierId; }
    public void setCarrierId(Long carrierId) { this.carrierId = carrierId; }
    public String getCarrierNameSnap() { return carrierNameSnap; }
    public void setCarrierNameSnap(String carrierNameSnap) { this.carrierNameSnap = carrierNameSnap; }
    public Long getRouteId() { return routeId; }
    public void setRouteId(Long routeId) { this.routeId = routeId; }
    public String getRouteNameSnap() { return routeNameSnap; }
    public void setRouteNameSnap(String routeNameSnap) { this.routeNameSnap = routeNameSnap; }
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
    public BigDecimal getBaseAmount() { return baseAmount; }
    public void setBaseAmount(BigDecimal baseAmount) { this.baseAmount = baseAmount; }
    public BigDecimal getSurchargeAmount() { return surchargeAmount; }
    public void setSurchargeAmount(BigDecimal surchargeAmount) { this.surchargeAmount = surchargeAmount; }
    public BigDecimal getSubtotalAmount() { return subtotalAmount; }
    public void setSubtotalAmount(BigDecimal subtotalAmount) { this.subtotalAmount = subtotalAmount; }
    public BigDecimal getTaxRate() { return taxRate; }
    public void setTaxRate(BigDecimal taxRate) { this.taxRate = taxRate; }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public LocalDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDateTime validUntil) { this.validUntil = validUntil; }
    public String getQuoteRemark() { return quoteRemark; }
    public void setQuoteRemark(String quoteRemark) { this.quoteRemark = quoteRemark; }
    public Long getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(Long submittedBy) { this.submittedBy = submittedBy; }
    public String getSubmittedByName() { return submittedByName; }
    public void setSubmittedByName(String submittedByName) { this.submittedByName = submittedByName; }
    public LocalDateTime getSubmitTime() { return submitTime; }
    public void setSubmitTime(LocalDateTime submitTime) { this.submitTime = submitTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getSupersededTime() { return supersededTime; }
    public void setSupersededTime(LocalDateTime supersededTime) { this.supersededTime = supersededTime; }
    public Long getLockedPriceId() { return lockedPriceId; }
    public void setLockedPriceId(Long lockedPriceId) { this.lockedPriceId = lockedPriceId; }
}
