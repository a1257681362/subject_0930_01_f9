package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.CarrierCreateRequest;
import com.evops.coldchain.entity.Carrier;
import com.evops.coldchain.mapper.CarrierMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

/** 承运商主档服务（V8）：报价与锁价的承运商主体以主档为准。 */
@Service
public class CarrierService {

    @Resource
    private CarrierMapper carrierMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private OpLogService opLogService;

    @Transactional
    public Carrier create(CarrierCreateRequest req) {
        String name = req.getCarrierName().trim();
        Long dup = carrierMapper.selectCount(new QueryWrapper<Carrier>().eq("carrier_name", name));
        if (dup != null && dup > 0) {
            throw new BusinessException("承运商名称已存在: " + name);
        }
        Carrier carrier = new Carrier();
        carrier.setCarrierNo(generateCarrierNo());
        carrier.setCarrierName(name);
        carrier.setContactName(req.getContactName());
        carrier.setContactPhone(req.getContactPhone());
        carrier.setQualification(req.getQualification());
        carrier.setRemark(req.getRemark());
        carrier.setStatus(CcConstants.ACTIVE);
        carrierMapper.insert(carrier);

        opLogService.log(CcConstants.ENT_CARRIER, carrier.getId(), null, CcConstants.ACT_CARRIER_CREATE,
                String.format("承运商建档：%s（%s），联系人=%s，资质=%s",
                        carrier.getCarrierNo(), name,
                        req.getContactName() == null ? "" : req.getContactName(),
                        req.getQualification() == null ? "" : req.getQualification()));
        return carrier;
    }

    public Carrier requireActiveCarrier(Long carrierId) {
        Carrier carrier = carrierId == null ? null : carrierMapper.selectById(carrierId);
        if (carrier == null) {
            throw new BusinessException("承运商不存在: " + carrierId);
        }
        if (CcConstants.REVOKED.equals(carrier.getStatus())) {
            throw new BusinessException("承运商已停用: " + carrier.getCarrierName());
        }
        return carrier;
    }

    public List<Carrier> list() {
        return carrierMapper.selectList(new QueryWrapper<Carrier>().orderByDesc("id"));
    }

    /** 承运商编号：CY + 9 位序列 */
    private String generateCarrierNo() {
        return String.format("CY%09d", sequenceMapper.nextCarrierNo());
    }
}
