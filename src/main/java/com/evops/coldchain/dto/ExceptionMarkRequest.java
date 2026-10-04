package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 人工异常补录请求（V6）。
 * 分级四类（温度超限/封签破损/延误/收货拒签）补录后为“待确认(OPEN)”，处置时钟不启动；
 * 需显式确认才定级、按确认时刻起算响应/处置时限。occurredTime 即首次发现时间，
 * 仅在补录落库时写入一次，之后任何补录/确认都不能覆盖它。
 */
public class ExceptionMarkRequest {
    @NotNull(message = "运输批次ID不能为空")
    private Long batchId;
    @NotBlank(message = "异常类型不能为空")
    private String exceptionType; // TEMP_EXCURSION / DAMAGE / DELAY / OTHER
    @NotBlank(message = "异常描述不能为空")
    private String description;
    private LocalDateTime occurredTime;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getExceptionType() { return exceptionType; }
    public void setExceptionType(String exceptionType) { this.exceptionType = exceptionType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getOccurredTime() { return occurredTime; }
    public void setOccurredTime(LocalDateTime occurredTime) { this.occurredTime = occurredTime; }
}
