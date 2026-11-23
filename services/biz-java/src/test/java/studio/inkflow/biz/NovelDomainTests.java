package studio.inkflow.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import studio.inkflow.biz.common.BizException;
import studio.inkflow.biz.novel.dto.NovelDtos;
import studio.inkflow.biz.novel.entity.BookEntity;
import studio.inkflow.biz.novel.entity.ChapterEntity;
import studio.inkflow.biz.novel.entity.ChapterVersionEntity;
import studio.inkflow.biz.novel.mapper.BookMapper;
import studio.inkflow.biz.novel.mapper.ChapterMapper;
import studio.inkflow.biz.novel.mapper.ChapterVersionMapper;
import studio.inkflow.biz.novel.service.BookService;
import studio.inkflow.biz.novel.service.ChapterService;
import studio.inkflow.biz.novel.service.WordCounter;

class NovelDomainTests {

    @Test
    void wordCounterCountsChineseAndLatinCharactersWithoutWhitespace() {
        assertThat(WordCounter.count(null)).isZero();
        assertThat(WordCounter.count("   \n\t ")).isZero();
        assertThat(WordCounter.count("夜色如墨，剑光如霜。\nChapter 1")).isEqualTo(18);
    }

    @Test
    void bookServiceBlocksCrossUserAccessAndVersionConflicts() {
        BookMapper bookMapper = mock(BookMapper.class);
        ChapterMapper chapterMapper = mock(ChapterMapper.class);
        BookService service = new BookService(bookMapper, chapterMapper);

        UUID ownerId = UUID.randomUUID();
        UUID intruderId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();

        BookEntity book = new BookEntity();
        book.setId(bookId);
        book.setOwnerId(ownerId);
        book.setTitle("星渊纪元");
        book.setStatus("DRAFT");
        book.setVersion(2L);

        when(bookMapper.selectById(bookId)).thenReturn(book);

        assertThatThrownBy(() -> service.requireOwnedBook(intruderId, bookId))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        when(bookMapper.updateById(any(BookEntity.class))).thenReturn(0);
        assertThatThrownBy(() -> service.updateBook(
                ownerId,
                bookId,
                new NovelDtos.UpdateBookRequest("星渊纪元·改", "科幻", "简介", "ONGOING", 1L)
        ))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> {
                    BizException biz = (BizException) ex;
                    assertThat(biz.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(biz.getCode()).isEqualTo("VERSION_CONFLICT");
                });
    }

    @Test
    void chapterUpdateAndRollbackRecordSnapshotsAndEnforceOptimisticLock() {
        BookMapper bookMapper = mock(BookMapper.class);
        ChapterMapper chapterMapper = mock(ChapterMapper.class);
        ChapterVersionMapper versionMapper = mock(ChapterVersionMapper.class);
        BookService bookService = new BookService(bookMapper, chapterMapper);
        ChapterService chapterService = new ChapterService(bookService, chapterMapper, versionMapper);

        UUID ownerId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        UUID chapterId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        BookEntity book = new BookEntity();
        book.setId(bookId);
        book.setOwnerId(ownerId);
        when(bookMapper.selectById(bookId)).thenReturn(book);

        ChapterEntity chapter = new ChapterEntity();
        chapter.setId(chapterId);
        chapter.setBookId(bookId);
        chapter.setChapterNo(1);
        chapter.setTitle("第一章 启程");
        chapter.setContent("新版内容");
        chapter.setWordCount(4);
        chapter.setStatus("DRAFT");
        chapter.setVersion(3L);
        when(chapterMapper.selectById(chapterId)).thenReturn(chapter);

        ChapterVersionEntity oldSnapshot = new ChapterVersionEntity();
        oldSnapshot.setId(versionId);
        oldSnapshot.setChapterId(chapterId);
        oldSnapshot.setVersionNo(1);
        oldSnapshot.setContent("旧版原稿");
        when(versionMapper.selectById(versionId)).thenReturn(oldSnapshot);
        when(versionMapper.findMaxVersionNo(chapterId)).thenReturn(1);
        when(chapterMapper.updateById(any(ChapterEntity.class))).thenReturn(1);

        NovelDtos.ChapterDetailResponse rolled = chapterService.rollbackToVersion(
                ownerId,
                bookId,
                chapterId,
                versionId,
                new NovelDtos.RollbackChapterRequest(3L)
        );
        assertThat(rolled.content()).isEqualTo("旧版原稿");
        assertThat(rolled.wordCount()).isEqualTo(4);
        verify(versionMapper).insert(any(ChapterVersionEntity.class));
    }
}
