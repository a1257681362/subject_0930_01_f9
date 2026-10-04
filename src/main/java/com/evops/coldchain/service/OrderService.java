package com.evops.coldchain.service;

import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.OrderCreateRequest;
import com.evops.coldchain.entity.Drug;
import com.evops.coldchain.entity.DrugVersion;
import com.evops.coldchain.entity.ShipmentOrder;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.coldchain.mapper.ShipmentOrderMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

/**
 * 发货单服务：创建时锁定档案版本（默认当前版本，也可显式指定旧版本），
 * 锁定后药品档案再变更也不影响本单核对口径。
 */
@Service
public class OrderService {

    @Resource
    private ShipmentOrderMapper orderMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private DrugService drugService;
    @Resource
    private OpLogService opLogService;

    @Transactional
    public ShipmentOrder create(OrderCreateRequest req) {
        Drug drug = drugService.requireDrug(req.getDrugId());
        if (CcConstants.REVOKED.equals(drug.getStatus())) {
            throw new BusinessException("药品档案已撤回，不能下单");
        }

        int pinnedVersion = req.getDrugVersionNo() == null ? drug.getCurrentVersion() : req.getDrugVersionNo();
        // 显式校验锁定版本存在（允许锁定历史版本）
        DrugVersion version = drugService.requireVersion(req.getDrugId(), pinnedVersion);

        ShipmentOrder order = new ShipmentOrder();
        order.setOrderNo("FH" + sequenceMapper.nextOrderNo());
        order.setDrugId(req.getDrugId());
        order.setDrugVersionNo(pinnedVersion);
        order.setReceiverOrg(req.getReceiverOrg());
        order.setShipQty(req.getShipQty());
        order.setSenderOrg(req.getSenderOrg());
        order.setPlanShipTime(req.getPlanShipTime());
        order.setStatus(CcConstants.ORDER_CREATED);
        orderMapper.insert(order);

        opLogService.log(CcConstants.ENT_ORDER, order.getId(), null, CcConstants.ACT_CREATE,
                String.format("发货单 %s 创建：药品=%s，锁定档案版本 V%d（批准文号=%s），收货=%s，数量=%d",
                        order.getOrderNo(), drug.getDrugName(), pinnedVersion,
                        version.getApprovalNo(), req.getReceiverOrg(), req.getShipQty()));
        return order;
    }

    public ShipmentOrder requireOrder(Long orderId) {
        ShipmentOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("发货单不存在: " + orderId);
        }
        return order;
    }
}
