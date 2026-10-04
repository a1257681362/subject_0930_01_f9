package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/** 药品建档请求（产生 V1 版本） */
public class DrugCreateRequest {
    @NotBlank(message = "药品名称不能为空")
    private String drugName;
    @NotBlank(message = "批准文号不能为空")
    private String approvalNo;
    @NotNull(message = "储存温区下限不能为空")
    private BigDecimal tempMin;
    @NotNull(message = "储存温区上限不能为空")
    private BigDecimal tempMax;
    @NotBlank(message = "包装规格不能为空")
    private String packageSpec;
    /** 药品类别ID（可选；挂类别后新批次按类别预警模板在派车时固化快照） */
    private Long categoryId;
    private String remark;

    public String getDrugName() { return drugName; }
    public void setDrugName(String drugName) { this.drugName = drugName; }
    public String getApprovalNo() { return approvalNo; }
    public void setApprovalNo(String approvalNo) { this.approvalNo = approvalNo; }
    public BigDecimal getTempMin() { return tempMin; }
    public void setTempMin(BigDecimal tempMin) { this.tempMin = tempMin; }
    public BigDecimal getTempMax() { return tempMax; }
    public void setTempMax(BigDecimal tempMax) { this.tempMax = tempMax; }
    public String getPackageSpec() { return packageSpec; }
    public void setPackageSpec(String packageSpec) { this.packageSpec = packageSpec; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
