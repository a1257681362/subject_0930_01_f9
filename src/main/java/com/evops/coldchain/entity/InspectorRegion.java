package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

/**
 * 监管人员监管区域授权（V7）：账号 -> 监管区域，可多区。
 * region_code='*' 表示全域（仅内置管理员）。授权行只追加。
 */
@TableName("t_cc_inspector_region")
public class InspectorRegion extends CreateOnlyEntity {
    private String username;
    private String regionCode;
    private String regionName;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getRegionCode() { return regionCode; }
    public void setRegionCode(String regionCode) { this.regionCode = regionCode; }
    public String getRegionName() { return regionName; }
    public void setRegionName(String regionName) { this.regionName = regionName; }
}
