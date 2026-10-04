package com.evops.coldchain.job;

import com.evops.coldchain.service.AlertScanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Map;

/**
 * 冷链温控预警定时扫描任务（V5）。
 *
 * 固定间隔触发 {@link AlertScanService#scan()}：按采样点增量评估连续超限。
 * - 重启后自动恢复执行；扫描游标（t_cc_alert_scan_cursor）只增不减，评估幂等，
 *   因此重启后重扫不多写预警/温度异常/流水，已产生预警也不消失。
 * - 定时线程无登录主体，操作者统一回退记为 system。
 * - 通过 coldchain.alert-scan.enabled=false 可关闭（集成测试直调服务，避免定时器干扰）。
 */
@Component
@ConditionalOnProperty(name = "coldchain.alert-scan.enabled", havingValue = "true", matchIfMissing = true)
public class AlertScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(AlertScanScheduler.class);

    @Resource
    private AlertScanService alertScanService;

    @Scheduled(fixedDelayString = "${coldchain.alert-scan.fixed-delay-ms:60000}",
            initialDelayString = "${coldchain.alert-scan.initial-delay-ms:15000}")
    public void scanAlerts() {
        try {
            Map<String, Object> summary = alertScanService.scan();
            int newAlerts = (int) summary.getOrDefault("newAlerts", 0);
            if (newAlerts > 0 || log.isDebugEnabled()) {
                log.info("温控预警定时扫描完成: {}", summary);
            }
        } catch (Exception e) {
            // 单次扫描失败不影响下一次调度；数据未提交（@Transactional 回滚），下次扫描幂等续算
            log.error("温控预警定时扫描失败，将在下个周期重试", e);
        }
    }
}
