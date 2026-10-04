package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 附加费明细项（V8）：报价/改价时逐项提交，金额必须拆出附加费，禁止一口价。
 * typeName 可不传，服务端按 surchargeType 补标准名称。
 */
public class SurchargeItem {
    @NotBlank(message = "附加费类型不能为空")
    private String surchargeType;
    private String typeName;
    @NotNull(message = "附加费金额不能为空")
    private BigDecimal amount;
    private String remark;

    public String getSurchargeType() { return surchargeType; }
    public void setSurchargeType(String surchargeType) { this.surchargeType = surchargeType; }
    public String getTypeName() { return typeName; }
    public void setTypeName(String typeName) { this.typeName = typeName; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
