package com.evops.coldchain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.coldchain.entity.OpLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OpLogMapper extends BaseMapper<OpLog> {
}
