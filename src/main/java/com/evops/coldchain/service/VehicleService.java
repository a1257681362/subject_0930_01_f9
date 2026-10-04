package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.VehicleCreateRequest;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.mapper.VehicleMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

/** 承运车辆登记 */
@Service
public class VehicleService {

    @Resource
    private VehicleMapper vehicleMapper;
    @Resource
    private OpLogService opLogService;

    @Transactional
    public Vehicle create(VehicleCreateRequest req) {
        Long exists = vehicleMapper.selectCount(new QueryWrapper<Vehicle>()
                .eq("plate_no", req.getPlateNo()));
        if (exists != null && exists > 0) {
            throw new BusinessException("车牌已登记: " + req.getPlateNo());
        }

        Vehicle vehicle = new Vehicle();
        vehicle.setPlateNo(req.getPlateNo());
        vehicle.setCarrierName(req.getCarrierName());
        vehicle.setDriverName(req.getDriverName());
        vehicle.setDriverPhone(req.getDriverPhone());
        vehicle.setFridgeModel(req.getFridgeModel());
        vehicle.setStatus(CcConstants.ACTIVE);
        vehicleMapper.insert(vehicle);

        opLogService.log(CcConstants.ENT_VEHICLE, vehicle.getId(), null, CcConstants.ACT_CREATE,
                String.format("车辆登记：%s / %s / 司机=%s", req.getPlateNo(), req.getCarrierName(), req.getDriverName()));
        return vehicle;
    }

    public Vehicle requireActiveVehicle(Long vehicleId) {
        Vehicle vehicle = vehicleMapper.selectById(vehicleId);
        if (vehicle == null) {
            throw new BusinessException("承运车辆不存在: " + vehicleId);
        }
        if (CcConstants.REVOKED.equals(vehicle.getStatus())) {
            throw new BusinessException("车辆已停用: " + vehicle.getPlateNo());
        }
        return vehicle;
    }

    public List<Vehicle> list() {
        return vehicleMapper.selectList(new QueryWrapper<Vehicle>().orderByDesc("id"));
    }
}
