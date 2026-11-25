package studio.foliarc.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.lore.service.LoreService;
import studio.foliarc.biz.memory.entity.ChapterSummaryEntity;
import studio.foliarc.biz.memory.entity.SectionSummaryEntity;
import studio.foliarc.biz.memory.entity.StoryRollupSummaryEntity;
import studio.foliarc.biz.memory.entity.VolumeSummaryEntity;
import studio.foliarc.biz.memory.mapper.ChapterSummaryMapper;
import studio.foliarc.biz.memory.mapper.CharacterRelationMapper;
import studio.foliarc.biz.memory.mapper.SectionSummaryMapper;
import studio.foliarc.biz.memory.mapper.StoryRollupSummaryMapper;
import studio.foliarc.biz.memory.mapper.VolumeSummaryMapper;
import studio.foliarc.biz.memory.service.MemoryService;
import studio.foliarc.biz.memory.service.StoryMemoryContextService;
import studio.foliarc.biz.novel.dto.NovelDtos;
import studio.foliarc.biz.novel.dto.StructureDtos;
import studio.foliarc.biz.novel.entity.BookEntity;
import studio.foliarc.biz.novel.entity.ChapterEntity;
import studio.foliarc.biz.novel.entity.ChapterSectionEntity;
import studio.foliarc.biz.novel.entity.ChapterVersionEntity;
import studio.foliarc.biz.novel.entity.SectionVersionEntity;
import studio.foliarc.biz.novel.entity.VolumeEntity;
import studio.foliarc.biz.novel.mapper.BookMapper;
import studio.foliarc.biz.novel.mapper.ChapterMapper;
import studio.foliarc.biz.novel.mapper.ChapterSectionMapper;
import studio.foliarc.biz.novel.mapper.ChapterVersionMapper;
import studio.foliarc.biz.novel.mapper.SectionVersionMapper;
import studio.foliarc.biz.novel.mapper.VolumeMapper;
import studio.foliarc.biz.novel.service.BookService;
import studio.foliarc.biz.novel.service.ChapterService;
import studio.foliarc.biz.novel.service.StructureService;
import studio.foliarc.biz.novel.service.WordCounter;
import studio.foliarc.biz.outbox.RabbitMqTopologyConfig;
import studio.foliarc.biz.outbox.service.OutboxService;
import studio.foliarc.biz.rag.dto.RagDtos;
import studio.foliarc.biz.rag.service.KnowledgeDocumentService;
import studio.foliarc.biz.vip.dto.VipDtos;
import studio.foliarc.biz.vip.service.VipEntitlementService;
import tools.jackson.databind.ObjectMapper;

class Phase58aStructureAndMemoryTests {

    private final UUID ownerId = UUID.randomUUID();
    private final UUID bookId = UUID.randomUUID();
    private final BookService books = mock(BookService.class);
    private final ChapterMapper chapters = mock(ChapterMapper.class);
    private final ChapterSectionMapper sections = mock(ChapterSectionMapper.class);
    private final VolumeMapper volumes = mock(VolumeMapper.class);
    private final SectionSummaryMapper sectionSummaries = mock(SectionSummaryMapper.class);
    private final StoryRollupSummaryMapper rollups = mock(StoryRollupSummaryMapper.class);
    private final ChapterSummaryMapper chapterSummaries = mock(ChapterSummaryMapper.class);
    private final VolumeSummaryMapper legacyVolumes = mock(VolumeSummaryMapper.class);
    private final OutboxService outbox = mock(OutboxService.class);
    private final KnowledgeDocumentService knowledge = mock(KnowledgeDocumentService.class);

    @BeforeAll
    static void initializeMapperMetadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "phase58a");
        for (Class<?> entity : List.of(ChapterEntity.class, ChapterSectionEntity.class, SectionSummaryEntity.class,
                StoryRollupSummaryEntity.class, ChapterSummaryEntity.class, VolumeSummaryEntity.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @Test
    void narrativeOrderAndFlexibleMultiSectionCrud() {
        ChapterEntity chapter = chapter(7, "原来的开场".repeat(20));
        UUID chapterId = chapter.getId();
        ChapterSectionEntity first = section(chapter, 1, chapter.getContent());
        List<ChapterSectionEntity> stored = new ArrayList<>(List.of(first));
        when(books.requireOwnedBook(ownerId, bookId)).thenReturn(book());
        when(chapters.selectById(chapterId)).thenReturn(chapter);
        when(chapters.selectList(any())).thenReturn(List.of(chapter));
        when(sections.selectList(any())).thenAnswer(call -> List.copyOf(stored));
        when(sections.findMaxSectionNo(chapterId)).thenAnswer(call ->
                stored.stream().mapToInt(ChapterSectionEntity::getSectionNo).max().orElse(0));
        when(sections.insert(any(ChapterSectionEntity.class))).thenAnswer(call -> {
            stored.add(call.getArgument(0));
            return 1;
        });
        when(sections.updateById(any(ChapterSectionEntity.class))).thenAnswer(call -> {
            ChapterSectionEntity updated = call.getArgument(0);
            updated.setVersion(updated.getVersion() + 1L);
            return 1;
        });
        when(sections.deleteById(any(UUID.class))).thenAnswer(call -> {
            stored.removeIf(s -> s.getId().equals(call.getArgument(0)));
            return 1;
        });
        SectionVersionMapper snapshots = mock(SectionVersionMapper.class);
        StoryMemoryContextService memory = mock(StoryMemoryContextService.class);
        StructureService service = new StructureService(books, volumes, chapters, sections, snapshots, memory, knowledge);

        String scene = "新场景的正文".repeat(20);
        var created = service.createSection(ownerId, bookId, chapterId, new StructureDtos.CreateSectionRequest("新场景", scene));
        assertThat(StructureService.narrativeOrder(7, 2)).isEqualTo(7_002L);
        assertThat(created.narrativeOrder()).isEqualTo(7_002L);
        assertThat(created.isDefault()).isFalse();
        assertThat(chapter.getStructureMode()).isEqualTo("MULTI_SECTION");
        assertThat(chapter.getWordCount()).isEqualTo(first.getWordCount() + WordCounter.count(scene));
        verify(snapshots).insert(any(SectionVersionEntity.class));
        verify(memory).invalidateAndEnqueueSectionDistill(eq(ownerId), eq(bookId), any(ChapterSectionEntity.class));

        var tree = service.getTree(ownerId, bookId);
        assertThat(tree.totalUnits()).isEqualTo(2L);
        assertThat(tree.volumes()).singleElement().satisfies(volume -> {
            assertThat(volume.id()).isNull();
            assertThat(volume.chapters()).singleElement().satisfies(node -> assertThat(node.sections()).hasSize(2));
        });
        var preview = service.fullPreview(ownerId, bookId, chapterId);
        assertThat(preview.sectionCount()).isEqualTo(2);
        assertThat(preview.combinedContent()).isEqualTo(first.getContent() + "\n\n" + scene);
        assertThat(preview.totalWordCount()).isEqualTo(chapter.getWordCount());

        VipEntitlementService vip = mock(VipEntitlementService.class);
        var free = new VipDtos.ResolvedUserEntitlements("FREE", "免费版", "FREE_DEFAULT", null, null, 10_000,
                20_000_000L, VipEntitlementService.defaultEntitlements("FREE"));
        when(vip.resolveForUser(ownerId)).thenReturn(free);
        when(vip.quotaExceeded(eq(free), eq("SECTION_CHARS"), eq(20_000L), eq(20_001L), anyString()))
                .thenReturn(BizException.vipQuotaExceeded("超过小节字符限制", Map.of("feature", "SECTION_CHARS")));
        service.setVipEntitlementService(vip);
        assertBizError(() -> service.updateSection(ownerId, bookId, chapterId, created.id(),
                        new StructureDtos.UpdateSectionRequest("新场景", "甲".repeat(20_001), 0L, false, null)),
                HttpStatus.FORBIDDEN, "VIP_QUOTA_EXCEEDED");
        verify(vip).quotaExceeded(eq(free), eq("SECTION_CHARS"), eq(20_000L), eq(20_001L), anyString());

        service.setVipEntitlementService(null);
        first.setContent("甲".repeat(100_000));
        first.setWordCount(100_000);
        assertBizError(() -> service.createSection(ownerId, bookId, chapterId,
                        new StructureDtos.CreateSectionRequest("超过累计上限", "乙".repeat(100_000))),
                HttpStatus.BAD_REQUEST, "CHAPTER_SIZE_EXCEEDED");
        var updated = service.updateSection(ownerId, bookId, chapterId, created.id(),
                new StructureDtos.UpdateSectionRequest("新场景", "丙".repeat(100_000), 0L, true, "扩写"));
        assertThat(updated.summaryDirty()).isTrue();
        assertThat(updated.version()).isEqualTo(1L);
        assertThat(chapter.getWordCount()).isEqualTo(200_000);
        verify(knowledge, times(2)).syncChapter(ownerId, bookId, chapterId, false);

        service.deleteSection(ownerId, bookId, chapterId, first.getId());
        assertThat(chapter.getStructureMode()).isEqualTo("SINGLE");
        assertThat(chapter.getContent()).isEqualTo(updated.content());
        assertThat(stored).singleElement().satisfies(s -> assertThat(s.getIsDefault()).isTrue());
        verify(memory).markRollupsStaleForSection(bookId, first);
        assertBizError(() -> service.deleteSection(ownerId, bookId, chapterId, created.id()),
                HttpStatus.BAD_REQUEST, "CANNOT_DELETE_LAST_SECTION");
    }

    @Test
    void adaptiveAssemblerO1AndReverseBudgetNeverDropsRecentAt1500Units() {
        BookEntity book = book();
        ChapterEntity chapter = chapter(1_501, "");
        UUID volumeId = UUID.randomUUID();
        chapter.setVolumeId(volumeId);
        VolumeEntity volume = new VolumeEntity();
        volume.setArcGoal("本卷目标：".repeat(80));
        when(volumes.selectById(volumeId)).thenReturn(volume);
        ChapterSectionEntity current = section(chapter, 1, "");
        ChapterSectionEntity previous = section(chapter(1_500, ""), 1, "断桥上的原文衔接");
        previous.setSummaryDirty(true);
        when(sections.selectCount(any())).thenAnswer(call -> {
            LambdaQueryWrapper<ChapterSectionEntity> query = call.getArgument(0);
            assertThat(query.getSqlSegment()).contains("narrative_order <");
            assertThat(query.getParamNameValuePairs()).containsValue(1_501_001L);
            return 1_500L;
        });
        when(sections.selectOne(any())).thenAnswer(call -> {
            LambdaQueryWrapper<ChapterSectionEntity> query = call.getArgument(0);
            assertThat(query.getSqlSegment()).contains("narrative_order <", "word_count >", "narrative_order DESC", "LIMIT 1");
            return previous;
        });
        List<SectionSummaryEntity> recent = IntStream.rangeClosed(1_491, 1_500).mapToObj(no -> {
            SectionSummaryEntity summary = new SectionSummaryEntity();
            summary.setNarrativeOrder(StructureService.narrativeOrder(no, 1));
            summary.setChapterNo(no);
            summary.setSectionNo(1);
            summary.setSummaryText("小节" + no + "开头" + "详".repeat(175) + "结尾" + no);
            return summary;
        }).toList();
        when(sectionSummaries.selectList(any())).thenAnswer(call -> {
            LambdaQueryWrapper<SectionSummaryEntity> query = call.getArgument(0);
            assertThat(query.getSqlSegment()).contains("narrative_order <", "narrative_order DESC", "LIMIT 10");
            return recent.reversed();
        });
        StoryRollupSummaryEntity tier1 = rollup(1, 1_480_001L, 1_490_999L, "近卷内容");
        StoryRollupSummaryEntity tier2 = rollup(2, 1_300_001L, 1_470_999L, "前篇内容");
        StoryRollupSummaryEntity tier3 = rollup(3, 1_000L, 1_299_999L, "纪元内容");
        List<Long> cutoffs = List.of(1_491_001L, tier1.getStartOrder(), tier2.getStartOrder());
        List<Integer> limits = List.of(5, 4, 10);
        List<StoryRollupSummaryEntity> tiers = List.of(tier1, tier2, tier3);
        List<Integer> fetchedTiers = new ArrayList<>();
        when(rollups.selectList(any())).thenAnswer(call -> {
            LambdaQueryWrapper<StoryRollupSummaryEntity> query = call.getArgument(0);
            String sql = query.getSqlSegment();
            int level = (Integer) query.getParamNameValuePairs().get("MPGENVAL2");
            int index = level - 1;
            assertThat(sql).contains("end_order <", "end_order DESC", "LIMIT " + limits.get(index));
            assertThat(query.getParamNameValuePairs()).containsValue(cutoffs.get(index));
            assertThat(tiers.get(index).getEndOrder()).isLessThan(cutoffs.get(index));
            fetchedTiers.add(level);
            return List.of(tiers.get(index));
        });

        var context = storyMemory().buildAdaptiveContext(ownerId, book, chapter, current, "", "续写");
        assertThat(context.effectivePrecisionMode()).isEqualTo("EPIC");
        assertThat(context.hotWindowContext()).contains(previous.getContent());
        assertThat(fetchedTiers).containsExactly(1, 2, 3);
        assertThat(context.rollingSummaryContext()).hasSizeLessThanOrEqualTo(4_100)
                .contains("本卷目标", "近卷内容", "前篇内容", "纪元内容");
        assertThat(recent.stream().map(s -> context.rollingSummaryContext().indexOf(s.getSummaryText())).toList())
                .allSatisfy(offset -> assertThat(offset).isGreaterThanOrEqualTo(0)).isSorted();
        verify(chapters, never()).selectList(any());
        verify(sections).selectCount(any());
        verify(sections).selectOne(any());
        verify(outbox).enqueue(eq("SECTION"), eq(previous.getId()), eq("SECTION_DISTILL"),
                eq(RabbitMqTopologyConfig.RK_DISTILL), anyString(), any());

        assertThat(StoryMemoryContextService.determinePrecisionMode(book, 300)).isEqualTo("PRECISE");
        assertThat(StoryMemoryContextService.determinePrecisionMode(book, 1_000)).isEqualTo("EXPEDITION");
        book.setPrecisionMode(" precise ");
        assertThat(StoryMemoryContextService.determinePrecisionMode(book, 1_500)).isEqualTo("PRECISE");
    }

    @Test
    void rollbackAndDeleteTriggerDirtyInvalidationAndRollupStale() {
        ChapterEntity chapter = chapter(19, "修改过的文本".repeat(20));
        ChapterSectionEntity section = section(chapter, 1, chapter.getContent());
        UUID chapterId = chapter.getId();
        when(chapters.selectById(chapterId)).thenReturn(chapter);
        when(chapters.updateById(any(ChapterEntity.class))).thenReturn(1);
        when(sections.selectList(any())).thenReturn(List.of(section));
        when(sections.selectOne(any())).thenReturn(section);
        when(sections.updateById(any(ChapterSectionEntity.class))).thenReturn(1);
        ChapterVersionMapper versions = mock(ChapterVersionMapper.class);
        ChapterVersionEntity snapshot = new ChapterVersionEntity();
        snapshot.setId(UUID.randomUUID());
        snapshot.setChapterId(chapterId);
        snapshot.setVersionNo(2);
        snapshot.setContent("回滚后的原文".repeat(20));
        when(versions.selectById(snapshot.getId())).thenReturn(snapshot);
        List<LambdaUpdateWrapper<StoryRollupSummaryEntity>> invalidated = new ArrayList<>();
        when(rollups.update(isNull(), any())).thenAnswer(call -> {
            invalidated.add(call.getArgument(1));
            return 1;
        });
        ChapterService service = new ChapterService(books, chapters, versions, knowledge, storyMemory());
        service.setChapterSectionMapper(sections);

        var rolled = service.rollbackToVersion(ownerId, bookId, chapterId, snapshot.getId(), new NovelDtos.RollbackChapterRequest(3L));
        assertThat(rolled.content()).isEqualTo(snapshot.getContent());
        assertThat(chapter.getContentHash()).isEqualTo(StoryMemoryContextService.sha256Hex(snapshot.getContent()));
        assertThat(chapter.getSummaryDirty()).isTrue();
        assertThat(section.getContentHash()).isEqualTo(chapter.getContentHash());
        assertThat(section.getSummaryDirty()).isTrue();
        verify(outbox).enqueue(eq("SECTION"), eq(section.getId()), eq("SECTION_DISTILL"),
                eq(RabbitMqTopologyConfig.RK_DISTILL), anyString(), any());
        verify(knowledge).syncChapterInBackgroundIfNeeded(ownerId, bookId, chapter);

        service.deleteChapter(ownerId, bookId, chapterId);
        verify(chapters).deleteById(chapterId);
        verify(legacyVolumes, times(2)).update(isNull(), any());
        assertThat(invalidated).hasSize(2);
        var deletion = invalidated.getLast();
        assertThat(deletion.getSqlSet()).contains("status=");
        assertThat(deletion.getSqlSegment()).contains("start_order <=", "end_order >=");
        assertThat(deletion.getParamNameValuePairs()).containsValue("STALE").containsValue(19_001L).containsValue(19_999L);
    }

    @Test
    void distillApplySavesSummaryMemoryAndTriggersRollupWithAncientDistanceGuard() {
        BookEntity book = book();
        book.setCharterChapterCount(3);
        when(books.getBookByIdUnchecked(bookId)).thenReturn(book);
        ChapterEntity recentChapter = chapter(1_490, "");
        ChapterEntity ancientChapter = chapter(950, "");
        ChapterSectionEntity recent = section(recentChapter, 1, "近期故事正文");
        ChapterSectionEntity ancient = section(ancientChapter, 2, "早期故事正文");
        recent.setSummaryDirty(true);
        ancient.setSummaryDirty(true);
        when(chapters.selectById(recentChapter.getId())).thenReturn(recentChapter);
        when(chapters.selectById(ancientChapter.getId())).thenReturn(ancientChapter);
        when(sections.selectById(recent.getId())).thenReturn(recent);
        when(sections.selectById(ancient.getId())).thenReturn(ancient);
        when(sections.selectOne(any())).thenReturn(section(chapter(1_501, ""), 1, ""));
        when(sectionSummaries.selectCount(any())).thenReturn(500L);
        StoryRollupSummaryEntity lastRollup = rollup(1, 1_471_001L, 1_480_001L, "上一卷");
        lastRollup.setSeqNo(49);
        when(rollups.selectOne(any())).thenReturn(lastRollup);
        when(sectionSummaries.selectList(any())).thenReturn(IntStream.rangeClosed(1_481, 1_490).mapToObj(no -> {
            SectionSummaryEntity row = new SectionSummaryEntity();
            row.setNarrativeOrder(StructureService.narrativeOrder(no, 1));
            return row;
        }).toList());
        MemoryService memory = mock(MemoryService.class);
        var service = storyMemory();
        service.setMemoryService(memory);

        var summary = new RagDtos.DistilledSectionSummary("破晓，河畔", "小队渡河", "获得信物", "河边脚印", "第三人称",
                "林青、苏晚", "鹰回到崖顶");
        var extracted = new RagDtos.ApplyExtractedMemoryRequest(1_490, List.of(), List.of(), List.of(), List.of());
        var applied = new RagDtos.ApplyExtractedMemoryResponse(0, 0, 0, 0);
        when(memory.applyExtractedMemory(ownerId, bookId, extracted)).thenReturn(applied);

        var rejected = service.applySectionDistill(bookId,
                new RagDtos.InternalApplyDistillRequest(recent.getId(), "superseded", summary, extracted));
        assertThat(rejected.saved()).isFalse();
        verify(sectionSummaries, never()).insert(any(SectionSummaryEntity.class));
        verify(memory, never()).applyExtractedMemory(eq(ownerId), eq(bookId), any());

        var completed = service.applySectionDistill(bookId,
                new RagDtos.InternalApplyDistillRequest(recent.getId(), recent.getContentHash(), summary, extracted));
        assertThat(completed.saved()).isTrue();
        assertThat(completed.rollupQueued()).isTrue();
        assertThat(completed.refreshAutoCharter()).isTrue();
        assertThat(completed.memory()).isEqualTo(applied);
        verify(memory).applyExtractedMemory(ownerId, bookId, extracted);
        verify(chapterSummaries).insert(any(ChapterSummaryEntity.class));
        ArgumentCaptor<SectionSummaryEntity> saved = ArgumentCaptor.forClass(SectionSummaryEntity.class);
        verify(sectionSummaries).insert(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("READY");
        assertThat(saved.getValue().getTimeAndPlace()).isEqualTo(summary.timeAndPlace());
        assertThat(saved.getValue().getPovNotes()).isEqualTo(summary.povNotes());
        assertThat(saved.getValue().getEndingHook()).isEqualTo(summary.endingHook());
        verify(outbox).enqueue(eq("BOOK"), eq(bookId), eq("MEMORY_ROLLUP"),
                eq(RabbitMqTopologyConfig.RK_ROLLUP), anyString(),
                argThat(payload -> Integer.valueOf(1).equals(payload.get("tier_level"))
                        && Integer.valueOf(50).equals(payload.get("seq_no"))
                        && Long.valueOf(1_481_001L).equals(payload.get("start_order"))
                        && Long.valueOf(1_490_001L).equals(payload.get("end_order"))
                        && Boolean.TRUE.equals(payload.get("refresh_auto_charter"))));

        var deferred = service.applySectionDistill(bookId,
                new RagDtos.InternalApplyDistillRequest(ancient.getId(), ancient.getContentHash(), summary, null));
        assertThat(deferred.saved()).isTrue();
        assertThat(deferred.rollupQueued()).isFalse();
        assertThat(deferred.refreshAutoCharter()).isFalse();
        verify(sectionSummaries, times(2)).insert(any(SectionSummaryEntity.class));
        verify(sectionSummaries).selectCount(any());
        verify(sections, times(2)).update(isNull(), argThat(wrapper -> {
            var update = (LambdaUpdateWrapper<?>) wrapper;
            return update.getSqlSet().contains("summary_dirty=") && update.getParamNameValuePairs().containsValue(false);
        }));
        verify(rollups, times(2)).update(isNull(), any());
        verify(outbox, times(1)).enqueue(eq("BOOK"), eq(bookId), eq("MEMORY_ROLLUP"),
                eq(RabbitMqTopologyConfig.RK_ROLLUP), anyString(), any());

        List<StoryRollupSummaryEntity> tierOneGroup = IntStream.rangeClosed(41, 50)
                .mapToObj(seq -> readyRollup(1, seq, 1_391_001L + (seq - 41) * 10_000L, 9_000L)).toList();
        when(rollups.selectOne(any())).thenReturn(null, tierOneGroup.getLast());
        when(sectionSummaries.selectList(any())).thenReturn(List.of());
        when(rollups.selectCount(any())).thenReturn(50L);
        when(rollups.selectList(any())).thenReturn(tierOneGroup);
        service.saveRollup(bookId, new RagDtos.InternalSaveRollupRequest(
                1, 50, null, 1_481_001L, 1_490_001L, 1_481, 1_490, "卷摘", "已完成的卷摘", "", "volume-hash"));
        verify(outbox).enqueue(eq("BOOK"), eq(bookId), eq("MEMORY_ROLLUP"),
                eq(RabbitMqTopologyConfig.RK_ROLLUP), anyString(),
                argThat(payload -> Integer.valueOf(2).equals(payload.get("tier_level"))
                        && Integer.valueOf(5).equals(payload.get("seq_no"))
                        && Long.valueOf(1_391_001L).equals(payload.get("start_order"))
                        && Long.valueOf(1_490_001L).equals(payload.get("end_order"))));

        List<StoryRollupSummaryEntity> tierTwoGroup = IntStream.rangeClosed(1, 5)
                .mapToObj(seq -> readyRollup(2, seq, 991_001L + (seq - 1) * 100_000L, 99_000L)).toList();
        when(rollups.selectOne(any())).thenReturn(null);
        when(rollups.selectCount(any())).thenReturn(5L);
        when(rollups.selectList(any())).thenReturn(tierTwoGroup);
        service.saveRollup(bookId, new RagDtos.InternalSaveRollupRequest(
                2, 5, null, 1_391_001L, 1_490_001L, 1_391, 1_490, "篇摘", "已完成的篇摘", "", "arc-hash"));
        verify(outbox).enqueue(eq("BOOK"), eq(bookId), eq("MEMORY_ROLLUP"),
                eq(RabbitMqTopologyConfig.RK_ROLLUP), anyString(),
                argThat(payload -> Integer.valueOf(3).equals(payload.get("tier_level"))
                        && Integer.valueOf(1).equals(payload.get("seq_no"))
                        && Long.valueOf(991_001L).equals(payload.get("start_order"))));

        var workerRequest = new ObjectMapper().readValue("""
                {
                  "sectionId": "%s",
                  "chapterId": "%s",
                  "contentHash": "%s",
                  "summary": {"summaryText": "小队渡河", "keyEvents": "渡河"},
                  "memory": {
                    "characterUpdates": [{"name": "林青", "lifecycleStatus": "ACTIVE", "aliases": []}],
                    "foreshadowingSignals": [{"action": "PLANTED", "title": "河边脚印", "description": "有人先行"}]
                  }
                }
                """.formatted(recent.getId(), recentChapter.getId(), recent.getContentHash()), RagDtos.InternalApplyDistillRequest.class);
        assertThat(workerRequest.summary().summaryText()).isEqualTo("小队渡河");
        assertThat(workerRequest.memory().foreshadowingUpdates()).singleElement()
                .satisfies(clue -> assertThat(clue.title()).isEqualTo("河边脚印"));
    }

    private StoryMemoryContextService storyMemory() {
        var memory = new StoryMemoryContextService(books, mock(BookMapper.class), chapters, chapterSummaries,
                legacyVolumes, mock(LoreService.class), mock(CharacterRelationMapper.class), outbox);
        memory.setChapterSectionMapper(sections);
        memory.setSectionSummaryMapper(sectionSummaries);
        memory.setStoryRollupSummaryMapper(rollups);
        memory.setVolumeMapper(volumes);
        return memory;
    }

    private BookEntity book() {
        BookEntity book = new BookEntity();
        book.setId(bookId);
        book.setOwnerId(ownerId);
        book.setPrecisionMode("AUTO");
        return book;
    }

    private ChapterEntity chapter(int no, String text) {
        ChapterEntity chapter = new ChapterEntity();
        chapter.setId(UUID.randomUUID());
        chapter.setBookId(bookId);
        chapter.setChapterNo(no);
        chapter.setTitle("第 " + no + " 章");
        chapter.setContent(text);
        chapter.setContentHash(StoryMemoryContextService.sha256Hex(text));
        chapter.setSummaryDirty(false);
        chapter.setWordCount(WordCounter.count(text));
        chapter.setStructureMode("SINGLE");
        chapter.setStatus("DRAFT");
        chapter.setVersion(3L);
        return chapter;
    }

    private ChapterSectionEntity section(ChapterEntity chapter, int no, String text) {
        ChapterSectionEntity section = new ChapterSectionEntity();
        section.setId(UUID.randomUUID());
        section.setBookId(bookId);
        section.setChapterId(chapter.getId());
        section.setChapterNo(chapter.getChapterNo());
        section.setSectionNo(no);
        section.setNarrativeOrder(StructureService.narrativeOrder(chapter.getChapterNo(), no));
        section.setTitle("小节 " + no);
        section.setContent(text);
        section.setContentHash(StoryMemoryContextService.sha256Hex(text));
        section.setWordCount(WordCounter.count(text));
        section.setIsDefault(no == 1);
        section.setVersion(0L);
        return section;
    }

    private static StoryRollupSummaryEntity rollup(int level, long start, long end, String text) {
        StoryRollupSummaryEntity rollup = new StoryRollupSummaryEntity();
        rollup.setTierLevel(level);
        rollup.setSeqNo(1);
        rollup.setStartOrder(start);
        rollup.setEndOrder(end);
        rollup.setSummaryText(text + "旧".repeat(4_000));
        return rollup;
    }

    private static StoryRollupSummaryEntity readyRollup(int level, int seqNo, long start, long span) {
        var rollup = rollup(level, start, start + span, "已完成的归并");
        rollup.setId(UUID.randomUUID());
        rollup.setSeqNo(seqNo);
        rollup.setStatus("READY");
        return rollup;
    }

    private static void assertBizError(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status, String code
    ) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getCode()).isEqualTo(code);
            assertThat(ex.getStatus()).isEqualTo(status);
        });
    }
}
