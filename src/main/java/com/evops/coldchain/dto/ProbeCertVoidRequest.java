package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 校准凭证作废请求（只追加作废标记与原因，不删除证书文件/原行） */
public class ProbeCertVoidRequest {
    @NotNull(message = "凭证ID不能为空")
    private Long certId;
    @NotBlank(message = "作废原因不能为空")
    private String reason;

    public Long getCertId() { return certId; }
    public void setCertId(Long certId) { this.certId = certId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
