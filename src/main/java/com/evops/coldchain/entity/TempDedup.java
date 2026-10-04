package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.time.LocalDateTime;

/**
 * 温度去重登记（V9）：(device_serial, record_time) 唯一约束兜底，
 * 服务端按设备序列和采样时间去重——同一测点只入账一次，只形成一条可用结果。
 */
@TableName("t_cc_temp_dedup")
public class TempDedup extends CreateOnlyEntity {
    /** 设备序列号 */
    private String deviceSerial;
    /** 采样时间 */
    private LocalDateTime recordTime;
    /** 指向唯一入账的温度留痕 */
    private Long logId;
    /** 首次入账的数据批次 */
    private Long ingestBatchId;

    public String getDeviceSerial() { return deviceSerial; }
    public void setDeviceSerial(String deviceSerial) { this.deviceSerial = deviceSerial; }
    public LocalDateTime getRecordTime() { return recordTime; }
    public void setRecordTime(LocalDateTime recordTime) { this.recordTime = recordTime; }
    public Long getLogId() { return logId; }
    public void setLogId(Long logId) { this.logId = logId; }
    public Long getIngestBatchId() { return ingestBatchId; }
    public void setIngestBatchId(Long ingestBatchId) { this.ingestBatchId = ingestBatchId; }
}
