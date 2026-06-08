package com.legalassistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.legalassistant.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
}
