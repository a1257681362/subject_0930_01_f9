package com.evops.coldchain.job;

import com.evops.coldchain.service.ExceptionLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 异常处置时钟定时扫描（V6）。
 *
 * 固定间隔触发 {@link ExceptionLifecycleService#scanClock}：
 * - 处置时限逾期未闭环的 CONFIRMED 异常自动升级（通知升级岗位，每异常只升级一次）；
 * - 最近一次升级/响应通知发送失败的未关闭异常自动重试（重试原因取上一次失败原因）。
 * - 定时线程无登录主体，操作者回退记为 system；扫描幂等（升级事实 escalated 只进不退）。
 * - 通过 coldchain.exception-clock.enabled=false 关闭（集成测试直调服务，避免定时器干扰）。
 */
@Component
@ConditionalOnProperty(name = "coldchain.exception-clock.enabled", havingValue = "true", matchIfMissing = true)
public class ExceptionClockScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExceptionClockScheduler.class);

    @Resource
    private ExceptionLifecycleService lifecycleService;

    @Scheduled(fixedDelayString = "${coldchain.exception-clock.fixed-delay-ms:60000}",
            initialDelayString = "${coldchain.exception-clock.initial-delay-ms:20000}")
    public void scanClock() {
        try {
            Map<String, Object> summary = lifecycleService.scanClock(LocalDateTime.now());
            int escalated = (int) summary.getOrDefault("escalated", 0);
            int retried = (int) summary.getOrDefault("notificationRetried", 0);
            if (escalated > 0 || retried > 0 || log.isDebugEnabled()) {
                log.info("异常处置时钟扫描完成: {}", summary);
            }
        } catch (Exception e) {
            // 单次失败不影响下次调度；事务回滚，升级/重试都按当前状态幂等重算
            log.error("异常处置时钟扫描失败，将在下个周期重试", e);
        }
    }
}
