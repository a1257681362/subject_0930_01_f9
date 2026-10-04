package com.evops.coldchain.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.HandoverOrder;
import com.evops.coldchain.entity.HandoverSeal;
import com.evops.coldchain.entity.PermissionSetting;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.mapper.VehicleMapper;
import com.evops.coldchain.service.HandoverService;
import com.evops.coldchain.service.PermissionService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 冷链批次分仓交接管理页面（Thymeleaf 服务端渲染 + fetch 调用既有 API）。
 * 页面与接口共用同一套卡口：箱数不平/封签重复/温度缺失/未核验完/权限收紧时按钮动作都会被拦截，
 * 页面直接展示停点说明；权限收紧时顶部红色横幅提示“停止推进、已确认事实保留”。
 */
@Controller
@RequestMapping("/ui/cold-chain")
public class HandoverPageController {

    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private VehicleMapper vehicleMapper;
    @Resource
    private HandoverService handoverService;
    @Resource
    private PermissionService permissionService;

    @GetMapping("/handovers")
    public String handovers(Model model) {
        boolean permitted = permissionService.isPermitted(CcConstants.PERM_COLD_CHAIN_HANDOVER);
        PermissionSetting setting = permissionService.get(CcConstants.PERM_COLD_CHAIN_HANDOVER);
        model.addAttribute("permitted", permitted);
        model.addAttribute("permSetting", setting);

        // 可办理交接的批次：在途（SHIPPED）或异常挂起（EXCEPTION）；已签收/已撤回不展示
        List<TransportBatch> batches = batchMapper.selectList(new QueryWrapper<TransportBatch>()
                .in("status", CcConstants.BATCH_SHIPPED, CcConstants.BATCH_EXCEPTION)
                .orderByDesc("id"));

        List<Map<String, Object>> arriveCandidates = new ArrayList<>();
        List<Map<String, Object>> createCandidates = new ArrayList<>();
        List<Map<String, Object>> batchViews = new ArrayList<>();

        for (TransportBatch batch : batches) {
            Vehicle vehicle = vehicleMapper.selectById(batch.getVehicleId());
            Map<String, Object> base = new LinkedHashMap<>();
            base.put("batch", batch);
            base.put("plateNo", vehicle == null ? "" : vehicle.getPlateNo());

            if (batch.getArrivedBoxQty() == null) {
                arriveCandidates.add(base);
                continue;
            }

            // 已到仓登记：可继续拆单，直到箱数平衡且全部提交
            Map<String, Object> progress = handoverService.progress(batch.getId());
            if (!Boolean.TRUE.equals(progress.get("readyForDispatch"))) {
                createCandidates.add(base);
            }

            List<HandoverOrder> orders = handoverService.listByBatch(batch.getId());
            List<Map<String, Object>> orderViews = new ArrayList<>();
            for (HandoverOrder o : orders) {
                List<HandoverSeal> seals = handoverService.listSeals(o.getId());
                int confirmed = 0;
                for (HandoverSeal s : seals) {
                    if (CcConstants.SEAL_CONFIRMED.equals(s.getVerifyStatus())) {
                        confirmed++;
                    }
                }
                Map<String, Object> ov = new LinkedHashMap<>();
                ov.put("order", o);
                ov.put("seals", seals);
                ov.put("confirmed", confirmed);
                orderViews.add(ov);
            }

            Map<String, Object> view = new LinkedHashMap<>(base);
            view.put("progress", progress);
            view.put("orders", orderViews);
            batchViews.add(view);
        }

        model.addAttribute("arriveCandidates", arriveCandidates);
        model.addAttribute("createCandidates", createCandidates);
        model.addAttribute("batchViews", batchViews);
        return "coldchain/handovers";
    }
}
