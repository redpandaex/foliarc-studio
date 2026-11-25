package studio.foliarc.biz.memory.mapper;

import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import studio.foliarc.biz.memory.entity.SectionSummaryEntity;

@Mapper
public interface SectionSummaryMapper extends BaseMapper<SectionSummaryEntity> {

    @Select("""
            SELECT summary.ending_hook
            FROM chapter_sections section
            LEFT JOIN section_summaries summary ON summary.section_id = section.id AND summary.status = 'READY'
            WHERE section.book_id = #{bookId} AND section.narrative_order < #{currentOrder}
            ORDER BY section.narrative_order DESC LIMIT 1
            """)
    String findPreviousEndingHook(@Param("bookId") UUID bookId, @Param("currentOrder") long currentOrder);

    @Select("""
            SELECT summary.narrative_order FROM section_summaries summary
            JOIN chapters chapter ON chapter.id = summary.chapter_id
            WHERE chapter.volume_id = #{volumeId} AND summary.status = 'READY'
              AND summary.narrative_order > #{afterOrder}
            ORDER BY summary.narrative_order LIMIT 10
            """)
    List<Long> readyOrdersInVolume(@Param("volumeId") UUID volumeId, @Param("afterOrder") long afterOrder);
}
