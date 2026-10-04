package com.evops.coldchain.dto;

import javax.validation.constraints.NotNull;

/** 药品归组到类别的请求（归组后新批次按该类别模板固化预警快照） */
public class DrugAssignCategoryRequest {
    @NotNull(message = "药品类别ID不能为空")
    private Long categoryId;

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
}
