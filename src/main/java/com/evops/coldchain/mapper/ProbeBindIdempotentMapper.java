package com.evops.coldchain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.coldchain.entity.ProbeBindIdempotent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProbeBindIdempotentMapper extends BaseMapper<ProbeBindIdempotent> {
}
