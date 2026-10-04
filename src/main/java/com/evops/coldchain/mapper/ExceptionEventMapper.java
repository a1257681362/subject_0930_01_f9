package com.evops.coldchain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.coldchain.entity.ExceptionEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ExceptionEventMapper extends BaseMapper<ExceptionEvent> {
}
