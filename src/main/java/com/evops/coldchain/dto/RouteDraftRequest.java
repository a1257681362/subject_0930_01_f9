package com.evops.coldchain.dto;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 路线季节版本草稿请求：为某路线的某季节编排一个新版本（或覆盖尚未发布的草稿）。
 * 分段必须相邻、连续覆盖全程，不允许空档/重叠（校验在服务层完成）。
 */
public class RouteDraftRequest {
    /** 季节：SPRING/SUMMER/AUTUMN/WINTER/GENERAL；不传按 GENERAL 通用配置处理 */
    private String season;
    /** 生效起点；不传表示发布即生效 */
    private LocalDateTime effectiveFrom;
    /** 生效终点；不传表示长期有效 */
    private LocalDateTime effectiveTo;
    private String changeReason;
    @NotEmpty(message = "至少配置一个温区分段")
    @Valid
    private List<SegmentDraftRequest> segments;

    public String getSeason() { return season; }
    public void setSeason(String season) { this.season = season; }
    public LocalDateTime getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDateTime effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public LocalDateTime getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(LocalDateTime effectiveTo) { this.effectiveTo = effectiveTo; }
    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }
    public List<SegmentDraftRequest> getSegments() { return segments; }
    public void setSegments(List<SegmentDraftRequest> segments) { this.segments = segments; }
}
