package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.DeviceCertRegisterRequest;
import com.evops.coldchain.entity.DeviceCert;
import com.evops.coldchain.mapper.DeviceCertMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备签名证书管理（V9）。
 *
 * 不变量：
 * - 设备序列号库内唯一；证书只追加不删除，作废置 REVOKED + 原因/时间。
 * - 签名验证：HMAC-SHA256(canonical_payload, cert_content)，服务端按登记证书重算比对。
 * - 证书过期/作废/指纹不匹配一律拒绝并进入隔离队列。
 */
@Service
public class DeviceCertService {

    @Resource
    private DeviceCertMapper certMapper;
    @Resource
    private OpLogService opLogService;

    // ---------------- 证书登记 ----------------

    @Transactional
    public DeviceCert register(DeviceCertRegisterRequest req) {
        DeviceCert existing = certMapper.selectOne(new QueryWrapper<DeviceCert>()
                .eq("device_serial", req.getDeviceSerial()));
        if (existing != null) {
            throw new BusinessException("设备序列号已登记证书: " + req.getDeviceSerial()
                    + "（如需更新请先作废原证书）");
        }
        if (req.getValidUntil() == null || !req.getValidUntil().isAfter(LocalDateTime.now())) {
            throw new BusinessException("证书有效期必须晚于当前时间");
        }

        DeviceCert cert = new DeviceCert();
        cert.setDeviceSerial(req.getDeviceSerial());
        cert.setCertFingerprint(req.getCertFingerprint());
        cert.setCertContent(req.getCertContent());
        cert.setStatus(CcConstants.CERT_ACTIVE);
        cert.setValidUntil(req.getValidUntil());
        certMapper.insert(cert);

        opLogService.log(CcConstants.ENT_DEVICE_CERT, cert.getId(), null,
                CcConstants.ACT_DEVICE_CERT_REGISTER,
                String.format("设备证书登记：序列号=%s，指纹=%s，有效期至=%s",
                        cert.getDeviceSerial(), cert.getCertFingerprint(), cert.getValidUntil()));
        return cert;
    }

    public List<DeviceCert> listCerts() {
        return certMapper.selectList(new QueryWrapper<DeviceCert>().orderByDesc("id"));
    }

    public DeviceCert requireCert(Long certId) {
        DeviceCert cert = certMapper.selectById(certId);
        if (cert == null) {
            throw new BusinessException("设备证书不存在: " + certId);
        }
        return cert;
    }

    /** 按设备序列号查找证书 */
    public DeviceCert findBySerial(String deviceSerial) {
        return certMapper.selectOne(new QueryWrapper<DeviceCert>()
                .eq("device_serial", deviceSerial));
    }

    // ---------------- 签名验证 ----------------

    /**
     * 验证设备数据批次的签名。
     * 返回 null 表示验证通过；否则返回 (reasonCode, reasonDetail)。
     */
    public VerifyResult verifySignature(String deviceSerial, String certFingerprint,
                                        String signature, String canonicalPayload) {
        DeviceCert cert = findBySerial(deviceSerial);
        if (cert == null) {
            return new VerifyResult(CcConstants.REASON_DEVICE_UNKNOWN,
                    "设备序列号 " + deviceSerial + " 未登记证书，请先登记设备证书");
        }
        if (CcConstants.REVOKED.equals(cert.getStatus())) {
            return new VerifyResult(CcConstants.REASON_CERT_REVOKED,
                    String.format("设备证书已作废（序列号=%s，作废时间=%s，原因=%s）",
                            deviceSerial, cert.getRevokeTime(), cert.getRevokeReason()));
        }
        if (cert.getValidUntil() == null || cert.getValidUntil().isBefore(LocalDateTime.now())) {
            return new VerifyResult(CcConstants.REASON_CERT_EXPIRED,
                    String.format("设备证书已过期（序列号=%s，有效期至=%s），请更新证书",
                            deviceSerial, cert.getValidUntil()));
        }
        if (!cert.getCertFingerprint().equalsIgnoreCase(certFingerprint)) {
            return new VerifyResult(CcConstants.REASON_CERT_MISMATCH,
                    String.format("证书指纹不匹配：期望 %s，实际 %s",
                            cert.getCertFingerprint(), certFingerprint));
        }

        String expected = hmacSha256Hex(canonicalPayload, cert.getCertContent());
        if (!expected.equalsIgnoreCase(signature)) {
            return new VerifyResult(CcConstants.REASON_SIGN_MISMATCH,
                    String.format("数据签名验证失败：设备=%s，期望签名=%s，实际签名=%s",
                            deviceSerial, expected, signature));
        }
        return null; // 验证通过
    }

    /** 构建规范化载荷（用于签名计算） */
    public static String buildCanonicalPayload(String deviceSerial, Long batchId,
                                               List<com.evops.coldchain.dto.DeviceIngestRequest.TempPoint> points) {
        StringBuilder sb = new StringBuilder();
        sb.append(deviceSerial).append("|").append(batchId).append("|");
        // 按采样时间排序确保确定性
        points.stream()
                .sorted((a, b) -> a.getRecordTime().compareTo(b.getRecordTime()))
                .forEach(p -> sb.append(p.getRecordTime()).append(",")
                        .append(p.getTemperature()).append(";"));
        return sb.toString();
    }

    /** 计算 HMAC-SHA256 十六进制签名 */
    public static String hmacSha256Hex(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 计算失败", e);
        }
    }

    /** 计算 SHA-256 十六进制摘要（用于 payload_hash） */
    public static String sha256Hex(String data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 验证结果：reasonCode + reasonDetail；null 表示通过 */
    public static class VerifyResult {
        private final String reasonCode;
        private final String reasonDetail;

        public VerifyResult(String reasonCode, String reasonDetail) {
            this.reasonCode = reasonCode;
            this.reasonDetail = reasonDetail;
        }

        public String getReasonCode() { return reasonCode; }
        public String getReasonDetail() { return reasonDetail; }
    }
}
