package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.math.BigDecimal;

/**
 * 报价附加费明细（V8，INSERT-only）。
 * 报价修订 = 新版本报价单 + 新一批明细行，历史明细永不修改/删除。
 * lockedPriceId 非空的行是锁价时逐行复制的快照副本（quoteId 仍指向来源报价版本，永久可追）。
 */
@TableName("t_cc_quote_surcharge")
public class QuoteSurcharge extends CreateOnlyEntity {
    private Long quoteId;
    /** 空=报价原始明细；非空=锁价/改价复制的快照副本（指向锁价单） */
    private Long lockedPriceId;
    /** 空=锁价时原始附加费快照；非空=某次审批同意的改价产生的附加费行 */
    private Long adjustId;
    /** FUEL 燃油 / COLD 制冷 / HANDLING 装卸 / BRIDGE 路桥 / WAIT 等待 / OTHER 其他 */
    private String surchargeType;
    private String typeName;
    private BigDecimal amount;
    private String remark;

    public Long getQuoteId() { return quoteId; }
    public void setQuoteId(Long quoteId) { this.quoteId = quoteId; }
    public Long getLockedPriceId() { return lockedPriceId; }
    public void setLockedPriceId(Long lockedPriceId) { this.lockedPriceId = lockedPriceId; }
    public Long getAdjustId() { return adjustId; }
    public void setAdjustId(Long adjustId) { this.adjustId = adjustId; }
    public String getSurchargeType() { return surchargeType; }
    public void setSurchargeType(String surchargeType) { this.surchargeType = surchargeType; }
    public String getTypeName() { return typeName; }
    public void setTypeName(String typeName) { this.typeName = typeName; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
