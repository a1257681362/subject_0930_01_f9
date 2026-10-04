package com.evops.coldchain.dto;

import javax.validation.constraints.NotNull;

/** 发布路线季节版本草稿 */
public class RoutePublishRequest {
    @NotNull(message = "路线版本ID不能为空")
    private Long versionId;

    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
}
