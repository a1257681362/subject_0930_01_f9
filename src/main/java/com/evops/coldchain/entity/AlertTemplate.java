package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 温控预警模板：每个药品类别一份（uk 类别唯一）。
 * 修改模板 = version_no+1 并停旧温区行、新生效温区行；模板行本身随版本演进。
 */
@TableName("t_cc_alert_template")
public class AlertTemplate extends BaseEntity {
    private Long categoryId;
    private Integer versionNo;
    /** 未显式配置的温区是否沿类别树向上继承上级阈值 */
    private Boolean inheritParent;
    private String changeReason;
    /** DRAFT / EFFECTIVE / SUPERSEDED / REVOKED */
    private String status;

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public Boolean getInheritParent() { return inheritParent; }
    public void setInheritParent(Boolean inheritParent) { this.inheritParent = inheritParent; }
    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
