package studio.foliarc.biz.ripple.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import studio.foliarc.biz.ripple.dto.RippleDtos.InternalCandidateSection;
import studio.foliarc.biz.ripple.entity.RippleJobEntity;

@Mapper
public interface RippleCandidateMapper {

    @Select("""
            <script>
            WITH search_terms(term) AS (
                VALUES <foreach collection="terms" item="term" separator=",">(#{term}::text)</foreach>
            )
            SELECT s.id AS section_id, s.chapter_id, s.chapter_no, s.section_no, s.narrative_order,
                   c.title AS chapter_title, s.title AS section_title, s.version,
                   COALESCE(m.summary_text, '') AS summary_text, s.content,
                   CASE WHEN hits.summary_hits > 0 AND hits.content_hits > 0 THEN 'SUMMARY_AND_CONTENT'
                        WHEN hits.summary_hits > 0 THEN 'SUMMARY'
                        ELSE 'CONTENT' END AS matched_via
            FROM chapter_sections s
            JOIN chapters c ON c.id = s.chapter_id
            LEFT JOIN section_summaries m ON m.section_id = s.id AND m.book_id = s.book_id
            CROSS JOIN LATERAL (
                SELECT count(*) FILTER (WHERE strpos(lower(COALESCE(m.summary_text, '')), term) > 0)
                       + count(*) FILTER (WHERE strpos(lower(COALESCE(m.involved_characters, '')), term) > 0)
                       + count(*) FILTER (WHERE strpos(lower(COALESCE(m.state_changes, '')), term) > 0)
                         AS summary_hits,
                       count(*) FILTER (WHERE strpos(lower(s.content), term) > 0) AS content_hits
                FROM search_terms
            ) hits
            WHERE s.book_id = #{job.bookId}
              AND s.narrative_order BETWEEN #{job.rangeStartOrder} AND #{job.rangeEndOrder}
              AND s.id != #{job.sourceSectionId}
              AND hits.summary_hits + hits.content_hits > 0
            ORDER BY hits.summary_hits + hits.content_hits DESC,
                     abs(s.narrative_order - #{job.sourceNarrativeOrder}), s.narrative_order
            LIMIT #{limit}
            </script>
            """)
    List<InternalCandidateSection> findCandidates(
            @Param("job") RippleJobEntity job, @Param("terms") List<String> terms, @Param("limit") int limit
    );
}
