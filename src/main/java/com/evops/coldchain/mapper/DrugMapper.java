package com.evops.coldchain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.coldchain.entity.Drug;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DrugMapper extends BaseMapper<Drug> {
}
