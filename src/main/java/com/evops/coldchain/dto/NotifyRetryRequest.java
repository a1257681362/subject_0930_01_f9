package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/** 升级通知手动重试请求：必须填写重试原因（如对方未响应、上一次发送失败原因） */
public class NotifyRetryRequest {
    @NotBlank(message = "重试原因不能为空")
    private String retryReason;

    public String getRetryReason() { return retryReason; }
    public void setRetryReason(String retryReason) { this.retryReason = retryReason; }
}
