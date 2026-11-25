package studio.foliarc.biz.rag.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import studio.foliarc.biz.rag.entity.KnowledgeDocumentEntity;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentEntity> {
}
