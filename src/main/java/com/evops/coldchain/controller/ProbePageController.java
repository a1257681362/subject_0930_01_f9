package com.evops.coldchain.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.entity.ProbeCert;
import com.evops.coldchain.entity.TempProbe;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.entity.VehicleProbe;
import com.evops.coldchain.mapper.ProbeCertMapper;
import com.evops.coldchain.mapper.TempProbeMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.mapper.VehicleProbeMapper;
import com.evops.coldchain.service.VehicleService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 车载温度探头校准凭证管理页面（Thymeleaf 服务端渲染 + fetch 调用既有 API）。
 * 页面与接口共用同一套有效期卡口：过期/作废/无凭证的探头在绑定下拉中不可选，
 * 未绑定有效探头的已派车批次在发运区直接给出拦截提示。
 */
@Controller
@RequestMapping("/ui/cold-chain")
public class ProbePageController {

    @Resource
    private TempProbeMapper probeMapper;
    @Resource
    private ProbeCertMapper certMapper;
    @Resource
    private VehicleProbeMapper bindMapper;
    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private VehicleService vehicleService;

    @GetMapping("/probes")
    public String probes(Model model) {
        List<TempProbe> probes = probeMapper.selectList(new QueryWrapper<TempProbe>().orderByDesc("id"));
        List<Map<String, Object>> probeViews = new ArrayList<>();
        for (TempProbe probe : probes) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("probe", probe);
            ProbeCert cert = probe.getCurrentCertId() == null ? null : certMapper.selectById(probe.getCurrentCertId());
            view.put("cert", cert);
            view.put("certs", certMapper.selectList(new QueryWrapper<ProbeCert>()
                    .eq("probe_id", probe.getId()).orderByDesc("id")));
            view.put("valid", isUsable(probe, cert));
            view.put("statusText", statusText(probe, cert));
            probeViews.add(view);
        }

        List<Vehicle> vehicles = vehicleService.list();
        List<Map<String, Object>> vehicleViews = new ArrayList<>();
        for (Vehicle vehicle : vehicles) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("vehicle", vehicle);
            VehicleProbe binding = currentBinding(vehicle.getId());
            view.put("binding", binding);
            if (binding != null) {
                ProbeCert cert = certMapper.selectById(binding.getCertId());
                view.put("bindValid", cert != null && "VALID".equals(cert.getStatus())
                        && cert.getValidUntil() != null && cert.getValidUntil().isAfter(LocalDateTime.now()));
                view.put("bindCert", cert);
            } else {
                view.put("bindValid", false);
            }
            view.put("history", bindMapper.selectList(new QueryWrapper<VehicleProbe>()
                    .eq("vehicle_id", vehicle.getId()).orderByAsc("id")));
            vehicleViews.add(view);
        }

        // 已派车待发运批次：直接标明是否会被发运卡口拦截
        List<TransportBatch> dispatched = batchMapper.selectList(new QueryWrapper<TransportBatch>()
                .eq("status", "DISPATCHED").orderByAsc("id"));
        List<Map<String, Object>> batchViews = new ArrayList<>();
        for (TransportBatch batch : dispatched) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("batch", batch);
            VehicleProbe binding = currentBinding(batch.getVehicleId());
            boolean canShip = false;
            String blockReason = "车辆尚未绑定车载温度探头";
            if (binding != null) {
                ProbeCert cert = certMapper.selectById(binding.getCertId());
                TempProbe probe = probeMapper.selectById(binding.getProbeId());
                if (probe == null || "REVOKED".equals(probe.getStatus())) {
                    blockReason = "绑定探头已停用（序列号=" + binding.getSerialNoSnap() + "）";
                } else if (cert == null || !"VALID".equals(cert.getStatus())) {
                    blockReason = "校准证书已作废（证书编号=" + binding.getCertNoSnap() + "）";
                } else if (cert.getValidUntil() == null || cert.getValidUntil().isBefore(LocalDateTime.now())) {
                    blockReason = "校准证书已过期（有效期至=" + cert.getValidUntil() + "）";
                } else {
                    canShip = true;
                    blockReason = null;
                }
            }
            view.put("canShip", canShip);
            view.put("blockReason", blockReason);
            view.put("binding", binding);
            batchViews.add(view);
        }

        model.addAttribute("probeViews", probeViews);
        model.addAttribute("vehicleViews", vehicleViews);
        model.addAttribute("batchViews", batchViews);
        return "coldchain/probes";
    }

    private VehicleProbe currentBinding(Long vehicleId) {
        List<VehicleProbe> rows = bindMapper.selectList(new QueryWrapper<VehicleProbe>()
                .eq("vehicle_id", vehicleId)
                .isNull("released_time")
                .orderByDesc("id"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean isUsable(TempProbe probe, ProbeCert cert) {
        return !"REVOKED".equals(probe.getStatus())
                && cert != null && "VALID".equals(cert.getStatus())
                && cert.getValidUntil() != null && cert.getValidUntil().isAfter(LocalDateTime.now());
    }

    private String statusText(TempProbe probe, ProbeCert cert) {
        if ("REVOKED".equals(probe.getStatus())) {
            return "探头已停用";
        }
        if (cert == null) {
            return "无有效凭证";
        }
        if ("VOID".equals(cert.getStatus())) {
            return "凭证已作废";
        }
        if (cert.getValidUntil() == null || cert.getValidUntil().isBefore(LocalDateTime.now())) {
            return "证书已过期";
        }
        return "可绑定（有效期内）";
    }
}
