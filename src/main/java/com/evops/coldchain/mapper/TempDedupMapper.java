package com.evops.coldchain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.coldchain.entity.TempDedup;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TempDedupMapper extends BaseMapper<TempDedup> {
}
