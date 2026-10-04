package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 锁价单（V8）。
 *
 * 一个报价批次至多一张锁价单（uk_cc_locked_batch 数据库层兜底“只能选一个有效方案”）；
 * 锁价瞬间把中选报价的路线/温区/箱型/服务等级/承运商/金额拆项/有效期逐字段快照，
 * 此后报价修订、过期都与锁价单脱钩。人工改价只更新 final_* 列，*_snap 列永久保留原始报价金额。
 */
@TableName("t_cc_locked_price")
public class LockedPrice extends BaseEntity {
    private String lockedPriceNo;
    private Long quoteBatchId;
    private Long quoteId;
    private Integer quoteVersionNo;
    private Long carrierId;
    private String carrierNameSnap;
    // 报价维度快照
    private Long routeId;
    private String routeNameSnap;
    private String originSnap;
    private String destinationSnap;
    private String tempZoneCodeSnap;
    private String tempZoneNameSnap;
    private String boxTypeSnap;
    private String boxTypeNameSnap;
    private String serviceLevelSnap;
    private String serviceLevelNameSnap;
    private Integer boxQtySnap;
    // 原始报价金额快照（永不覆盖）
    private BigDecimal baseAmountSnap;
    private BigDecimal surchargeAmountSnap;
    private BigDecimal subtotalAmountSnap;
    private BigDecimal taxRateSnap;
    private BigDecimal taxAmountSnap;
    private BigDecimal totalAmountSnap;
    // 生效金额（未改价时=快照；改价审批同意后更新）
    private BigDecimal finalBaseAmount;
    private BigDecimal finalSurchargeAmount;
    private BigDecimal finalSubtotalAmount;
    private BigDecimal finalTaxRate;
    private BigDecimal finalTaxAmount;
    private BigDecimal finalTotalAmount;
    private String currency;
    private LocalDateTime validUntilSnap;
    private LocalDateTime lockTime;
    private Long lockedBy;
    private String lockedByName;
    /** LOCKED / ADJUSTING / VOIDED */
    private String status;
    private String voidReason;
    private LocalDateTime voidTime;
    private Long lastAdjustId;

    public String getLockedPriceNo() { return lockedPriceNo; }
    public void setLockedPriceNo(String lockedPriceNo) { this.lockedPriceNo = lockedPriceNo; }
    public Long getQuoteBatchId() { return quoteBatchId; }
    public void setQuoteBatchId(Long quoteBatchId) { this.quoteBatchId = quoteBatchId; }
    public Long getQuoteId() { return quoteId; }
    public void setQuoteId(Long quoteId) { this.quoteId = quoteId; }
    public Integer getQuoteVersionNo() { return quoteVersionNo; }
    public void setQuoteVersionNo(Integer quoteVersionNo) { this.quoteVersionNo = quoteVersionNo; }
    public Long getCarrierId() { return carrierId; }
    public void setCarrierId(Long carrierId) { this.carrierId = carrierId; }
    public String getCarrierNameSnap() { return carrierNameSnap; }
    public void setCarrierNameSnap(String carrierNameSnap) { this.carrierNameSnap = carrierNameSnap; }
    public Long getRouteId() { return routeId; }
    public void setRouteId(Long routeId) { this.routeId = routeId; }
    public String getRouteNameSnap() { return routeNameSnap; }
    public void setRouteNameSnap(String routeNameSnap) { this.routeNameSnap = routeNameSnap; }
    public String getOriginSnap() { return originSnap; }
    public void setOriginSnap(String originSnap) { this.originSnap = originSnap; }
    public String getDestinationSnap() { return destinationSnap; }
    public void setDestinationSnap(String destinationSnap) { this.destinationSnap = destinationSnap; }
    public String getTempZoneCodeSnap() { return tempZoneCodeSnap; }
    public void setTempZoneCodeSnap(String tempZoneCodeSnap) { this.tempZoneCodeSnap = tempZoneCodeSnap; }
    public String getTempZoneNameSnap() { return tempZoneNameSnap; }
    public void setTempZoneNameSnap(String tempZoneNameSnap) { this.tempZoneNameSnap = tempZoneNameSnap; }
    public String getBoxTypeSnap() { return boxTypeSnap; }
    public void setBoxTypeSnap(String boxTypeSnap) { this.boxTypeSnap = boxTypeSnap; }
    public String getBoxTypeNameSnap() { return boxTypeNameSnap; }
    public void setBoxTypeNameSnap(String boxTypeNameSnap) { this.boxTypeNameSnap = boxTypeNameSnap; }
    public String getServiceLevelSnap() { return serviceLevelSnap; }
    public void setServiceLevelSnap(String serviceLevelSnap) { this.serviceLevelSnap = serviceLevelSnap; }
    public String getServiceLevelNameSnap() { return serviceLevelNameSnap; }
    public void setServiceLevelNameSnap(String serviceLevelNameSnap) { this.serviceLevelNameSnap = serviceLevelNameSnap; }
    public Integer getBoxQtySnap() { return boxQtySnap; }
    public void setBoxQtySnap(Integer boxQtySnap) { this.boxQtySnap = boxQtySnap; }
    public BigDecimal getBaseAmountSnap() { return baseAmountSnap; }
    public void setBaseAmountSnap(BigDecimal baseAmountSnap) { this.baseAmountSnap = baseAmountSnap; }
    public BigDecimal getSurchargeAmountSnap() { return surchargeAmountSnap; }
    public void setSurchargeAmountSnap(BigDecimal surchargeAmountSnap) { this.surchargeAmountSnap = surchargeAmountSnap; }
    public BigDecimal getSubtotalAmountSnap() { return subtotalAmountSnap; }
    public void setSubtotalAmountSnap(BigDecimal subtotalAmountSnap) { this.subtotalAmountSnap = subtotalAmountSnap; }
    public BigDecimal getTaxRateSnap() { return taxRateSnap; }
    public void setTaxRateSnap(BigDecimal taxRateSnap) { this.taxRateSnap = taxRateSnap; }
    public BigDecimal getTaxAmountSnap() { return taxAmountSnap; }
    public void setTaxAmountSnap(BigDecimal taxAmountSnap) { this.taxAmountSnap = taxAmountSnap; }
    public BigDecimal getTotalAmountSnap() { return totalAmountSnap; }
    public void setTotalAmountSnap(BigDecimal totalAmountSnap) { this.totalAmountSnap = totalAmountSnap; }
    public BigDecimal getFinalBaseAmount() { return finalBaseAmount; }
    public void setFinalBaseAmount(BigDecimal finalBaseAmount) { this.finalBaseAmount = finalBaseAmount; }
    public BigDecimal getFinalSurchargeAmount() { return finalSurchargeAmount; }
    public void setFinalSurchargeAmount(BigDecimal finalSurchargeAmount) { this.finalSurchargeAmount = finalSurchargeAmount; }
    public BigDecimal getFinalSubtotalAmount() { return finalSubtotalAmount; }
    public void setFinalSubtotalAmount(BigDecimal finalSubtotalAmount) { this.finalSubtotalAmount = finalSubtotalAmount; }
    public BigDecimal getFinalTaxRate() { return finalTaxRate; }
    public void setFinalTaxRate(BigDecimal finalTaxRate) { this.finalTaxRate = finalTaxRate; }
    public BigDecimal getFinalTaxAmount() { return finalTaxAmount; }
    public void setFinalTaxAmount(BigDecimal finalTaxAmount) { this.finalTaxAmount = finalTaxAmount; }
    public BigDecimal getFinalTotalAmount() { return finalTotalAmount; }
    public void setFinalTotalAmount(BigDecimal finalTotalAmount) { this.finalTotalAmount = finalTotalAmount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public LocalDateTime getValidUntilSnap() { return validUntilSnap; }
    public void setValidUntilSnap(LocalDateTime validUntilSnap) { this.validUntilSnap = validUntilSnap; }
    public LocalDateTime getLockTime() { return lockTime; }
    public void setLockTime(LocalDateTime lockTime) { this.lockTime = lockTime; }
    public Long getLockedBy() { return lockedBy; }
    public void setLockedBy(Long lockedBy) { this.lockedBy = lockedBy; }
    public String getLockedByName() { return lockedByName; }
    public void setLockedByName(String lockedByName) { this.lockedByName = lockedByName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getVoidReason() { return voidReason; }
    public void setVoidReason(String voidReason) { this.voidReason = voidReason; }
    public LocalDateTime getVoidTime() { return voidTime; }
    public void setVoidTime(LocalDateTime voidTime) { this.voidTime = voidTime; }
    public Long getLastAdjustId() { return lastAdjustId; }
    public void setLastAdjustId(Long lastAdjustId) { this.lastAdjustId = lastAdjustId; }
}
