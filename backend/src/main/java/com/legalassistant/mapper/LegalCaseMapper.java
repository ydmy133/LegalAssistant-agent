package com.legalassistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.legalassistant.entity.LegalCase;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LegalCaseMapper extends BaseMapper<LegalCase> {
}
