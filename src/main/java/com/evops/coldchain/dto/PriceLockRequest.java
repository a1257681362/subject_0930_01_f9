package com.evops.coldchain.dto;

import javax.validation.constraints.NotNull;

/**
 * 锁价请求（V8）：在报价批次的多张并行报价中选定唯一一张中选方案。
 * 锁价时再次校验报价未过期；一个报价批次只能锁价一次（数据库唯一约束兜底）。
 */
public class PriceLockRequest {
    @NotNull(message = "报价批次ID不能为空")
    private Long quoteBatchId;
    @NotNull(message = "中选报价单ID不能为空")
    private Long quoteId;
    private String lockRemark;

    public Long getQuoteBatchId() { return quoteBatchId; }
    public void setQuoteBatchId(Long quoteBatchId) { this.quoteBatchId = quoteBatchId; }
    public Long getQuoteId() { return quoteId; }
    public void setQuoteId(Long quoteId) { this.quoteId = quoteId; }
    public String getLockRemark() { return lockRemark; }
    public void setLockRemark(String lockRemark) { this.lockRemark = lockRemark; }
}
