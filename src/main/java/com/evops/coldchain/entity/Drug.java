package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 药品档案主表：一个药品一行，drug_no 稳定不变；current_version 指向当前生效版本。
 */
@TableName("t_cc_drug")
public class Drug extends BaseEntity {
    private String drugNo;
    private String drugName;
    private Integer currentVersion;
    /** ACTIVE / REVOKED */
    private String status;
    /** 药品类别ID（V5）：挂类别后按类别温控预警模板在派车时固化快照 */
    private Long categoryId;
    private String remark;

    public String getDrugNo() { return drugNo; }
    public void setDrugNo(String drugNo) { this.drugNo = drugNo; }
    public String getDrugName() { return drugName; }
    public void setDrugName(String drugName) { this.drugName = drugName; }
    public Integer getCurrentVersion() { return currentVersion; }
    public void setCurrentVersion(Integer currentVersion) { this.currentVersion = currentVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
