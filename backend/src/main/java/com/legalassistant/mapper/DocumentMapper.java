package com.legalassistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.legalassistant.entity.Document;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentMapper extends BaseMapper<Document> {
}
