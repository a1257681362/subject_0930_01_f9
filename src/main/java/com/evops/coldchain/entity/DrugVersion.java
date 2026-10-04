package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.math.BigDecimal;

/**
 * 药品档案版本行：建档产生 V1，每次变更追加新版本行。
 * 历史版本行永不 UPDATE/DELETE——在途批次永远按其派车时的版本核对。
 */
@TableName("t_cc_drug_version")
public class DrugVersion extends CreateOnlyEntity {
    private Long drugId;
    private Integer versionNo;
    /** 批准文号 */
    private String approvalNo;
    /** 储存温区下限 ℃ */
    private BigDecimal tempMin;
    /** 储存温区上限 ℃ */
    private BigDecimal tempMax;
    /** 包装规格 */
    private String packageSpec;
    private String changeReason;
    /** EFFECTIVE / SUPERSEDED / REVOKED */
    private String status;

    public Long getDrugId() { return drugId; }
    public void setDrugId(Long drugId) { this.drugId = drugId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
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
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
