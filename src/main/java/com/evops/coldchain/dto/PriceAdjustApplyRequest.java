package com.evops.coldchain.dto;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/**
 * 人工改价申请（V8）。
 * 锁价后金额不能直接改，只能走审批：提交后停留 PENDING，审批同意后 final_* 才生效；
 * 申请单留存改价前后全部金额与原始报价，原始报价快照永不覆盖。
 * 改后金额仍按 基础运费 + 附加费 + 税率 的拆项口径由服务端重算校验。
 */
public class PriceAdjustApplyRequest {
    @NotNull(message = "锁价单ID不能为空")
    private Long lockedPriceId;
    @NotBlank(message = "改价原因不能为空")
    private String reason;
    @NotNull(message = "改后基础运费不能为空")
    private BigDecimal newBaseAmount;
    /** 改后附加费明细（可空=无附加费；逐项列出，服务端汇总） */
    @Valid
    private List<SurchargeItem> newSurcharges;
    @NotNull(message = "改后税率不能为空")
    private BigDecimal newTaxRate;

    public Long getLockedPriceId() { return lockedPriceId; }
    public void setLockedPriceId(Long lockedPriceId) { this.lockedPriceId = lockedPriceId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public BigDecimal getNewBaseAmount() { return newBaseAmount; }
    public void setNewBaseAmount(BigDecimal newBaseAmount) { this.newBaseAmount = newBaseAmount; }
    public List<SurchargeItem> getNewSurcharges() { return newSurcharges; }
    public void setNewSurcharges(List<SurchargeItem> newSurcharges) { this.newSurcharges = newSurcharges; }
    public BigDecimal getNewTaxRate() { return newTaxRate; }
    public void setNewTaxRate(BigDecimal newTaxRate) { this.newTaxRate = newTaxRate; }
}
