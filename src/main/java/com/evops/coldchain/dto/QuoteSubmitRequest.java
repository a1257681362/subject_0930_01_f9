package com.evops.coldchain.dto;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 承运商报价提交/修订请求（V8）。
 *
 * 金额必须拆分：基础运费 baseAmount + 逐项附加费 surcharges + 税率 taxRate；
 * 不含税小计、税额、含税总价全部由服务端统一口径重算，不接受调用方只报一口价。
 * 同一批次同一承运商重复提交即产生修订新版本，旧版本置 SUPERSEDED 且原值保留。
 */
public class QuoteSubmitRequest {
    @NotNull(message = "报价批次ID不能为空")
    private Long quoteBatchId;
    @NotNull(message = "承运商ID不能为空")
    private Long carrierId;
    @NotNull(message = "基础运费不能为空")
    private BigDecimal baseAmount;
    /** 附加费明细（可空=无附加费；有则逐项列出） */
    @Valid
    private List<SurchargeItem> surcharges;
    @NotNull(message = "税率不能为空")
    private BigDecimal taxRate;
    /** 报价有效期截止（不晚于批次报价截止时间） */
    @NotNull(message = "报价有效期不能为空")
    private LocalDateTime validUntil;
    private String quoteRemark;

    public Long getQuoteBatchId() { return quoteBatchId; }
    public void setQuoteBatchId(Long quoteBatchId) { this.quoteBatchId = quoteBatchId; }
    public Long getCarrierId() { return carrierId; }
    public void setCarrierId(Long carrierId) { this.carrierId = carrierId; }
    public BigDecimal getBaseAmount() { return baseAmount; }
    public void setBaseAmount(BigDecimal baseAmount) { this.baseAmount = baseAmount; }
    public List<SurchargeItem> getSurcharges() { return surcharges; }
    public void setSurcharges(List<SurchargeItem> surcharges) { this.surcharges = surcharges; }
    public BigDecimal getTaxRate() { return taxRate; }
    public void setTaxRate(BigDecimal taxRate) { this.taxRate = taxRate; }
    public LocalDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDateTime validUntil) { this.validUntil = validUntil; }
    public String getQuoteRemark() { return quoteRemark; }
    public void setQuoteRemark(String quoteRemark) { this.quoteRemark = quoteRemark; }
}
