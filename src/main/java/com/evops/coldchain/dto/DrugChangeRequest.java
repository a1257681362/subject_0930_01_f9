package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 药品档案变更请求：以“全量新值”追加一个新版本，
 * 旧版本行的批准文号/温区/包装永不改变。
 */
public class DrugChangeRequest {
    @NotBlank(message = "批准文号不能为空")
    private String approvalNo;
    @NotNull(message = "储存温区下限不能为空")
    private BigDecimal tempMin;
    @NotNull(message = "储存温区上限不能为空")
    private BigDecimal tempMax;
    @NotBlank(message = "包装规格不能为空")
    private String packageSpec;
    @NotBlank(message = "变更原因不能为空")
    private String changeReason;

    public String getApprovalNo() { return approvalNo; }
    public void setApprovalNo(String approvalNo) { this.approvalNo = approvalNo; }
    public BigDecimal getTempMin() { return tempMin; }
    public void setTempMin(BigDecimal tempMin) { this.tempMin = tempMin; }
    public BigDecimal getTempMax() { return tempMax; }
    public void setTempMax(BigDecimal tempMax) { this.tempMax = tempMax; }
    public String getPackageSpec() { return packageSpec; }
    public void setPackageSpec(String packageSpec) { this.packageSpec = packageSpec; }
    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }
}
