package com.evops.coldchain.service;

import com.evops.common.BusinessException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V8 报价维度代码表：温区 / 箱型 / 服务等级。
 * 报价严格按 路线 × 温区 × 箱型 × 服务等级 计算，维度代码必须出自此处，拒绝未知代码。
 */
public final class QuoteDimensions {

    private QuoteDimensions() {
    }

    private static final Map<String, String> ZONES = new LinkedHashMap<>();
    private static final Map<String, String> BOX_TYPES = new LinkedHashMap<>();
    private static final Map<String, String> SERVICE_LEVELS = new LinkedHashMap<>();

    static {
        ZONES.put("REFRIGERATE", "冷藏温区(2~8℃)");
        ZONES.put("FROZEN", "冷冻温区(-18℃以下)");
        ZONES.put("DEEP_FROZEN", "深冷温区(-25℃以下)");
        ZONES.put("COOL", "阴凉温区(0~20℃)");
        ZONES.put("NORMAL", "常温温区");

        BOX_TYPES.put("REFRIGERATED_BOX", "冷藏箱");
        BOX_TYPES.put("INSULATED_BOX", "保温箱");
        BOX_TYPES.put("DRY_ICE_BOX", "干冰箱");
        BOX_TYPES.put("FULL_TRUCK", "整车冷藏车厢");
        BOX_TYPES.put("LTL", "零担拼舱");

        SERVICE_LEVELS.put("STANDARD", "标准达");
        SERVICE_LEVELS.put("EXPRESS", "加急达");
        SERVICE_LEVELS.put("DEDICATED", "专车直达");
        SERVICE_LEVELS.put("MONITORED", "全程温控可视");
    }

    public static String zoneName(String code) {
        return resolve("温区", ZONES, code);
    }

    public static String boxTypeName(String code) {
        return resolve("箱型", BOX_TYPES, code);
    }

    public static String serviceLevelName(String code) {
        return resolve("服务等级", SERVICE_LEVELS, code);
    }

    private static String resolve(String label, Map<String, String> table, String code) {
        if (code == null) {
            throw new BusinessException(label + "代码不能为空");
        }
        String name = table.get(code.trim().toUpperCase());
        if (name == null) {
            throw new BusinessException("未知" + label + "代码: " + code + "，支持的代码：" + table.keySet());
        }
        return name;
    }

    /** 附加费类型名（未知类型统一按 OTHER 处理并要求提交方自带名称） */
    public static String surchargeTypeName(String code, String givenName) {
        if (givenName != null && !givenName.trim().isEmpty()) {
            return givenName.trim();
        }
        switch (code == null ? "" : code.trim().toUpperCase()) {
            case "FUEL": return "燃油附加费";
            case "COLD": return "制冷费";
            case "HANDLING": return "装卸费";
            case "BRIDGE": return "路桥费";
            case "WAIT": return "等待费";
            case "INSURANCE": return "货运保险费";
            default: return "其他附加费";
        }
    }
}
