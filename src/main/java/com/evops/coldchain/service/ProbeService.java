package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.ProbeBindRequest;
import com.evops.coldchain.dto.ProbeCertUploadRequest;
import com.evops.coldchain.dto.ProbeCreateRequest;
import com.evops.coldchain.entity.ProbeBindIdempotent;
import com.evops.coldchain.entity.ProbeCert;
import com.evops.coldchain.entity.TempProbe;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.entity.VehicleProbe;
import com.evops.coldchain.mapper.ProbeBindIdempotentMapper;
import com.evops.coldchain.mapper.ProbeCertMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.coldchain.mapper.TempProbeMapper;
import com.evops.coldchain.mapper.VehicleProbeMapper;
import com.evops.common.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 车载温度探头与校准凭证管理。
 *
 * 不变量：
 * - 探头序列号库内唯一；建档登记序列号、量程、校准机构、有效期。
 * - 凭证只追加：上传新凭证生成 SHA-256 摘要并切换为当前凭证；作废只置 VOID + 原因/时间，
 *   不删除文件、不改原行；每次下载/作废写 t_cc_op_log。
 * - 车辆绑定按 requestId 幂等：重复请求只回第一次的绑定结果，绝不产生第二条绑定行。
 * - 替换探头不删旧绑定：旧行 released_time + replaced_by_bind_id 留前后关系。
 * - 绑定/发运都校验探头 ACTIVE 且当前凭证 VALID 且未过有效期；过期/作废设备一律拦截。
 */
@Service
public class ProbeService {

    @Resource
    private TempProbeMapper probeMapper;
    @Resource
    private ProbeCertMapper certMapper;
    @Resource
    private VehicleProbeMapper bindMapper;
    @Resource
    private ProbeBindIdempotentMapper bindReqMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private VehicleService vehicleService;
    @Resource
    private OpLogService opLogService;

    // ---------------- 探头建档 ----------------

    @Transactional
    public TempProbe createProbe(ProbeCreateRequest req) {
        if (req.getRangeMin() == null || req.getRangeMax() == null
                || req.getRangeMin().compareTo(req.getRangeMax()) >= 0) {
            throw new BusinessException("量程下限必须小于上限");
        }
        Long exists = probeMapper.selectCount(new QueryWrapper<TempProbe>()
                .eq("serial_no", req.getSerialNo()));
        if (exists != null && exists > 0) {
            throw new BusinessException("探头序列号已登记: " + req.getSerialNo());
        }

        TempProbe probe = new TempProbe();
        probe.setProbeNo(generateProbeNo());
        probe.setSerialNo(req.getSerialNo());
        probe.setModel(req.getModel());
        probe.setRangeMin(req.getRangeMin());
        probe.setRangeMax(req.getRangeMax());
        probe.setCalibrateOrg(req.getCalibrateOrg());
        probe.setValidUntil(req.getValidUntil());
        probe.setStatus(CcConstants.ACTIVE);
        probe.setRemark(req.getRemark());
        probeMapper.insert(probe);

        // 可选：建档同时上传首份校准凭证
        if (notBlank(req.getCertNo()) || notBlank(req.getCertFileName())) {
            if (!notBlank(req.getCertNo()) || !notBlank(req.getCertFileName())
                    || req.getCalibrateDate() == null || !notBlank(req.getCertFileContent())) {
                throw new BusinessException("首份校准凭证信息不完整：证书编号、校准日期、文件名与证书内容均必填");
            }
            ProbeCert cert = buildCert(probe, req.getCertNo(), req.getCalibrateOrg(),
                    req.getCalibrateDate(), req.getValidUntil(),
                    req.getRangeMin(), req.getRangeMax(),
                    req.getCertFileName(), req.getCertFileContent());
            certMapper.insert(cert);
            probe.setCurrentCertId(cert.getId());
            probeMapper.updateById(probe);
            opLogService.log(CcConstants.ENT_CERT, cert.getId(), null, CcConstants.ACT_CERT_UPLOAD,
                    certUploadSummary(probe, cert, "建档随附首份校准凭证"));
        }

        opLogService.log(CcConstants.ENT_PROBE, probe.getId(), null, CcConstants.ACT_PROBE_CREATE,
                String.format("探头建档：编号=%s，序列号=%s，型号=%s，量程=%s~%s℃，校准机构=%s，有效期至=%s",
                        probe.getProbeNo(), probe.getSerialNo(), probe.getModel(),
                        probe.getRangeMin(), probe.getRangeMax(), probe.getCalibrateOrg(), probe.getValidUntil()));
        return probe;
    }

    public List<TempProbe> listProbes() {
        return probeMapper.selectList(new QueryWrapper<TempProbe>().orderByDesc("id"));
    }

    public TempProbe requireProbe(Long probeId) {
        TempProbe probe = probeMapper.selectById(probeId);
        if (probe == null) {
            throw new BusinessException("温度探头不存在: " + probeId);
        }
        return probe;
    }

    // ---------------- 校准凭证：上传 / 下载 / 作废 ----------------

    @Transactional
    public ProbeCert uploadCert(ProbeCertUploadRequest req) {
        TempProbe probe = requireProbe(req.getProbeId());
        if (CcConstants.REVOKED.equals(probe.getStatus())) {
            throw new BusinessException("探头已停用，不能再上传校准凭证: " + probe.getSerialNo());
        }
        if (req.getValidUntil() == null || !req.getValidUntil().isAfter(LocalDateTime.now())) {
            throw new BusinessException("校准有效期必须晚于当前时间，过期/失效日期的凭证不能登记");
        }
        BigDecimal min = req.getRangeMin() == null ? probe.getRangeMin() : req.getRangeMin();
        BigDecimal max = req.getRangeMax() == null ? probe.getRangeMax() : req.getRangeMax();
        if (min.compareTo(max) >= 0) {
            throw new BusinessException("凭证量程下限必须小于上限");
        }

        ProbeCert cert = buildCert(probe, req.getCertNo(), req.getCalibrateOrg(),
                req.getCalibrateDate(), req.getValidUntil(), min, max,
                req.getFileName(), req.getFileContent());
        certMapper.insert(cert);

        // 切换为探头当前凭证（历史凭证行原样保留，只追加不改写）
        probe.setCurrentCertId(cert.getId());
        probe.setCalibrateOrg(req.getCalibrateOrg());
        probe.setValidUntil(req.getValidUntil());
        probe.setRangeMin(min);
        probe.setRangeMax(max);
        probeMapper.updateById(probe);

        opLogService.log(CcConstants.ENT_CERT, cert.getId(), null, CcConstants.ACT_CERT_UPLOAD,
                certUploadSummary(probe, cert, "新校准凭证上传并切换为当前凭证"));
        return cert;
    }

    /**
     * 下载凭证：返回凭证内容，同时追加下载留痕（last_download_time + t_cc_op_log）。
     * 已作废凭证仍允许下载用于追溯，但同样写审计流水。
     */
    @Transactional
    public ProbeCert downloadCert(Long certId) {
        ProbeCert cert = requireCert(certId);
        cert.setLastDownloadTime(LocalDateTime.now());
        certMapper.updateById(cert);

        opLogService.log(CcConstants.ENT_CERT, cert.getId(), null, CcConstants.ACT_CERT_DOWNLOAD,
                String.format("校准证书下载：证书编号=%s，探头ID=%d，文件=%s，摘要SHA-256=%s，状态=%s",
                        cert.getCertNo(), cert.getProbeId(), cert.getFileName(), cert.getSha256(),
                        cert.getStatus()));
        return cert;
    }

    /** 作废凭证：只追加作废标记/原因/时间，不删除文件、不改证书原内容 */
    @Transactional
    public ProbeCert voidCert(Long certId, String reason) {
        ProbeCert cert = requireCert(certId);
        if (CcConstants.CERT_VOID.equals(cert.getStatus())) {
            throw new BusinessException("凭证已作废，不能重复作废");
        }
        cert.setStatus(CcConstants.CERT_VOID);
        cert.setVoidReason(reason);
        cert.setVoidTime(LocalDateTime.now());
        certMapper.updateById(cert);

        // 若作废的是某探头的当前凭证，摘除其“当前凭证”，使绑定/发运立即拦截该设备。
        // 注意：MyBatis-Plus updateById 默认忽略 null 字段，置空列必须用 UpdateWrapper 显式 set。
        TempProbe probe = probeMapper.selectById(cert.getProbeId());
        if (probe != null && cert.getId().equals(probe.getCurrentCertId())) {
            probeMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<TempProbe>()
                    .eq(TempProbe::getId, probe.getId())
                    .set(TempProbe::getCurrentCertId, null));
        }

        opLogService.log(CcConstants.ENT_CERT, cert.getId(), null, CcConstants.ACT_CERT_VOID,
                String.format("校准证书作废：证书编号=%s，探头ID=%d，摘要SHA-256=%s，原因=%s",
                        cert.getCertNo(), cert.getProbeId(), cert.getSha256(), reason));
        return cert;
    }

    public List<ProbeCert> listCerts(Long probeId) {
        requireProbe(probeId);
        return certMapper.selectList(new QueryWrapper<ProbeCert>()
                .eq("probe_id", probeId)
                .orderByDesc("id"));
    }

    public ProbeCert requireCert(Long certId) {
        ProbeCert cert = certMapper.selectById(certId);
        if (cert == null) {
            throw new BusinessException("校准凭证不存在: " + certId);
        }
        return cert;
    }

    // ---------------- 车辆绑定（幂等关联）+ 替换链 ----------------

    /**
     * 车载探头校准凭证关联（绑定/替换），按 requestId 幂等。
     * 同一 requestId 的重复请求直接返回第一次的绑定行：不新增绑定、不改变替换链、不写第二条流水。
     */
    @Transactional
    public ProbeBindOutcome bindIdempotent(ProbeBindRequest req) {
        // 1) 幂等快路径：已完成过的请求直接回第一次结果
        ProbeBindIdempotent existing = bindReqMapper.selectOne(new QueryWrapper<ProbeBindIdempotent>()
                .eq("request_id", req.getRequestId()));
        if (existing != null) {
            return replay(existing, req);
        }

        // 2) 先校验业务对象：车辆在册、探头在用、当前凭证 VALID 且未过有效期。
        //    校验在占幂等键之前，失败不留下占位行；并发重复由下方唯一约束兜底。
        Vehicle vehicle = vehicleService.requireActiveVehicle(req.getVehicleId());
        TempProbe probe = requireProbe(req.getProbeId());
        ProbeCert cert = requireValidCurrentCert(probe);

        // 3) 占幂等键（与业务同事务，唯一约束在数据库层兜底并发重复）
        try {
            claimRequest(req);
        } catch (DuplicateKeyException e) {
            ProbeBindIdempotent winner = bindReqMapper.selectOne(new QueryWrapper<ProbeBindIdempotent>()
                    .eq("request_id", req.getRequestId()));
            if (winner != null) {
                return replay(winner, req);
            }
            throw new BusinessException("关联请求处理冲突，请按原 requestId 重试");
        }

        // 4) 释放旧绑定（保留前后关系），再追加新绑定行
        VehicleProbe previous = currentBinding(vehicle.getId());
        LocalDateTime now = LocalDateTime.now();

        VehicleProbe bind = new VehicleProbe();
        bind.setVehicleId(vehicle.getId());
        bind.setProbeId(probe.getId());
        bind.setBindTime(now);
        bind.setValidUntilSnap(cert.getValidUntil());
        bind.setCertId(cert.getId());
        bind.setCertNoSnap(cert.getCertNo());
        bind.setSha256Snap(cert.getSha256());
        bind.setRangeMinSnap(probe.getRangeMin());
        bind.setRangeMaxSnap(probe.getRangeMax());
        bind.setCalibrateOrgSnap(probe.getCalibrateOrg());
        bind.setSerialNoSnap(probe.getSerialNo());
        bindMapper.insert(bind);

        if (previous != null) {
            previous.setReleasedTime(now);
            previous.setReplacedByBindId(bind.getId());
            bindMapper.updateById(previous);
        }

        // 5) 回填幂等行的 bind_id（与绑定行同一事务提交，要么都成要么都不成）
        ProbeBindIdempotent claim = bindReqMapper.selectOne(new QueryWrapper<ProbeBindIdempotent>()
                .eq("request_id", req.getRequestId()));
        claim.setBindId(bind.getId());
        bindReqMapper.updateById(claim);

        String action = previous == null ? CcConstants.ACT_PROBE_BIND : CcConstants.ACT_PROBE_REPLACE;
        String summary = previous == null
                ? String.format("车辆 %s 关联车载探头：序列号=%s，证书编号=%s，校准机构=%s，有效期至=%s，摘要=%s",
                        vehicle.getPlateNo(), probe.getSerialNo(), cert.getCertNo(),
                        cert.getCalibrateOrg(), cert.getValidUntil(), cert.getSha256())
                : String.format("车辆 %s 替换车载探头：旧探头序列号=%s（绑定#%d） -> 新探头序列号=%s（绑定#%d）；"
                                + "新证书编号=%s，校准机构=%s，有效期至=%s，摘要=%s；旧绑定保留前后关系不删除",
                        vehicle.getPlateNo(), previous.getSerialNoSnap(), previous.getId(),
                        probe.getSerialNo(), bind.getId(), cert.getCertNo(), cert.getCalibrateOrg(),
                        cert.getValidUntil(), cert.getSha256());
        opLogService.log(CcConstants.ENT_PROBE, bind.getId(), null, action, summary);

        return new ProbeBindOutcome(bind, previous, false);
    }

    /** 重复请求回放：参数必须与第一次一致；只回结果，不新增任何行 */
    private ProbeBindOutcome replay(ProbeBindIdempotent first, ProbeBindRequest req) {
        if (!first.getVehicleId().equals(req.getVehicleId()) || !first.getProbeId().equals(req.getProbeId())) {
            throw new BusinessException("requestId=" + req.getRequestId()
                    + " 已用于另一组车辆/探头关联，不能复用");
        }
        if (first.getBindId() == null || first.getBindId() == 0L) {
            // 占键后业务事务回滚的极短残留：允许同 requestId 重新完成
            throw new BusinessException("该关联请求尚未完成，请重新提交");
        }
        VehicleProbe bind = bindMapper.selectById(first.getBindId());
        if (bind == null) {
            throw new BusinessException("首次关联结果异常缺失: bindId=" + first.getBindId());
        }
        // 被本次绑定替换的旧绑定（replaced_by_bind_id 指向本绑定）；首次绑定时为空
        VehicleProbe previous = bindMapper.selectOne(new QueryWrapper<VehicleProbe>()
                .eq("replaced_by_bind_id", bind.getId())
                .last("LIMIT 1"));
        return new ProbeBindOutcome(bind, previous, true);
    }

    /** 占幂等键：与业务在同一事务提交（业务失败随事务回滚，同 requestId 可重新提交） */
    private void claimRequest(ProbeBindRequest req) {
        ProbeBindIdempotent row = new ProbeBindIdempotent();
        row.setRequestId(req.getRequestId());
        row.setVehicleId(req.getVehicleId());
        row.setProbeId(req.getProbeId());
        row.setBindId(0L); // 占位：绑定行落库后回填真实ID
        bindReqMapper.insert(row);
    }

    /** 车辆当前生效绑定（released_time 为空），无则 null */
    public VehicleProbe currentBinding(Long vehicleId) {
        List<VehicleProbe> rows = bindMapper.selectList(new QueryWrapper<VehicleProbe>()
                .eq("vehicle_id", vehicleId)
                .isNull("released_time")
                .orderByDesc("id"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 车辆绑定历史（含已替换的旧绑定，按时间升序，前后关系经 replaced_by_bind_id 串联） */
    public List<VehicleProbe> bindHistory(Long vehicleId) {
        vehicleService.requireActiveVehicle(vehicleId);
        return bindMapper.selectList(new QueryWrapper<VehicleProbe>()
                .eq("vehicle_id", vehicleId)
                .orderByAsc("id"));
    }

    /**
     * 发运前卡口：车辆必须存在当前绑定，且探头仍在用、凭证未作废、仍在有效期内。
     * 返回发运瞬间要快照到批次的绑定行。
     */
    public VehicleProbe requireValidBindingForShip(Long vehicleId) {
        Vehicle vehicle = vehicleService.requireActiveVehicle(vehicleId);
        VehicleProbe bind = currentBinding(vehicleId);
        if (bind == null) {
            throw new BusinessException(String.format(
                    "车辆 %s 尚未绑定车载温度探头，发运前必须先完成“车载探头校准凭证关联”",
                    vehicle.getPlateNo()));
        }
        TempProbe probe = probeMapper.selectById(bind.getProbeId());
        if (probe == null || CcConstants.REVOKED.equals(probe.getStatus())) {
            throw new BusinessException(String.format(
                    "车辆 %s 当前绑定的探头已停用/不存在（序列号=%s），不允许发运；请替换有效探头",
                    vehicle.getPlateNo(), bind.getSerialNoSnap()));
        }
        ProbeCert cert = certMapper.selectById(bind.getCertId());
        if (cert == null || !CcConstants.CERT_VALID.equals(cert.getStatus())) {
            throw new BusinessException(String.format(
                    "车辆 %s 绑定探头的校准证书已作废（序列号=%s，证书编号=%s），不允许发运；请重新关联持证探头",
                    vehicle.getPlateNo(), bind.getSerialNoSnap(), bind.getCertNoSnap()));
        }
        if (cert.getValidUntil() == null || cert.getValidUntil().isBefore(LocalDateTime.now())) {
            throw new BusinessException(String.format(
                    "车辆 %s 绑定探头的校准证书已过期（序列号=%s，证书编号=%s，有效期至=%s），不允许发运；请替换在有效期内的探头",
                    vehicle.getPlateNo(), bind.getSerialNoSnap(), bind.getCertNoSnap(), cert.getValidUntil()));
        }
        return bind;
    }

    /**
     * 绑定卡口：探头必须 ACTIVE、当前凭证 VALID 且未过期。返回当前凭证。
     */
    public ProbeCert requireValidCurrentCert(TempProbe probe) {
        if (CcConstants.REVOKED.equals(probe.getStatus())) {
            throw new BusinessException("探头已停用，不能绑定: " + probe.getSerialNo());
        }
        if (probe.getCurrentCertId() == null) {
            throw new BusinessException("探头尚未登记有效校准凭证，不能绑定: " + probe.getSerialNo());
        }
        ProbeCert cert = certMapper.selectById(probe.getCurrentCertId());
        if (cert == null || !CcConstants.CERT_VALID.equals(cert.getStatus())) {
            throw new BusinessException("探头当前校准凭证缺失或已作废，不能绑定: " + probe.getSerialNo());
        }
        if (cert.getValidUntil() == null || cert.getValidUntil().isBefore(LocalDateTime.now())) {
            throw new BusinessException(String.format(
                    "探头校准证书已过期（序列号=%s，证书编号=%s，有效期至=%s），不能绑定/发运",
                    probe.getSerialNo(), cert == null ? null : cert.getCertNo(),
                    cert == null ? null : cert.getValidUntil()));
        }
        return cert;
    }

    // ---------------- 私有工具 ----------------

    private ProbeCert buildCert(TempProbe probe, String certNo, String calibrateOrg,
                                LocalDateTime calibrateDate, LocalDateTime validUntil,
                                BigDecimal rangeMin, BigDecimal rangeMax,
                                String fileName, String fileContent) {
        if (!notBlank(fileContent)) {
            throw new BusinessException("证书文件内容不能为空，需据以生成摘要");
        }
        if (calibrateDate == null) {
            throw new BusinessException("校准日期不能为空");
        }
        if (validUntil == null || !validUntil.isAfter(calibrateDate)) {
            throw new BusinessException("校准有效期必须晚于校准日期");
        }
        byte[] bytes = fileContent.getBytes(StandardCharsets.UTF_8);

        ProbeCert cert = new ProbeCert();
        cert.setProbeId(probe.getId());
        cert.setCertNo(certNo);
        cert.setCalibrateOrg(calibrateOrg);
        cert.setCalibrateDate(calibrateDate);
        cert.setValidUntil(validUntil);
        cert.setRangeMin(rangeMin);
        cert.setRangeMax(rangeMax);
        cert.setFileName(fileName);
        cert.setFileContent(fileContent);
        cert.setFileSize((long) bytes.length);
        cert.setSha256(sha256Hex(bytes));
        cert.setStatus(CcConstants.CERT_VALID);
        return cert;
    }

    private String certUploadSummary(TempProbe probe, ProbeCert cert, String prefix) {
        return String.format("%s：探头=%s（序列号=%s），证书编号=%s，校准机构=%s，校准日期=%s，有效期至=%s，"
                        + "文件=%s（%d 字节），SHA-256=%s",
                prefix, probe.getProbeNo(), probe.getSerialNo(), cert.getCertNo(), cert.getCalibrateOrg(),
                cert.getCalibrateDate(), cert.getValidUntil(), cert.getFileName(),
                cert.getFileSize() == null ? 0 : cert.getFileSize(), cert.getSha256());
    }

    /** 稳定探头编号：PW + 8 位序列 */
    private String generateProbeNo() {
        return String.format("PW%08d", sequenceMapper.nextProbeNo());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
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

    /** 关联结果：bind 绑定行；previous 被替换的旧绑定（首次绑定为 null）；replay 是否重复请求回放 */
    public static class ProbeBindOutcome {
        private final VehicleProbe bind;
        private final VehicleProbe previous;
        private final boolean replay;

        public ProbeBindOutcome(VehicleProbe bind, VehicleProbe previous, boolean replay) {
            this.bind = bind;
            this.previous = previous;
            this.replay = replay;
        }

        public VehicleProbe getBind() { return bind; }
        public VehicleProbe getPrevious() { return previous; }
        public boolean isReplay() { return replay; }
    }
}
