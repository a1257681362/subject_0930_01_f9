package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 人工改价审批（V8，只追加）。
 *
 * 锁价后不允许直接改金额：发起改价落 PENDING，经审批岗同意后锁价单 final_* 才更新；
 * 每行留存改价前后全部拆项金额与原始中选报价ID，原始报价行/锁价 *_snap 永不覆盖。
 * 驳回的申请原样保留，锁价单继续按原金额执行。
 */
@TableName("t_cc_quote_adjust_approval")
public class QuoteAdjustApproval extends BaseEntity {
    private Long lockedPriceId;
    private Long quoteId;
    private String reason;
    // 改价前金额
    private BigDecimal beforeBaseAmount;
    private BigDecimal beforeSurchargeAmount;
    private BigDecimal beforeSubtotalAmount;
    private BigDecimal beforeTaxRate;
    private BigDecimal beforeTaxAmount;
    private BigDecimal beforeTotalAmount;
    // 申请改后金额
    private BigDecimal afterBaseAmount;
    private BigDecimal afterSurchargeAmount;
    private BigDecimal afterSubtotalAmount;
    private BigDecimal afterTaxRate;
    private BigDecimal afterTaxAmount;
    private BigDecimal afterTotalAmount;
    /** PENDING / APPROVED / REJECTED */
    private String status;
    private Long applyBy;
    private String applyName;
    private LocalDateTime applyTime;
    private Long reviewBy;
    private String reviewName;
    private LocalDateTime reviewTime;
    private String reviewNote;

    public Long getLockedPriceId() { return lockedPriceId; }
    public void setLockedPriceId(Long lockedPriceId) { this.lockedPriceId = lockedPriceId; }
    public Long getQuoteId() { return quoteId; }
    public void setQuoteId(Long quoteId) { this.quoteId = quoteId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public BigDecimal getBeforeBaseAmount() { return beforeBaseAmount; }
    public void setBeforeBaseAmount(BigDecimal beforeBaseAmount) { this.beforeBaseAmount = beforeBaseAmount; }
    public BigDecimal getBeforeSurchargeAmount() { return beforeSurchargeAmount; }
    public void setBeforeSurchargeAmount(BigDecimal beforeSurchargeAmount) { this.beforeSurchargeAmount = beforeSurchargeAmount; }
    public BigDecimal getBeforeSubtotalAmount() { return beforeSubtotalAmount; }
    public void setBeforeSubtotalAmount(BigDecimal beforeSubtotalAmount) { this.beforeSubtotalAmount = beforeSubtotalAmount; }
    public BigDecimal getBeforeTaxRate() { return beforeTaxRate; }
    public void setBeforeTaxRate(BigDecimal beforeTaxRate) { this.beforeTaxRate = beforeTaxRate; }
    public BigDecimal getBeforeTaxAmount() { return beforeTaxAmount; }
    public void setBeforeTaxAmount(BigDecimal beforeTaxAmount) { this.beforeTaxAmount = beforeTaxAmount; }
    public BigDecimal getBeforeTotalAmount() { return beforeTotalAmount; }
    public void setBeforeTotalAmount(BigDecimal beforeTotalAmount) { this.beforeTotalAmount = beforeTotalAmount; }
    public BigDecimal getAfterBaseAmount() { return afterBaseAmount; }
    public void setAfterBaseAmount(BigDecimal afterBaseAmount) { this.afterBaseAmount = afterBaseAmount; }
    public BigDecimal getAfterSurchargeAmount() { return afterSurchargeAmount; }
    public void setAfterSurchargeAmount(BigDecimal afterSurchargeAmount) { this.afterSurchargeAmount = afterSurchargeAmount; }
    public BigDecimal getAfterSubtotalAmount() { return afterSubtotalAmount; }
    public void setAfterSubtotalAmount(BigDecimal afterSubtotalAmount) { this.afterSubtotalAmount = afterSubtotalAmount; }
    public BigDecimal getAfterTaxRate() { return afterTaxRate; }
    public void setAfterTaxRate(BigDecimal afterTaxRate) { this.afterTaxRate = afterTaxRate; }
    public BigDecimal getAfterTaxAmount() { return afterTaxAmount; }
    public void setAfterTaxAmount(BigDecimal afterTaxAmount) { this.afterTaxAmount = afterTaxAmount; }
    public BigDecimal getAfterTotalAmount() { return afterTotalAmount; }
    public void setAfterTotalAmount(BigDecimal afterTotalAmount) { this.afterTotalAmount = afterTotalAmount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getApplyBy() { return applyBy; }
    public void setApplyBy(Long applyBy) { this.applyBy = applyBy; }
    public String getApplyName() { return applyName; }
    public void setApplyName(String applyName) { this.applyName = applyName; }
    public LocalDateTime getApplyTime() { return applyTime; }
    public void setApplyTime(LocalDateTime applyTime) { this.applyTime = applyTime; }
    public Long getReviewBy() { return reviewBy; }
    public void setReviewBy(Long reviewBy) { this.reviewBy = reviewBy; }
    public String getReviewName() { return reviewName; }
    public void setReviewName(String reviewName) { this.reviewName = reviewName; }
    public LocalDateTime getReviewTime() { return reviewTime; }
    public void setReviewTime(LocalDateTime reviewTime) { this.reviewTime = reviewTime; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
}
