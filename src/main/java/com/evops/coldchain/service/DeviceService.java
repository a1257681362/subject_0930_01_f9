package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.DeviceBindRequest;
import com.evops.coldchain.dto.DeviceRegisterRequest;
import com.evops.coldchain.dto.DeviceRevokeRequest;
import com.evops.coldchain.entity.Device;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.mapper.DeviceMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 车载温控设备登记与证书备案（V9）。
 *
 * 不变量：
 * - 设备序列、备案证书指纹库内唯一；登记时解析 X.509 证书并固化证书要素与 PEM 原文；
 * - 证书备案不要求“证书当前有效”以外的 CA 链（演示/车端自签体系），信任锚=平台登记备案本身：
 *   报文验签时证书指纹必须与备案一致，且设备 ACTIVE、证书在有效期；
 * - 作废只追加 REVOKED + 原因/时间，不删行，历史报文仍可用备案证书验证、温度结果不回写；
 * - 换绑车辆只改当前指向并留痕（在途批次的温度归属仍以批次快照/设备序列锚点为准）。
 */
@Service
public class DeviceService {

    @Resource
    private DeviceMapper deviceMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private VehicleService vehicleService;
    @Resource
    private OpLogService opLogService;

    /** 设备登记（证书备案）；可同时安装到车辆 */
    @Transactional
    public Device register(DeviceRegisterRequest req) {
        String serial = req.getDeviceSerial().trim();

        DeviceSignatureService.ParsedCertificate cert;
        try {
            cert = DeviceSignatureService.parse(req.getCertPem());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("设备证书备案失败：" + e.getMessage());
        }
        if (exists("device_serial", serial)) {
            throw new BusinessException("设备序列号已登记: " + serial);
        }
        if (exists("cert_fingerprint_sha256", cert.getFingerprintSha256())) {
            throw new BusinessException("该设备证书已备案（SHA-256 指纹=" + cert.getFingerprintSha256()
                    + "），同一证书不能重复登记");
        }

        Device device = new Device();
        device.setDeviceNo(generateDeviceNo());
        device.setDeviceSerial(serial);
        device.setDeviceModel(req.getDeviceModel());
        device.setFirmwareVersion(req.getFirmwareVersion());
        device.setCertSubject(cert.getSubject());
        device.setCertIssuer(cert.getIssuer());
        device.setCertSerial(cert.getSerialHex());
        device.setCertFingerprintSha256(cert.getFingerprintSha256());
        device.setCertNotBefore(cert.getNotBefore());
        device.setCertNotAfter(cert.getNotAfter());
        device.setCertContent(req.getCertPem());
        device.setStatus(CcConstants.DEVICE_ACTIVE);
        device.setRemark(req.getRemark());

        if (req.getVehicleId() != null) {
            Vehicle vehicle = vehicleService.requireActiveVehicle(req.getVehicleId());
            device.setVehicleId(vehicle.getId());
            device.setBindTime(LocalDateTime.now());
        }
        deviceMapper.insert(device);

        opLogService.log(CcConstants.ENT_DEVICE, device.getId(), null, CcConstants.ACT_DEVICE_REGISTER,
                String.format("车载设备登记并备案证书：设备编号=%s，序列号=%s，型号=%s，证书主体=%s，颁发者=%s，"
                                + "证书序列号=%s，有效期=%s~%s，SHA-256指纹=%s%s",
                        device.getDeviceNo(), serial, device.getDeviceModel(),
                        cert.getSubject(), cert.getIssuer(), cert.getSerialHex(),
                        cert.getNotBefore(), cert.getNotAfter(), cert.getFingerprintSha256(),
                        req.getVehicleId() == null ? "" : "；安装车辆ID=" + req.getVehicleId()));
        return device;
    }

    /** 作废设备：只追加作废标记，此后其报文一律隔离；历史温度结果保留 */
    @Transactional
    public Device revoke(DeviceRevokeRequest req) {
        Device device = requireDevice(req.getDeviceId());
        if (CcConstants.DEVICE_REVOKED.equals(device.getStatus())) {
            throw new BusinessException("设备已作废，不能重复作废: " + device.getDeviceSerial());
        }
        device.setStatus(CcConstants.DEVICE_REVOKED);
        device.setRevokeReason(req.getReason());
        device.setRevokeTime(LocalDateTime.now());
        deviceMapper.updateById(device);

        opLogService.log(CcConstants.ENT_DEVICE, device.getId(), null, CcConstants.ACT_DEVICE_REVOKE,
                String.format("车载设备作废：编号=%s，序列号=%s，证书指纹=%s；作废后报文一律隔离，历史温度结果保留。原因=%s",
                        device.getDeviceNo(), device.getDeviceSerial(),
                        device.getCertFingerprintSha256(), req.getReason()));
        return device;
    }

    /** 设备安装/换绑车辆（只改当前指向，留痕；不影响在途批次快照） */
    @Transactional
    public Device bindVehicle(DeviceBindRequest req) {
        Device device = requireDevice(req.getDeviceId());
        if (CcConstants.DEVICE_REVOKED.equals(device.getStatus())) {
            throw new BusinessException("设备已作废，不能安装/换绑: " + device.getDeviceSerial());
        }
        Vehicle vehicle = vehicleService.requireActiveVehicle(req.getVehicleId());
        Long oldVehicleId = device.getVehicleId();
        device.setVehicleId(vehicle.getId());
        device.setBindTime(LocalDateTime.now());
        deviceMapper.updateById(device);

        opLogService.log(CcConstants.ENT_DEVICE, device.getId(), null, CcConstants.ACT_DEVICE_BIND,
                String.format("车载设备 %s（序列号=%s）%s车辆 %s",
                        device.getDeviceNo(), device.getDeviceSerial(),
                        oldVehicleId == null ? "安装到" : "由车辆#" + oldVehicleId + " 换绑到",
                        vehicle.getPlateNo()));
        return device;
    }

    // ---------------- 查询 ----------------

    public Device requireDevice(Long deviceId) {
        Device device = deviceMapper.selectById(deviceId);
        if (device == null) {
            throw new BusinessException("车载设备不存在: " + deviceId);
        }
        return device;
    }

    public Device findBySerial(String serial) {
        if (serial == null) {
            return null;
        }
        List<Device> rows = deviceMapper.selectList(new QueryWrapper<Device>()
                .eq("device_serial", serial.trim()).last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Device requireBySerial(String serial) {
        Device device = findBySerial(serial);
        if (device == null) {
            throw new BusinessException("车载设备未登记: " + serial);
        }
        return device;
    }

    public List<Device> listDevices() {
        return deviceMapper.selectList(new QueryWrapper<Device>().orderByDesc("id"));
    }

    private boolean exists(String column, Object value) {
        Long count = deviceMapper.selectCount(new QueryWrapper<Device>().eq(column, value));
        return count != null && count > 0;
    }

    /** 稳定设备编号：SB + 9 位序列 */
    private String generateDeviceNo() {
        return String.format("SB%09d", sequenceMapper.nextDeviceNo());
    }
}
