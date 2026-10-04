package com.evops.coldchain.dto;

import javax.validation.Valid;
import java.util.List;

/**
 * 类别温控预警模板维护请求。
 * 每次提交生成新版本：仅携带本类别要“显式覆盖”的温区；未携带的温区在 inheritParent=TRUE
 * 时沿类别树向上继承，inheritParent=FALSE 时视为本类别未配置。
 * 修改只影响此后新建（派车）的批次，历史批次继续用创建时快照。
 */
public class TemplateSaveRequest {
    /** 未显式配置温区是否继承上级，空按 TRUE 处理 */
    private Boolean inheritParent;
    private String changeReason;
    /** 本类别显式覆盖的温区（可多个）；为空表示本类别不覆盖任何温区、全部继承 */
    @Valid
    private List<ZoneDraftRequest> zones;

    public Boolean getInheritParent() { return inheritParent; }
    public void setInheritParent(Boolean inheritParent) { this.inheritParent = inheritParent; }
    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }
    public List<ZoneDraftRequest> getZones() { return zones; }
    public void setZones(List<ZoneDraftRequest> zones) { this.zones = zones; }
}
