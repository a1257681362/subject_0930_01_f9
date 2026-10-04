package com.evops.coldchain.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 默认升级通知通道：HTTP POST webhook（V6）。
 * 2xx 视为成功；连接异常/超时/非 2xx 均返回失败原因（由调用方落库留痕，后续可重试）。
 * 不引入额外依赖，超时由 coldchain.exception-notify.connect-timeout-ms / read-timeout-ms 配置。
 */
@Component
public class HttpExceptionNotifyTransport implements ExceptionNotifyTransport {

    @Value("${coldchain.exception-notify.connect-timeout-ms:2000}")
    private int connectTimeoutMs;
    @Value("${coldchain.exception-notify.read-timeout-ms:3000}")
    private int readTimeoutMs;

    @Override
    public SendResult send(String endpoint, String payload) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return SendResult.ok();
            }
            return SendResult.fail("webhook 返回非成功状态码: " + code);
        } catch (Exception e) {
            String reason = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return SendResult.fail("通知投递失败：" + reason);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
