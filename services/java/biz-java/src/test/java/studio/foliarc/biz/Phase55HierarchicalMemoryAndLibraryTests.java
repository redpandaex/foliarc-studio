package studio.foliarc.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.lore.dto.LoreDtos;
import studio.foliarc.biz.lore.service.LoreService;
import studio.foliarc.biz.memory.entity.ChapterSummaryEntity;
import studio.foliarc.biz.memory.entity.VolumeSummaryEntity;
import studio.foliarc.biz.memory.mapper.ChapterSummaryMapper;
import studio.foliarc.biz.memory.mapper.CharacterRelationMapper;
import studio.foliarc.biz.memory.mapper.VolumeSummaryMapper;
import studio.foliarc.biz.memory.service.StoryMemoryContextService;
import studio.foliarc.biz.novel.entity.BookEntity;
import studio.foliarc.biz.novel.entity.ChapterEntity;
import studio.foliarc.biz.novel.mapper.BookMapper;
import studio.foliarc.biz.novel.mapper.ChapterMapper;
import studio.foliarc.biz.novel.service.BookService;
import studio.foliarc.biz.outbox.RabbitMqTopologyConfig;
import studio.foliarc.biz.outbox.service.OutboxService;
import studio.foliarc.biz.rag.dto.RagDtos;
import studio.foliarc.biz.rag.entity.KnowledgeDocumentEntity;
import studio.foliarc.biz.rag.mapper.BookKnowledgeBindingMapper;
import studio.foliarc.biz.rag.mapper.DocumentChunkMapper;
import studio.foliarc.biz.rag.mapper.KnowledgeDocumentMapper;
import studio.foliarc.biz.rag.mapper.KnowledgeGrantMapper;
import studio.foliarc.biz.rag.service.KnowledgeDocumentService;
import studio.foliarc.biz.storage.MinioStorageService;

class Phase55HierarchicalMemoryAndLibraryTests {

    @Test
    void fiveLayerContextScalesToChapter100WithVolumeMilestonesAndEntityScoping() {
        BookService bookService = mock(BookService.class);
        BookMapper bookMapper = mock(BookMapper.class);
        ChapterMapper chapterMapper = mock(ChapterMapper.class);
        ChapterSummaryMapper chapterSummaryMapper = mock(ChapterSummaryMapper.class);
        VolumeSummaryMapper volumeSummaryMapper = mock(VolumeSummaryMapper.class);
        LoreService loreService = mock(LoreService.class);
        CharacterRelationMapper relationMapper = mock(CharacterRelationMapper.class);
        OutboxService outboxService = mock(OutboxService.class);

        StoryMemoryContextService svc = new StoryMemoryContextService(
                bookService, bookMapper, chapterMapper, chapterSummaryMapper,
                volumeSummaryMapper, loreService, relationMapper, outboxService
        );

        UUID ownerId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        BookEntity book = new BookEntity();
        book.setId(bookId);
        book.setOwnerId(ownerId);
        book.setTitle("星渊百章录");
        book.setAuthorCharter("第三人称限知，冷峻玄幻基调，主角林青背负青铜令之谜。");
        book.setAutoCharter("AI 自动整理的旧草稿（不应覆盖作者锁定版本）");
        book.setCharterSource("AUTHOR_DEFINED");

        ChapterEntity ch95 = new ChapterEntity();
        ch95.setId(UUID.randomUUID());
        ch95.setBookId(bookId);
        ch95.setChapterNo(95);
        ch95.setTitle("北境兵变");
        ch95.setContent("第95章正文略");

        ChapterEntity ch99 = new ChapterEntity();
        ch99.setId(UUID.randomUUID());
        ch99.setBookId(bookId);
        ch99.setChapterNo(99);
        ch99.setTitle("魔渊前夜");
        ch99.setContent("寒风猎猎，林青与苏晚晴立于魔渊断桥之上，青铜令骤然发烫。");

        ChapterEntity ch100 = new ChapterEntity();
        ch100.setId(UUID.randomUUID());
        ch100.setBookId(bookId);
        ch100.setChapterNo(100);
        ch100.setTitle("决战星渊");

        when(chapterMapper.selectList(any())).thenReturn(List.of(ch95, ch99));

        VolumeSummaryEntity vol1 = new VolumeSummaryEntity();
        vol1.setVolumeNo(1);
        vol1.setStartChapterNo(1);
        vol1.setEndChapterNo(10);
        vol1.setSummaryText("第一卷：林青于青云宗得玄铁剑与青铜令，初揭师门灭门阴谋。");
        when(volumeSummaryMapper.selectList(any())).thenReturn(List.of(vol1));

        ChapterSummaryEntity sum95 = new ChapterSummaryEntity();
        sum95.setChapterNo(95);
        sum95.setChapterTitle("北境兵变");
        sum95.setSummaryText("林青潜入北境大营救出苏晚晴。");
        sum95.setEndingHook("魔渊封印出现裂痕。");
        ChapterSummaryEntity sum99 = new ChapterSummaryEntity();
        sum99.setChapterNo(99);
        sum99.setChapterTitle("魔渊前夜");
        sum99.setSummaryText("二人抵达魔渊断桥。");
        sum99.setInvolvedCharacters("林青, 苏晚晴");
        sum99.setEndingHook("青铜令指向渊底祭坛。");
        when(chapterSummaryMapper.selectList(any())).thenReturn(List.of(sum95, sum99));

        // 3 characters: protagonist (Lin Qing), mentioned in Ch.99 (Su Wanqing), unrelated extra (Roadside Boss)
        when(loreService.listCharacters(ownerId, bookId, null)).thenReturn(List.of(
                new LoreDtos.CharacterResponse(UUID.randomUUID(), bookId, "林青", "PROTAGONIST", "冷峻", "元婴初期", "魔渊断桥", "", "玄铁剑", null),
                new LoreDtos.CharacterResponse(UUID.randomUUID(), bookId, "苏晚晴", "SUPPORTING", "沉稳", "金丹巅峰", "魔渊断桥", "", "", null),
                new LoreDtos.CharacterResponse(UUID.randomUUID(), bookId, "无关龙套甲", "SUPPORTING", "", "炼气一层", "江南客栈", "", "", null)
        ));
        when(loreService.listForeshadowings(ownerId, bookId, "OPEN")).thenReturn(List.of(
                new LoreDtos.ForeshadowingResponse(UUID.randomUUID(), bookId, "青铜令真身", "开启星渊祭坛的钥匙", 1, null, "OPEN", null)
        ));
        when(relationMapper.selectList(any())).thenReturn(List.of());

        StoryMemoryContextService.FiveLayerContext ctx = svc.buildFiveLayerContext(
                ownerId, book, ch100, "", "开启祭坛大战"
        );

        // L0: Author charter wins over auto charter
        assertThat(ctx.storyCharter()).contains("主角林青背负青铜令之谜").doesNotContain("不应覆盖");
        // L1: Immediate Ch.99 tail verbatim
        assertThat(ctx.hotWindowContext()).contains("第 99 章《魔渊前夜》").contains("青铜令骤然发烫");
        // L2: Volume 1 milestone + recent Ch.95 summary + Ch.99 hook
        assertThat(ctx.rollingSummaryContext())
                .contains("第 1 卷（第 1~10 章）")
                .contains("第 95 章《北境兵变》")
                .contains("青铜令指向渊底祭坛");
        // L3: Entity-scoped (includes Lin Qing & Su Wanqing, excludes unrelated extra)
        assertThat(ctx.activeStateBrief())
                .contains("林青")
                .contains("苏晚晴")
                .contains("青铜令真身")
                .doesNotContain("无关龙套甲");
    }

    @Test
    void autoCharterNeverOverwritesAuthorDefinedCharterAndCascadeInvalidationMarksStale() {
        BookService bookService = mock(BookService.class);
        BookMapper bookMapper = mock(BookMapper.class);
        ChapterMapper chapterMapper = mock(ChapterMapper.class);
        ChapterSummaryMapper chapterSummaryMapper = mock(ChapterSummaryMapper.class);
        VolumeSummaryMapper volumeSummaryMapper = mock(VolumeSummaryMapper.class);
        LoreService loreService = mock(LoreService.class);
        CharacterRelationMapper relationMapper = mock(CharacterRelationMapper.class);
        OutboxService outboxService = mock(OutboxService.class);

        StoryMemoryContextService svc = new StoryMemoryContextService(
                bookService, bookMapper, chapterMapper, chapterSummaryMapper,
                volumeSummaryMapper, loreService, relationMapper, outboxService
        );

        UUID ownerId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        BookEntity lockedBook = new BookEntity();
        lockedBook.setId(bookId);
        lockedBook.setOwnerId(ownerId);
        lockedBook.setCharterSource("AUTHOR_DEFINED");
        lockedBook.setAuthorCharter("作者锁定档案");
        when(bookService.getBookByIdUnchecked(bookId)).thenReturn(lockedBook);

        boolean updated = svc.saveAutoCharter(bookId, new RagDtos.InternalSaveAutoCharterRequest("AI 新归纳", 10));
        assertThat(updated).isFalse();
        verify(bookMapper, never()).updateById(any(BookEntity.class));

        // Cascade invalidation: editing Chapter 10 marks existing summary + Volume 1 STALE and enqueues on RK_SUMMARY
        ChapterEntity ch10 = new ChapterEntity();
        ch10.setId(UUID.randomUUID());
        ch10.setBookId(bookId);
        ch10.setChapterNo(10);
        ch10.setTitle("卷末转折");
        ch10.setContent("修改后的第十章正文内容，字数足够触发级联摘要重算。".repeat(4));
        ch10.setContentHash(StoryMemoryContextService.sha256Hex(ch10.getContent()));

        ChapterSummaryEntity oldSum = new ChapterSummaryEntity();
        oldSum.setId(UUID.randomUUID());
        oldSum.setChapterId(ch10.getId());
        oldSum.setContentHash("old-hash");
        oldSum.setStatus("READY");
        when(chapterSummaryMapper.selectOne(any())).thenReturn(oldSum);

        VolumeSummaryEntity oldVol = new VolumeSummaryEntity();
        oldVol.setId(UUID.randomUUID());
        oldVol.setVolumeNo(1);
        oldVol.setStatus("READY");
        when(volumeSummaryMapper.selectOne(any())).thenReturn(oldVol);

        svc.invalidateAndEnqueueSummary(ownerId, bookId, ch10);
        assertThat(oldSum.getStatus()).isEqualTo("STALE");
        assertThat(oldVol.getStatus()).isEqualTo("STALE");
        verify(outboxService).enqueue(
                eq("CHAPTER"),
                eq(ch10.getId()),
                eq("CHAPTER_SUMMARY"),
                eq(RabbitMqTopologyConfig.RK_SUMMARY),
                any(),
                any()
        );
    }

    @Test
    void personalLibraryBlocksDeletingReadonlyPlatformDocsAndMatchesGenreTags() {
        BookService bookService = mock(BookService.class);
        ChapterMapper chapterMapper = mock(ChapterMapper.class);
        KnowledgeDocumentMapper docMapper = mock(KnowledgeDocumentMapper.class);
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        KnowledgeGrantMapper grantMapper = mock(KnowledgeGrantMapper.class);
        BookKnowledgeBindingMapper bindingMapper = mock(BookKnowledgeBindingMapper.class);
        MinioStorageService minio = mock(MinioStorageService.class);
        OutboxService outbox = mock(OutboxService.class);
        RestClient restClient = mock(RestClient.class);

        KnowledgeDocumentService svc = new KnowledgeDocumentService(
                bookService, chapterMapper, docMapper, chunkMapper, grantMapper,
                bindingMapper, minio, outbox, restClient
        );

        UUID authorId = UUID.randomUUID();
        UUID platformDocId = UUID.randomUUID();
        KnowledgeDocumentEntity platformDoc = new KnowledgeDocumentEntity();
        platformDoc.setId(platformDocId);
        platformDoc.setOwnerId(UUID.randomUUID());
        platformDoc.setScope("GLOBAL");
        platformDoc.setIsReadonly(true);
        when(docMapper.selectById(platformDocId)).thenReturn(platformDoc);

        assertThatThrownBy(() -> svc.deletePersonalDocument(authorId, platformDocId))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(KnowledgeDocumentService.genreMatches("玄幻,修仙", "东方玄幻")).isTrue();
        assertThat(KnowledgeDocumentService.genreMatches("清宫,古风", "赛博朋克")).isFalse();
        assertThat(KnowledgeDocumentService.genreMatches("", "任意题材")).isTrue();
    }
}
