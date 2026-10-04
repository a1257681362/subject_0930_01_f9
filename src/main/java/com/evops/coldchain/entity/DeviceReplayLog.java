package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.time.LocalDateTime;

/**
 * 重放审计（V9）：每次重放独立一行，只追加。
 * 记录操作者、时间、结果、入账点数——重放全过程写入审计记录。
 */
@TableName("t_cc_device_replay_log")
public class DeviceReplayLog extends CreateOnlyEntity {
    /** 关联隔离队列条目 */
    private Long quarantineId;
    /** 第几次重放 */
    private Integer replaySeq;
    /** 操作者账号 */
    private String replayBy;
    /** 重放时间 */
    private LocalDateTime replayTime;
    /** SUCCESS / FAILED */
    private String result;
    /** 结果说明 */
    private String resultDetail;
    /** 本次入账点数 */
    private Integer pointsPosted;

    public Long getQuarantineId() { return quarantineId; }
    public void setQuarantineId(Long quarantineId) { this.quarantineId = quarantineId; }
    public Integer getReplaySeq() { return replaySeq; }
    public void setReplaySeq(Integer replaySeq) { this.replaySeq = replaySeq; }
    public String getReplayBy() { return replayBy; }
    public void setReplayBy(String replayBy) { this.replayBy = replayBy; }
    public LocalDateTime getReplayTime() { return replayTime; }
    public void setReplayTime(LocalDateTime replayTime) { this.replayTime = replayTime; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getResultDetail() { return resultDetail; }
    public void setResultDetail(String resultDetail) { this.resultDetail = resultDetail; }
    public Integer getPointsPosted() { return pointsPosted; }
    public void setPointsPosted(Integer pointsPosted) { this.pointsPosted = pointsPosted; }
}
