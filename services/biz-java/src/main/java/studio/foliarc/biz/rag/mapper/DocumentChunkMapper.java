package studio.foliarc.biz.rag.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import studio.foliarc.biz.rag.entity.DocumentChunkEntity;

@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunkEntity> {
}
