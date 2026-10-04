package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.InspectionConcludeRequest;
import com.evops.coldchain.dto.InspectionTaskCreateRequest;
import com.evops.coldchain.dto.SampleSealRequest;
import com.evops.coldchain.dto.TransferConfirmRequest;
import com.evops.coldchain.dto.TransferRequest;
import com.evops.coldchain.entity.InspectionSample;
import com.evops.coldchain.entity.InspectionTask;
import com.evops.coldchain.entity.SampleTransfer;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.mapper.InspectionSampleMapper;
import com.evops.coldchain.mapper.InspectionTaskMapper;
import com.evops.coldchain.mapper.SampleTransferMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.mapper.VehicleMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 药品运输抽检服务（V7）。
 *
 * 业务规则：
 * - 监管人员按批次立项抽检，任务归属监管区域；查询/详情/封存/转移/结论全部按授权区域隔离。
 * - 封存样本时生成库内唯一、永不复用的封存码，封存当刻固化采样人/采样位置/数量/封存照片；
 *   样本只沿 SEALED -> TRANSFERRED 前进，系统不提供修改、删除样本或照片的任何路径。
 * - 样本转移双人确认：移交人发起即记录移交方确认、单据停留 PENDING；接收人必须是不同于
 *   移交人的第二个登录账号，确认后才 CONFIRMED 并转移当前持有方。每次转移只追加一行。
 * - 抽检结论回写批次独立抽检列，绝不触碰批次状态/温度/异常/交接等原始运输记录；结论一次定稿。
 * - 导出按区域隔离并对承运人联系方式（驾驶员电话）脱敏，逐次写导出审计流水。
 */
@Service
public class InspectionService {

    @Resource
    private InspectionTaskMapper taskMapper;
    @Resource
    private InspectionSampleMapper sampleMapper;
    @Resource
    private SampleTransferMapper transferMapper;
    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private VehicleMapper vehicleMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private InspectorRegionService regionService;
    @Resource
    private OpLogService opLogService;

    // ---------------- 任务立项 ----------------

    @Transactional
    public InspectionTask createTask(InspectionTaskCreateRequest req) {
        requireManage();
        TransportBatch batch = requireBatch(req.getBatchId());
        if (CcConstants.BATCH_REVOKED.equals(batch.getStatus())) {
            throw new BusinessException("批次已撤回作废，不能立项抽检: " + batch.getBatchNo());
        }
        String region = regionService.resolveAndBindRegion(req.getSuperviseRegion(), batch);

        InspectionTask task = new InspectionTask();
        task.setTaskNo(generateTaskNo());
        task.setBatchId(batch.getId());
        task.setSuperviseRegion(region);
        task.setTaskName(req.getTaskName().trim());
        task.setSamplingLocation(trim(req.getSamplingLocation()));
        task.setSamplerName(trim(req.getSamplerName()));
        task.setStatus(CcConstants.TASK_PLANNED);
        task.setRemark(req.getRemark());
        taskMapper.insert(task);

        opLogService.log(CcConstants.ENT_INSPECTION, task.getId(), batch.getId(),
                CcConstants.ACT_INSPECTION_CREATE,
                String.format("批次 %s 立项抽检任务 %s（监管区域=%s，任务=%s，默认采样位置=%s，采样人=%s）；"
                                + "审批进行期间抽检与样本封存照常可办、全程可追溯",
                        batch.getBatchNo(), task.getTaskNo(), region, task.getTaskName(),
                        nz(task.getSamplingLocation()), nz(task.getSamplerName())));
        return task;
    }

    // ---------------- 样本封存 ----------------

    @Transactional
    public InspectionSample sealSample(SampleSealRequest req) {
        requireManage();
        InspectionTask task = requireTask(req.getTaskId());
        if (CcConstants.TASK_CONCLUDED.equals(task.getStatus())) {
            throw new BusinessException(String.format(
                    "抽检任务 %s 已出结论定稿，不能再封存新样本", task.getTaskNo()));
        }
        String location = trim(req.getSampleLocation());
        if (location == null) {
            location = trim(task.getSamplingLocation());
        }
        String sampler = trim(req.getSamplerName());
        if (sampler == null) {
            sampler = trim(task.getSamplerName());
        }
        if (location == null) {
            throw new BusinessException("采样位置缺失：封存样本必须记录采样位置（任务默认位置也为空，请逐条提供）");
        }
        if (sampler == null) {
            throw new BusinessException("采样人缺失：封存样本必须记录采样人（任务默认采样人为空，请逐条提供）");
        }

        int seq = countSamples(task.getId()) + 1;
        InspectionSample sample = new InspectionSample();
        sample.setTaskId(task.getId());
        sample.setBatchId(task.getBatchId());
        sample.setSampleSeq(seq);
        sample.setSealCode(generateSealCode());
        sample.setQuantity(req.getQuantity());
        sample.setUnit(isBlank(req.getUnit()) ? "盒" : req.getUnit().trim());
        sample.setSampleLocation(location);
        sample.setSamplerName(sampler);
        sample.setSealPhotoName(req.getSealPhotoName().trim());
        sample.setSealPhotoUrl(req.getSealPhotoUrl().trim());
        sample.setSealPhotoSha256(sha256Hex(req.getSealPhotoContent()));
        sample.setStatus(CcConstants.SAMPLE_SEALED);
        // 封存当刻的初始持有方=采样人（后续经双人确认转移）
        sample.setCustodianName(sampler);
        sample.setCustodianOrg(trim(req.getCustodianOrg()));
        sample.setTransferCount(0);
        sample.setSealTime(LocalDateTime.now());
        sampleMapper.insert(sample);

        opLogService.log(CcConstants.ENT_SAMPLE, sample.getId(), task.getBatchId(),
                CcConstants.ACT_SAMPLE_SEAL,
                String.format("抽检任务 %s 封存第%d个样本：封存码=%s，采样人=%s，采样位置=%s，数量=%d%s，"
                                + "封存照片=%s（%s），摘要=%s；封存后样本/照片不可修改、不可删除",
                        task.getTaskNo(), seq, sample.getSealCode(), sampler, location,
                        sample.getQuantity(), sample.getUnit(), sample.getSealPhotoName(),
                        sample.getSealPhotoUrl(), nz(sample.getSealPhotoSha256())));
        return sample;
    }

    // ---------------- 样本转移：双人确认 ----------------

    /** 移交人发起：记录移交方确认，单据停留 PENDING，等待第二个账号接收确认 */
    @Transactional
    public SampleTransfer requestTransfer(TransferRequest req) {
        requireManage();
        InspectionSample sample = requireSample(req.getSampleId());
        InspectionTask task = requireTask(sample.getTaskId());

        Long pending = transferMapper.selectCount(new QueryWrapper<SampleTransfer>()
                .eq("sample_id", sample.getId())
                .eq("status", CcConstants.TRANSFER_PENDING));
        if (pending != null && pending > 0) {
            throw new BusinessException(String.format(
                    "样本[%s]上一次移交尚未完成接收人双人确认（PENDING），不能发起新的移交", sample.getSealCode()));
        }

        String senderAccount = UserContext.currentName();
        int seq = countTransfers(sample.getId()) + 1;
        LocalDateTime now = LocalDateTime.now();
        SampleTransfer transfer = new SampleTransfer();
        transfer.setSampleId(sample.getId());
        transfer.setTaskId(task.getId());
        transfer.setBatchId(task.getBatchId());
        transfer.setTransferSeq(seq);
        transfer.setHandoverLocation(req.getHandoverLocation().trim());
        transfer.setTransferNote(req.getTransferNote());
        transfer.setFromCustodianName(sample.getCustodianName());
        transfer.setFromCustodianOrg(sample.getCustodianOrg());
        transfer.setSenderName(senderAccount);
        transfer.setSenderDisplay(UserContext.currentName());
        transfer.setSenderConfirmTime(now);
        transfer.setToCustodianName(req.getToCustodianName().trim());
        transfer.setToCustodianOrg(trim(req.getToCustodianOrg()));
        transfer.setStatus(CcConstants.TRANSFER_PENDING);
        transferMapper.insert(transfer);

        opLogService.log(CcConstants.ENT_SAMPLE_TRANSFER, transfer.getId(), task.getBatchId(),
                CcConstants.ACT_SAMPLE_TRANSFER_REQUEST,
                String.format("样本[%s]第%d次移交发起：移交人=%s 已确认，地点=%s，拟接收人=%s（%s）；"
                                + "单据停留待处理，须由不同于移交人的接收人第二账号确认后才完成转移",
                        sample.getSealCode(), seq, senderAccount, transfer.getHandoverLocation(),
                        transfer.getToCustodianName(), nz(transfer.getToCustodianOrg())));
        return transfer;
    }

    /** 接收人确认：必须是不同于移交人的第二个登录账号；确认后持有方转移、只进不退 */
    @Transactional
    public SampleTransfer confirmTransfer(Long transferId, TransferConfirmRequest req) {
        requireManage();
        SampleTransfer transfer = requireTransfer(transferId);
        InspectionTask task = requireTask(transfer.getTaskId());
        if (CcConstants.TRANSFER_CONFIRMED.equals(transfer.getStatus())) {
            throw new BusinessException(String.format(
                    "样本第%d次移交已于 %s 由 %s 双人确认完成，确认事实不可重复/改写",
                    transfer.getTransferSeq(), transfer.getReceiverConfirmTime(), transfer.getReceiverName()));
        }
        String receiverAccount = UserContext.currentName();
        if (receiverAccount.equals(transfer.getSenderName())) {
            throw new BusinessException(String.format(
                    "样本转移必须双人确认：移交人[%s]不能同时作为接收人确认本人发起的移交（封存码所属任务=%s）",
                    transfer.getSenderName(), task.getTaskNo()));
        }

        transfer.setReceiverName(receiverAccount);
        transfer.setReceiverDisplay(UserContext.currentName());
        transfer.setReceiverConfirmTime(LocalDateTime.now());
        if (!isBlank(req.getToCustodianOrg())) {
            transfer.setToCustodianOrg(req.getToCustodianOrg().trim());
        }
        if (!isBlank(req.getReceiverNote())) {
            String note = isBlank(transfer.getTransferNote()) ? "" : transfer.getTransferNote();
            transfer.setTransferNote(note + "｜接收确认：" + req.getReceiverNote().trim());
        }
        transfer.setStatus(CcConstants.TRANSFER_CONFIRMED);
        transferMapper.updateById(transfer);

        InspectionSample sample = requireSample(transfer.getSampleId());
        sample.setStatus(CcConstants.SAMPLE_TRANSFERRED);
        sample.setCustodianName(transfer.getToCustodianName());
        sample.setCustodianOrg(transfer.getToCustodianOrg());
        sample.setTransferCount(sample.getTransferCount() == null ? 1 : sample.getTransferCount() + 1);
        sampleMapper.updateById(sample);

        opLogService.log(CcConstants.ENT_SAMPLE_TRANSFER, transfer.getId(), task.getBatchId(),
                CcConstants.ACT_SAMPLE_TRANSFER_CONFIRM,
                String.format("样本[%s]第%d次移交双人确认完成：移交人=%s，接收人=%s（第二账号），地点=%s；"
                                + "当前持有方由 %s 转移给 %s（%s），转移事实只进不退",
                        sample.getSealCode(), transfer.getTransferSeq(), transfer.getSenderName(),
                        receiverAccount, transfer.getHandoverLocation(),
                        transfer.getFromCustodianName(), transfer.getToCustodianName(),
                        nz(transfer.getToCustodianOrg())));
        return transfer;
    }

    // ---------------- 抽检结论（回写批次独立列，不覆盖原始运输记录） ----------------

    @Transactional
    public InspectionTask conclude(InspectionConcludeRequest req) {
        requireManage();
        InspectionTask task = requireTask(req.getTaskId());
        if (CcConstants.TASK_CONCLUDED.equals(task.getStatus())) {
            throw new BusinessException(String.format(
                    "抽检任务 %s 已于 %s 出具结论（%s），结论一次定稿不能改写",
                    task.getTaskNo(), task.getConcludeTime(), task.getResult()));
        }
        int samples = countSamples(task.getId());
        if (samples == 0) {
            throw new BusinessException(String.format(
                    "抽检任务 %s 尚未封存任何样本，不能出具抽检结论", task.getTaskNo()));
        }
        String result;
        switch (req.getResult().trim().toUpperCase()) {
            case CcConstants.INSPECT_PASS:
                result = CcConstants.INSPECT_PASS;
                break;
            case CcConstants.INSPECT_FAIL:
                result = CcConstants.INSPECT_FAIL;
                break;
            default:
                throw new BusinessException("抽检结论只支持 PASS 合格 / FAIL 不合格，实际: " + req.getResult());
        }

        LocalDateTime now = LocalDateTime.now();
        task.setStatus(CcConstants.TASK_CONCLUDED);
        task.setResult(result);
        task.setConclusion(req.getConclusion().trim());
        task.setConcludeBy(UserContext.currentId());
        task.setConcludeName(UserContext.currentName());
        task.setConcludeTime(now);
        taskMapper.updateById(task);

        // 仅回写批次的独立抽检列：批次状态/温度/异常/交接/结算等原始运输记录一律不动
        TransportBatch batch = requireBatch(task.getBatchId());
        batch.setInspectionTaskId(task.getId());
        batch.setInspectionResult(result);
        batch.setInspectionConclusion(task.getConclusion());
        batch.setInspectionBy(UserContext.currentId());
        batch.setInspectionTime(now);
        batchMapper.updateById(batch);

        opLogService.log(CcConstants.ENT_INSPECTION, task.getId(), task.getBatchId(),
                CcConstants.ACT_INSPECTION_CONCLUDE,
                String.format("抽检任务 %s 出具结论=%s（%d 个样本）并回写批次 %s 独立抽检列；"
                                + "批次状态/温度/异常/交接等原始运输记录均未改动，结论人=%s",
                        task.getTaskNo(), result, samples, batch.getBatchNo(),
                        task.getConcludeName()));
        return task;
    }

    // ---------------- 查询（按监管区域隔离） ----------------

    public List<InspectionTask> listTasks(String region, String status) {
        QueryWrapper<InspectionTask> qw = new QueryWrapper<InspectionTask>()
                .orderByDesc("id");
        if (status != null && !status.trim().isEmpty()) {
            qw.eq("status", status.trim().toUpperCase());
        }
        regionService.applyRegionFilter(qw, trim(region));
        return taskMapper.selectList(qw);
    }

    /** 任务详情：任务 + 样本（含各自转移链）；区域隔离卡口 */
    public Map<String, Object> detail(Long taskId) {
        InspectionTask task = requireTask(taskId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", task);
        List<InspectionSample> samples = sampleMapper.selectList(new QueryWrapper<InspectionSample>()
                .eq("task_id", taskId).orderByAsc("sample_seq"));
        List<Map<String, Object>> sampleList = new ArrayList<>();
        for (InspectionSample s : samples) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("sample", s);
            sm.put("transfers", listTransfers(s.getId()));
            sampleList.add(sm);
        }
        data.put("samples", sampleList);
        return data;
    }

    public List<InspectionTask> listByBatch(Long batchId) {
        TransportBatch batch = requireBatch(batchId);
        if (batch.getSuperviseRegion() != null) {
            regionService.requireAccess(batch.getSuperviseRegion());
        }
        return taskMapper.selectList(new QueryWrapper<InspectionTask>()
                .eq("batch_id", batchId).orderByAsc("id"));
    }

    public List<SampleTransfer> listTransfers(Long sampleId) {
        return transferMapper.selectList(new QueryWrapper<SampleTransfer>()
                .eq("sample_id", sampleId).orderByAsc("transfer_seq"));
    }

    // ---------------- 导出（区域隔离 + 承运人联系方式脱敏） ----------------

    /**
     * 组装抽检样本导出行（CSV）。只导出当前账号授权区域的数据；
     * 承运人联系方式（车辆驾驶员电话）一律脱敏（保留前3后2，中间星号）。
     */
    public List<String[]> exportRows(String region, String status) {
        List<InspectionTask> tasks = listTasks(region, status);
        List<String[]> rows = new ArrayList<>();
        for (InspectionTask task : tasks) {
            TransportBatch batch = batchMapper.selectById(task.getBatchId());
            Vehicle vehicle = batch == null ? null : vehicleMapper.selectById(batch.getVehicleId());
            List<InspectionSample> samples = sampleMapper.selectList(new QueryWrapper<InspectionSample>()
                    .eq("task_id", task.getId()).orderByAsc("sample_seq"));
            for (InspectionSample s : samples) {
                rows.add(new String[]{
                        task.getTaskNo(),
                        task.getSuperviseRegion(),
                        batch == null ? "" : batch.getBatchNo(),
                        vehicle == null ? "" : nz(vehicle.getCarrierName()),
                        vehicle == null ? "" : nz(vehicle.getDriverName()),
                        // 承运人联系方式脱敏
                        vehicle == null ? "" : maskPhone(vehicle.getDriverPhone()),
                        s.getSealCode(),
                        String.valueOf(s.getSampleSeq()),
                        String.valueOf(s.getQuantity()),
                        nz(s.getUnit()),
                        nz(s.getSamplerName()),
                        nz(s.getSampleLocation()),
                        String.valueOf(s.getSealTime()),
                        s.getStatus(),
                        nz(s.getCustodianName()),
                        String.valueOf(s.getTransferCount()),
                        nz(task.getResult()),
                });
        }
        }
        return rows;
    }

    public String[] exportHeader() {
        return new String[]{
                "抽检任务号", "监管区域", "批次号", "承运商", "驾驶员", "承运人联系方式(脱敏)",
                "封存码", "样本序号", "数量", "单位", "采样人", "采样位置", "封存时间",
                "样本状态", "当前持有方", "双人转移次数", "抽检结论"};
    }

    /** 电话脱敏：保留前3位与后2位，中间以 * 代替；长度不足时整体星号化 */
    public static String maskPhone(String phone) {
        if (phone == null) {
            return "";
        }
        String p = phone.trim();
        if (p.isEmpty()) {
            return "";
        }
        if (p.length() < 7) {
            return repeat('*', p.length());
        }
        int star = p.length() - 5;
        return p.substring(0, 3) + repeat('*', star) + p.substring(p.length() - 2);
    }

    // ---------------- 私有工具 ----------------

    /** 办理类动作二次校验 Shiro 权限点（POST 立项路径的过滤器只要求查看权限） */
    private void requireManage() {
        try {
            if (!org.apache.shiro.SecurityUtils.getSubject().isPermitted(CcConstants.PERM_INSPECTION_MANAGE)) {
                throw new BusinessException("Forbidden：当前账号无抽检办理权限（" + CcConstants.PERM_INSPECTION_MANAGE + "）");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception ignored) {
            // 无 Shiro 环境（服务直调）按系统操作者放行，与 UserContext 回退口径一致
        }
    }

    InspectionTask requireTask(Long taskId) {
        InspectionTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("抽检任务不存在: " + taskId);
        }
        regionService.requireAccess(task.getSuperviseRegion());
        return task;
    }

    private InspectionSample requireSample(Long sampleId) {
        InspectionSample sample = sampleMapper.selectById(sampleId);
        if (sample == null) {
            throw new BusinessException("抽检样本不存在: " + sampleId);
        }
        requireTask(sample.getTaskId());
        return sample;
    }

    private SampleTransfer requireTransfer(Long transferId) {
        SampleTransfer transfer = transferMapper.selectById(transferId);
        if (transfer == null) {
            throw new BusinessException("样本转移单不存在: " + transferId);
        }
        requireTask(transfer.getTaskId());
        return transfer;
    }

    private TransportBatch requireBatch(Long batchId) {
        TransportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("运输批次不存在: " + batchId);
        }
        return batch;
    }

    private int countSamples(Long taskId) {
        Long n = sampleMapper.selectCount(new QueryWrapper<InspectionSample>().eq("task_id", taskId));
        return n == null ? 0 : n.intValue();
    }

    private int countTransfers(Long sampleId) {
        Long n = transferMapper.selectCount(new QueryWrapper<SampleTransfer>().eq("sample_id", sampleId));
        return n == null ? 0 : n.intValue();
    }

    /** 抽检任务号：CJ + 8 位序列 */
    private String generateTaskNo() {
        return String.format("CJ%08d", sequenceMapper.nextInspectionNo());
    }

    /** 唯一封存码：FC + 年 + 8 位序列（数据库 uk 兜底唯一，不复用） */
    private String generateSealCode() {
        return String.format("FC%tY%08d", LocalDateTime.now(), sequenceMapper.nextSealNo());
    }

    private static String sha256Hex(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BusinessException("封存照片摘要计算失败: " + e.getMessage());
        }
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String trim(String s) {
        return s == null ? null : (s.trim().isEmpty() ? null : s.trim());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
