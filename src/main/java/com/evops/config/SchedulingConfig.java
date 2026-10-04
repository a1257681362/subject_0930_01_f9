package com.evops.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 开启 Spring 定时任务（冷链温控预警扫描由 AlertScanScheduler 周期触发）。 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
