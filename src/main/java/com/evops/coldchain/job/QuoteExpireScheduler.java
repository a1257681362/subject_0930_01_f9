package com.evops.coldchain.job;

import com.evops.coldchain.service.QuoteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Map;

/**
 * 报价批次过期扫描（V8）：把到截止时间仍 OPEN 的报价批次懒标记为 EXPIRED。
 * 锁价/报价路径上另有同步懒过期兜底，双保险保证“报价过期后不能被新批次引用”。
 * 定时线程无登录主体，操作者记 system；扫描幂等（已是 EXPIRED 的批次不再处理）。
 * 通过 coldchain.quote-expire.enabled=false 关闭（集成测试直调服务，避免定时器干扰）。
 */
@Component
@ConditionalOnProperty(name = "coldchain.quote-expire.enabled", havingValue = "true", matchIfMissing = true)
public class QuoteExpireScheduler {

    private static final Logger log = LoggerFactory.getLogger(QuoteExpireScheduler.class);

    @Resource
    private QuoteService quoteService;

    @Scheduled(fixedDelayString = "${coldchain.quote-expire.fixed-delay-ms:60000}",
            initialDelayString = "${coldchain.quote-expire.initial-delay-ms:10000}")
    public void scanExpired() {
        try {
            Map<String, Object> summary = quoteService.scanExpired();
            int expired = (int) summary.getOrDefault("expiredBatches", 0);
            if (expired > 0 || log.isDebugEnabled()) {
                log.info("报价批次过期扫描完成: {}", summary);
            }
        } catch (Exception e) {
            log.error("报价批次过期扫描失败，将在下个周期重试", e);
        }
    }
}
