package com.evops.coldchain.service;

import com.evops.coldchain.dto.DeviceTempPoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * 车载设备报文密码学工具（V9）：
 * - 解析 X.509 PEM 证书，提取备案要素（主体/颁发者/序列号/有效期/公钥）与 DER 的 SHA-256 指纹；
 * - 规范化设备温度报文（客户端必须按同一口径构造）；
 * - 用备案/随报证书公钥做 SHA256withRSA 验签。
 *
 * 规范化报文（UTF-8）：
 * <pre>
 *   clientBatchNo + "|" + deviceSerial + "|" +
 *   每个测点 "sampleTime,temperature,mileageKm" 以 ';' 连接（按报文顺序；里程为空则末段留空）
 * </pre>
 * sampleTime 形如 2026-10-03T08:30:00（ISO-LOCAL），temperature/mileage 用 JSON 反序列化
 * 得到的 BigDecimal 的 toPlainString()，保证 8.0 与 8.00 都稳定。
 */
public final class DeviceSignatureService {

    private static final String SIGN_ALGORITHM = "SHA256withRSA";
    private static final String CERT_TYPE = "X.509";
    /** 采样时间固定到秒，避免 LocalDateTime.toString 在秒/纳秒为 0 时省略字段造成口径漂移 */
    private static final DateTimeFormatter POINT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private DeviceSignatureService() {
    }

    /** 已解析的备案证书要素（证书实例不落到实体，仅在验签时使用） */
    public static class ParsedCertificate {
        private final X509Certificate certificate;
        private final String fingerprintSha256;
        private final String subject;
        private final String issuer;
        private final String serialHex;
        private final LocalDateTime notBefore;
        private final LocalDateTime notAfter;

        ParsedCertificate(X509Certificate certificate, String fingerprintSha256) {
            this.certificate = certificate;
            this.fingerprintSha256 = fingerprintSha256;
            this.subject = certificate.getSubjectX500Principal().getName();
            this.issuer = certificate.getIssuerX500Principal().getName();
            this.serialHex = certificate.getSerialNumber().toString(16).toUpperCase();
            this.notBefore = toLocalDateTime(certificate.getNotBefore().toInstant());
            this.notAfter = toLocalDateTime(certificate.getNotAfter().toInstant());
        }

        public X509Certificate getCertificate() { return certificate; }
        public String getFingerprintSha256() { return fingerprintSha256; }
        public String getSubject() { return subject; }
        public String getIssuer() { return issuer; }
        public String getSerialHex() { return serialHex; }
        public LocalDateTime getNotBefore() { return notBefore; }
        public LocalDateTime getNotAfter() { return notAfter; }
        public PublicKey getPublicKey() { return certificate.getPublicKey(); }
    }

    /** 解析 PEM（允许带头尾/去空白）；非法证书抛出 IllegalArgumentException（含原因） */
    public static ParsedCertificate parse(String pem) {
        if (pem == null || pem.trim().isEmpty()) {
            throw new IllegalArgumentException("证书内容为空");
        }
        try {
            CertificateFactory factory = CertificateFactory.getInstance(CERT_TYPE);
            X509Certificate cert = (X509Certificate) factory.generateCertificate(
                    new java.io.ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
            return new ParsedCertificate(cert, sha256Hex(cert.getEncoded()));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("X.509 证书解析失败: " + e.getMessage(), e);
        }
    }

    /** 规范化报文串；签名与幂等摘要都基于它，保证确定性 */
    public static String canonicalPayload(String clientBatchNo, String deviceSerial,
                                          java.util.List<DeviceTempPoint> points) {
        StringBuilder sb = new StringBuilder();
        sb.append(clientBatchNo).append('|').append(deviceSerial).append('|');
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            DeviceTempPoint p = points.get(i);
            sb.append(POINT_TIME.format(p.getSampleTime()))
                    .append(',').append(p.getTemperature().toPlainString())
                    .append(',');
            if (p.getMileageKm() != null) {
                sb.append(p.getMileageKm().toPlainString());
            }
        }
        return sb.toString();
    }

    /** 规范化报文的 SHA-256 摘要（同载荷同摘要，用于 clientBatchNo 换载荷检测与隔离留痕） */
    public static String payloadSha256(String canonical) {
        return sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
    }

    /** SHA256withRSA 验签：签名为 Base64；任何不匹配/解码失败都返回 false（不抛出，便于统一隔离） */
    public static boolean verify(PublicKey publicKey, String canonical, String signatureBase64) {
        try {
            byte[] signature = Base64.getDecoder().decode(signatureBase64);
            Signature verifier = Signature.getInstance(SIGN_ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    /** 计算字节数组的 SHA-256 十六进制摘要（小写，64 位） */
    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
