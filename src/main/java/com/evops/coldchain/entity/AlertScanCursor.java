package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 温控预警定时任务扫描游标（表内固定一行 id=1）：
 * 已纳入连续超限计算的最大温度留痕ID + 最近扫描时间/操作者。
 * 定时任务重启后据此增量续算；评估本身幂等，游标只用于增量与“重启不重放”。
 */
@TableName("t_cc_alert_scan_cursor")
public class AlertScanCursor {
    @TableId(type = IdType.INPUT)
    private Integer id;
    private Long lastLogId;
    private LocalDateTime lastScanTime;
    private String lastScanBy;
    private Long updateBy;
    private LocalDateTime updateTime;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Long getLastLogId() { return lastLogId; }
    public void setLastLogId(Long lastLogId) { this.lastLogId = lastLogId; }
    public LocalDateTime getLastScanTime() { return lastScanTime; }
    public void setLastScanTime(LocalDateTime lastScanTime) { this.lastScanTime = lastScanTime; }
    public String getLastScanBy() { return lastScanBy; }
    public void setLastScanBy(String lastScanBy) { this.lastScanBy = lastScanBy; }
    public Long getUpdateBy() { return updateBy; }
    public void setUpdateBy(Long updateBy) { this.updateBy = updateBy; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
