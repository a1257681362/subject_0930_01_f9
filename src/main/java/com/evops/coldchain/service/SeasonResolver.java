package com.evops.coldchain.service;

import com.evops.coldchain.CcConstants;

import java.time.LocalDateTime;

/**
 * 季节解析：按公历月份把运输时间归入季节，路线据此选取对应季节的生效版本。
 * 采用气象口径：3~5 月春、6~8 月夏、9~11 月秋、12~2 月冬。
 * 若路线没有配置当季版本，选取逻辑可回退到 GENERAL 通用配置（见 RouteService）。
 */
public final class SeasonResolver {

    private SeasonResolver() {
    }

    public static String seasonOf(LocalDateTime time) {
        int month = time.getMonthValue();
        if (month >= 3 && month <= 5) {
            return CcConstants.SEASON_SPRING;
        }
        if (month >= 6 && month <= 8) {
            return CcConstants.SEASON_SUMMER;
        }
        if (month >= 9 && month <= 11) {
            return CcConstants.SEASON_AUTUMN;
        }
        return CcConstants.SEASON_WINTER;
    }

    /** 归一化外部传入的季节码；非法值按通用季节处理并由上层决定是否放行 */
    public static String normalize(String season) {
        if (season == null) {
            return CcConstants.SEASON_GENERAL;
        }
        switch (season.trim().toUpperCase()) {
            case CcConstants.SEASON_SPRING:
                return CcConstants.SEASON_SPRING;
            case CcConstants.SEASON_SUMMER:
                return CcConstants.SEASON_SUMMER;
            case CcConstants.SEASON_AUTUMN:
                return CcConstants.SEASON_AUTUMN;
            case CcConstants.SEASON_WINTER:
                return CcConstants.SEASON_WINTER;
            case CcConstants.SEASON_GENERAL:
                return CcConstants.SEASON_GENERAL;
            default:
                throw new IllegalArgumentException("未知季节: " + season
                        + "（支持 SPRING/SUMMER/AUTUMN/WINTER/GENERAL）");
        }
    }
}
