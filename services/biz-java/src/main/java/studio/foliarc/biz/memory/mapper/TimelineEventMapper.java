package studio.foliarc.biz.memory.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import studio.foliarc.biz.memory.entity.TimelineEventEntity;

@Mapper
public interface TimelineEventMapper extends BaseMapper<TimelineEventEntity> {
}
