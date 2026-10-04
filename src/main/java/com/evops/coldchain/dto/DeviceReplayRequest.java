package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/**
 * 重放隔离数据请求（V9）。
 */
public class DeviceReplayRequest {
    @NotBlank(message = "重放原因不能为空")
    private String replayReason;

    public String getReplayReason() { return replayReason; }
    public void setReplayReason(String replayReason) { this.replayReason = replayReason; }
}
