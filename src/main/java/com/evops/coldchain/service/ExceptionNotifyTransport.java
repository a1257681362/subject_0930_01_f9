package com.evops.coldchain.service;

/**
 * 升级通知传输通道（V6）。
 * 默认实现走 HTTP webhook；集成测试可用测试替身替换（{@code @Primary}），
 * 以确定性地制造发送失败与重试成功，无需真实网络。
 */
public interface ExceptionNotifyTransport {

    /**
     * @param endpoint 规则配置的投递地址（非空）
     * @param payload  通知正文（JSON 字符串）
     * @return 发送结果（成功或失败原因）
     */
    SendResult send(String endpoint, String payload);

    class SendResult {
        private final boolean success;
        private final String failReason;

        private SendResult(boolean success, String failReason) {
            this.success = success;
            this.failReason = failReason;
        }

        public static SendResult ok() {
            return new SendResult(true, null);
        }

        public static SendResult fail(String reason) {
            return new SendResult(false, reason);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getFailReason() {
            return failReason;
        }
    }
}
