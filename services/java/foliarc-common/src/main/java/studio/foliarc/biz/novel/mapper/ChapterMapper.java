package studio.foliarc.biz.novel.mapper;

import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import studio.foliarc.biz.novel.entity.ChapterEntity;

@Mapper
public interface ChapterMapper extends BaseMapper<ChapterEntity> {

    @Select("SELECT id FROM chapters WHERE book_id = #{bookId} AND id = #{chapterId} FOR NO KEY UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    UUID lockForStructure(@Param("bookId") UUID bookId, @Param("chapterId") UUID chapterId);

    @Select("SELECT COALESCE(MAX(chapter_no), 0) FROM chapters WHERE book_id = #{bookId}")
    int findMaxChapterNo(@Param("bookId") UUID bookId);

    @Select("""
            SELECT volume_id, MIN(chapter_no) AS start_chapter_no, MAX(chapter_no) AS end_chapter_no
            FROM chapters WHERE book_id = #{bookId} AND volume_id IS NOT NULL
            GROUP BY volume_id
            """)
    List<VolumeChapterRange> findVolumeChapterRanges(@Param("bookId") UUID bookId);

    @Select("SELECT book_id, COUNT(*)::int AS chapter_count, COALESCE(SUM(word_count), 0)::bigint AS total_word_count "
            + "FROM chapters WHERE book_id IN (SELECT id FROM books WHERE owner_id = #{ownerId}) GROUP BY book_id")
    List<BookChapterStats> findStatsByOwnerId(@Param("ownerId") UUID ownerId);

    class BookChapterStats {
        private UUID bookId;
        private int chapterCount;
        private long totalWordCount;

        public UUID getBookId() { return bookId; }
        public void setBookId(UUID bookId) { this.bookId = bookId; }
        public int getChapterCount() { return chapterCount; }
        public void setChapterCount(int chapterCount) { this.chapterCount = chapterCount; }
        public long getTotalWordCount() { return totalWordCount; }
        public void setTotalWordCount(long totalWordCount) { this.totalWordCount = totalWordCount; }
    }

    class VolumeChapterRange {
        private UUID volumeId;
        private Integer startChapterNo;
        private Integer endChapterNo;

        public UUID getVolumeId() { return volumeId; }
        public void setVolumeId(UUID volumeId) { this.volumeId = volumeId; }
        public Integer getStartChapterNo() { return startChapterNo; }
        public void setStartChapterNo(Integer startChapterNo) { this.startChapterNo = startChapterNo; }
        public Integer getEndChapterNo() { return endChapterNo; }
        public void setEndChapterNo(Integer endChapterNo) { this.endChapterNo = endChapterNo; }
    }
}
