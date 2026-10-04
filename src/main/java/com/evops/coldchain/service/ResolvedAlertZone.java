package com.evops.coldchain.service;

import com.evops.coldchain.entity.AlertTemplateZone;
import com.evops.coldchain.entity.DrugCategory;

/**
 * 类别链解析后的生效温区阈值：本类别覆盖或沿 parent 继承的最终值。
 * inherited 标明该温区是否来自上级、sourceCategory 标明阈值实际来源类别。
 */
public class ResolvedAlertZone {
    private final AlertTemplateZone zone;
    private final boolean inherited;
    private final DrugCategory sourceCategory;

    public ResolvedAlertZone(AlertTemplateZone zone, boolean inherited, DrugCategory sourceCategory) {
        this.zone = zone;
        this.inherited = inherited;
        this.sourceCategory = sourceCategory;
    }

    public AlertTemplateZone getZone() { return zone; }
    public boolean isInherited() { return inherited; }
    public DrugCategory getSourceCategory() { return sourceCategory; }
}
