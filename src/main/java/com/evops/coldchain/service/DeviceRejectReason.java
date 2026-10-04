package com.evops.coldchain.service;

/**
 * 设备报文验签/入账失败原因码（V9）。
 * 隔离队列与接口错误统一用“原因码 + 可读说明”，便于运维定位与重放。
 */
public final class DeviceRejectReason {

    private DeviceRejectReason() {
    }

    /** 随报证书无法解析为 X.509 证书 */
    public static final String CERT_PARSE_ERROR = "CERT_PARSE_ERROR";
    /** 证书未在平台备案（指纹匹配不到任何在册设备） */
    public static final String CERT_NOT_REGISTERED = "CERT_NOT_REGISTERED";
    /** 证书签名所用密钥与备案证书公钥不匹配（签名验证失败） */
    public static final String SIGNATURE_INVALID = "SIGNATURE_INVALID";
    /** 报文声称的设备序列与证书备案设备不一致 */
    public static final String DEVICE_SERIAL_MISMATCH = "DEVICE_SERIAL_MISMATCH";
    /** 设备已作废，其报文一律隔离 */
    public static final String DEVICE_REVOKED = "DEVICE_REVOKED";
    /** 证书不在有效期（未生效或已过期，按服务端当前时间） */
    public static final String CERT_EXPIRED = "CERT_EXPIRED";
    /** 同一 clientBatchNo 此前已提交，但本次报文摘要不同（禁止换载荷重放） */
    public static final String BATCH_PAYLOAD_CHANGED = "BATCH_PAYLOAD_CHANGED";
    /** 隔离单已重放成功入账，不能再次重放 */
    public static final String REPLAY_ALREADY_RELEASED = "REPLAY_ALREADY_RELEASED";
    /** 人工补录的设备序列未登记在册 */
    public static final String DEVICE_NOT_FOUND = "DEVICE_NOT_FOUND";

    public static String describe(String code) {
        switch (code) {
            case CERT_PARSE_ERROR:
                return "随报设备证书无法解析为有效的 X.509 证书，请检查证书 PEM 原文";
            case CERT_NOT_REGISTERED:
                return "随报设备证书未在平台备案（证书指纹无匹配设备），请先完成设备登记后重放";
            case SIGNATURE_INVALID:
                return "报文签名验证失败：签名与证书公钥不匹配，报文可能被篡改或私钥不符";
            case DEVICE_SERIAL_MISMATCH:
                return "报文声称的设备序列号与签名证书备案设备不一致，拒绝入账";
            case DEVICE_REVOKED:
                return "设备已作废，其温度报文一律隔离，不得入账";
            case CERT_EXPIRED:
                return "设备证书不在有效期内（尚未生效或已过期），请更换有效证书后重放";
            case BATCH_PAYLOAD_CHANGED:
                return "同一设备侧批次号已提交过且报文内容不同，禁止用新载荷覆盖首次结果";
            case REPLAY_ALREADY_RELEASED:
                return "该隔离批次已重放成功入账，只能重放未入账数据，已入账批次禁止再次重放";
            case DEVICE_NOT_FOUND:
                return "设备序列号未登记在册，不能人工补录，请先登记设备并备案证书";
            default:
                return "设备温度报文校验失败: " + code;
        }
    }
}
