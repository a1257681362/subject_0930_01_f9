package com.evops.coldchain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.coldchain.entity.TemperatureLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TemperatureLogMapper extends BaseMapper<TemperatureLog> {
}
