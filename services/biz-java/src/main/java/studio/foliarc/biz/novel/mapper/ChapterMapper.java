package studio.foliarc.biz.novel.mapper;

import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import studio.foliarc.biz.novel.entity.ChapterEntity;

@Mapper
public interface ChapterMapper extends BaseMapper<ChapterEntity> {

    @Select("SELECT COALESCE(MAX(chapter_no), 0) FROM chapters WHERE book_id = #{bookId}")
    int findMaxChapterNo(@Param("bookId") UUID bookId);

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
}
