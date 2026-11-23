package studio.inkflow.biz.novel.mapper;

import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import studio.inkflow.biz.novel.entity.ChapterVersionEntity;

@Mapper
public interface ChapterVersionMapper extends BaseMapper<ChapterVersionEntity> {

    @Select("SELECT COALESCE(MAX(version_no), 0) FROM chapter_versions WHERE chapter_id = #{chapterId}::uuid")
    int findMaxVersionNo(@Param("chapterId") UUID chapterId);
}
