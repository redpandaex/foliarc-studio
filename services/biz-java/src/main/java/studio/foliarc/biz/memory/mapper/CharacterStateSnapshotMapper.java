package studio.foliarc.biz.memory.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import studio.foliarc.biz.memory.entity.CharacterStateSnapshotEntity;

@Mapper
public interface CharacterStateSnapshotMapper extends BaseMapper<CharacterStateSnapshotEntity> {
}
