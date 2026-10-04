package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/** 药品类别建档请求（parentId 为空=根类别） */
public class CategoryCreateRequest {
    @NotBlank(message = "类别名称不能为空")
    private String categoryName;
    /** 上级类别ID，空=根类别 */
    private Long parentId;
    private String remark;

    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
