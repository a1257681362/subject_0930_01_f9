package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 药品类别树节点：parent_id 为空表示根类别。
 * 类别可改名/移动；其温控预警阈值的“只追加”在模板温区行表上保证。
 */
@TableName("t_cc_drug_category")
public class DrugCategory extends BaseEntity {
    private String categoryNo;
    private String categoryName;
    /** 上级类别ID，空=根类别 */
    private Long parentId;
    /** ACTIVE / REVOKED */
    private String status;
    private String remark;

    public String getCategoryNo() { return categoryNo; }
    public void setCategoryNo(String categoryNo) { this.categoryNo = categoryNo; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
