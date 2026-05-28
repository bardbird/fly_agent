package com.fly.agent.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fly.agent.dao.entity.MessageEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {
}
