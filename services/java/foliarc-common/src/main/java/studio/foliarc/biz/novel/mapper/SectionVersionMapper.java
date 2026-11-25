package studio.foliarc.biz.novel.mapper;

import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import studio.foliarc.biz.novel.entity.SectionVersionEntity;

@Mapper
public interface SectionVersionMapper extends BaseMapper<SectionVersionEntity> {

    @Select("SELECT COALESCE(MAX(version_no), 0) FROM section_versions WHERE section_id = #{sectionId}")
    int findMaxVersionNo(@Param("sectionId") UUID sectionId);
}
