package com.evops.coldchain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.coldchain.entity.LockedPrice;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LockedPriceMapper extends BaseMapper<LockedPrice> {
}
