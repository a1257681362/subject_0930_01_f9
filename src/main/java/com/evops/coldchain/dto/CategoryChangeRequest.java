package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/** 药品类别变更请求（改名/挂接新上级；不影响已固化模板快照的历史批次） */
public class CategoryChangeRequest {
    @NotBlank(message = "类别名称不能为空")
    private String categoryName;
    /** 上级类别ID，空=挂到根；不能指向自己或自己的后代（防环） */
    private Long parentId;
    private String remark;

    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
