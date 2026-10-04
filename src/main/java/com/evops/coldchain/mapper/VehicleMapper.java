package com.evops.coldchain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.coldchain.entity.Vehicle;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface VehicleMapper extends BaseMapper<Vehicle> {
}
