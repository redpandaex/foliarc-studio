package studio.foliarc.biz.novel.mapper;

import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import studio.foliarc.biz.novel.entity.ChapterSectionEntity;

@Mapper
public interface ChapterSectionMapper extends BaseMapper<ChapterSectionEntity> {

    @Select("SELECT COALESCE(MAX(section_no), 0) FROM chapter_sections WHERE chapter_id = #{chapterId}")
    int findMaxSectionNo(@Param("chapterId") UUID chapterId);

    @Select("SELECT COALESCE(MAX(narrative_order), 0) FROM chapter_sections WHERE book_id = #{bookId}")
    long findMaxNarrativeOrder(@Param("bookId") UUID bookId);

    @Select("SELECT LEFT(content, 500) FROM chapter_sections WHERE book_id = #{bookId} ORDER BY narrative_order LIMIT 1")
    String firstSectionSample(@Param("bookId") UUID bookId);

    @Select("SELECT LEFT(content, 500) FROM chapter_sections WHERE book_id = #{bookId} ORDER BY narrative_order DESC LIMIT 1")
    String latestSectionSample(@Param("bookId") UUID bookId);

    static LambdaQueryWrapper<ChapterSectionEntity> inChapter(UUID chapterId) {
        return new LambdaQueryWrapper<ChapterSectionEntity>().eq(ChapterSectionEntity::getChapterId, chapterId);
    }
}
