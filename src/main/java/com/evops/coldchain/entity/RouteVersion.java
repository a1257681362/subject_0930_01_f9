package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 路线季节版本行：同一 (route_id, season) 下 version_no 从 1 递增，只追加新版本行。
 * 状态机：DRAFT（编排中）-> EFFECTIVE（发布生效）-> SUPERSEDED（被同季节新版本热替换）；
 * DRAFT 或从未被批次引用的版本可被撤回为 REVOKED。已被批次固化快照的版本不可撤回。
 */
@TableName("t_cc_route_version")
public class RouteVersion extends BaseEntity {
    private Long routeId;
    /** SPRING / SUMMER / AUTUMN / WINTER / GENERAL */
    private String season;
    private Integer versionNo;
    /** 生效起点（可晚于发布时间，即预约生效） */
    private LocalDateTime effectiveFrom;
    /** 生效终点，空表示长期有效 */
    private LocalDateTime effectiveTo;
    private LocalDateTime publishTime;
    private String changeReason;
    /** DRAFT / EFFECTIVE / SUPERSEDED / REVOKED */
    private String status;

    public Long getRouteId() { return routeId; }
    public void setRouteId(Long routeId) { this.routeId = routeId; }
    public String getSeason() { return season; }
    public void setSeason(String season) { this.season = season; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public LocalDateTime getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDateTime effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public LocalDateTime getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(LocalDateTime effectiveTo) { this.effectiveTo = effectiveTo; }
    public LocalDateTime getPublishTime() { return publishTime; }
    public void setPublishTime(LocalDateTime publishTime) { this.publishTime = publishTime; }
    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
