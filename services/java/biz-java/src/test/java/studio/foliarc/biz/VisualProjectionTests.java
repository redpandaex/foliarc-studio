package studio.foliarc.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.config.mybatis.PostgresUuidTypeHandler;
import studio.foliarc.biz.lore.entity.CharacterEntity;
import studio.foliarc.biz.lore.entity.ForeshadowingEntity;
import studio.foliarc.biz.lore.entity.WorldEntryEntity;
import studio.foliarc.biz.lore.mapper.CharacterMapper;
import studio.foliarc.biz.lore.mapper.ForeshadowingMapper;
import studio.foliarc.biz.lore.mapper.PromptTemplateMapper;
import studio.foliarc.biz.lore.mapper.WorldEntryMapper;
import studio.foliarc.biz.lore.service.LoreService;
import studio.foliarc.biz.memory.entity.CharacterRelationEntity;
import studio.foliarc.biz.memory.entity.CharacterStateSnapshotEntity;
import studio.foliarc.biz.memory.entity.SectionSummaryEntity;
import studio.foliarc.biz.memory.entity.TimelineEventEntity;
import studio.foliarc.biz.memory.mapper.CharacterRelationMapper;
import studio.foliarc.biz.memory.mapper.CharacterStateSnapshotMapper;
import studio.foliarc.biz.memory.mapper.SectionSummaryMapper;
import studio.foliarc.biz.memory.mapper.TimelineEventMapper;
import studio.foliarc.biz.memory.service.MemoryService;
import studio.foliarc.biz.novel.entity.BookEntity;
import studio.foliarc.biz.novel.entity.ChapterEntity;
import studio.foliarc.biz.novel.entity.ChapterSectionEntity;
import studio.foliarc.biz.novel.entity.VolumeEntity;
import studio.foliarc.biz.novel.mapper.ChapterMapper;
import studio.foliarc.biz.novel.mapper.ChapterSectionMapper;
import studio.foliarc.biz.novel.mapper.VolumeMapper;
import studio.foliarc.biz.novel.service.BookService;
import studio.foliarc.biz.novel.service.StructureService;
import studio.foliarc.biz.rag.dto.RagDtos;
import studio.foliarc.biz.visual.controller.VisualController;
import studio.foliarc.biz.visual.dto.VisualDtos;
import studio.foliarc.biz.visual.dto.VisualDtos.UpdateVisualRelationRequest;
import studio.foliarc.biz.visual.dto.VisualDtos.VisualForeshadowingArc;
import studio.foliarc.biz.visual.dto.VisualDtos.VisualGraphEdge;
import studio.foliarc.biz.visual.dto.VisualDtos.VisualGraphNode;
import studio.foliarc.biz.visual.service.VisualProjectionService;

class VisualProjectionTests {

    private final UUID ownerId = UUID.randomUUID();
    private final UUID bookId = UUID.randomUUID();
    private final UUID otherBookId = UUID.randomUUID();

    private final BookService bookService = mock(BookService.class);
    private final VolumeMapper volumeMapper = mock(VolumeMapper.class);
    private final ChapterMapper chapterMapper = mock(ChapterMapper.class);
    private final ChapterSectionMapper sectionMapper = mock(ChapterSectionMapper.class);
    private final CharacterMapper characterMapper = mock(CharacterMapper.class);
    private final WorldEntryMapper worldEntryMapper = mock(WorldEntryMapper.class);
    private final ForeshadowingMapper foreshadowingMapper = mock(ForeshadowingMapper.class);
    private final CharacterRelationMapper relationMapper = mock(CharacterRelationMapper.class);
    private final CharacterStateSnapshotMapper snapshotMapper = mock(CharacterStateSnapshotMapper.class);
    private final TimelineEventMapper timelineMapper = mock(TimelineEventMapper.class);
    private final SectionSummaryMapper summaryMapper = mock(SectionSummaryMapper.class);

    @BeforeAll
    static void initializeMapperMetadata() {
        var configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(PostgresUuidTypeHandler.class);
        var assistant = new MapperBuilderAssistant(configuration, "phase7a-visual");
        for (Class<?> entity : List.of(
                VolumeEntity.class,
                ChapterEntity.class,
                ChapterSectionEntity.class,
                CharacterEntity.class,
                WorldEntryEntity.class,
                ForeshadowingEntity.class,
                CharacterRelationEntity.class,
                CharacterStateSnapshotEntity.class,
                TimelineEventEntity.class,
                SectionSummaryEntity.class
        )) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @Test
    void goldenSampleBookEnforcesStrictAntiSpoilerAcrossCutoffsAndRevealsFullPlan() {
        SampleBookFixture fx = setupGoldenSampleBook();
        VisualProjectionService service = createService();

        // 1. AS_OF 第 7 章：看不到「舟师」节点、关系、伏笔及隐藏计数（totalCharacters == visibleCharacters）
        var atCh7 = service.getProjection(ownerId, bookId, "graph", "AS_OF",
                fx.ch7.getId(), null, null, null, null, null, null);
        assertThat(atCh7.capabilities()).isEqualTo(new VisualDtos.VisualCapabilities(true, false, false, false));
        assertThat(atCh7.cutoff().visibility()).isEqualTo("AS_OF");
        assertThat(atCh7.cutoff().chapterNo()).isEqualTo(7);
        assertThat(atCh7.cutoff().narrativeOrder()).isEqualTo(7_999L);
        assertThat(atCh7.chapters()).extracting(VisualDtos.VisualChapterAxisItem::chapterNo)
                .allMatch(no -> no <= 7);
        assertThat(atCh7.nodes()).extracting(VisualGraphNode::name)
                .containsExactly("林青")
                .doesNotContain("舟师", "顾三", "未登场反派");
        assertThat(atCh7.totalCharacters()).isEqualTo(atCh7.visibleCharacters()).isEqualTo(1);
        assertThat(atCh7.edges()).isEmpty();
        assertThat(atCh7.arcs()).isEmpty();
        assertThat(atCh7.undatedRelationCount()).isEqualTo(1);
        assertThat(atCh7.etag()).hasSize(64);

        // 实体详情与导演选项在 Ch.7 必须严格 404 / 隐藏「舟师」与 #F03
        assertThatThrownBy(() -> service.getEntityDetail(
                ownerId, bookId, "character", fx.zhoushi.getId().toString(), "AS_OF", fx.ch7.getId(), null, null))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.getEntityDetail(
                ownerId, bookId, "foreshadowing", fx.f03.getId().toString(), "AS_OF", fx.ch7.getId(), null, null))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        var dirCh7 = service.getDirectorOptions(ownerId, bookId, "AS_OF", fx.ch7.getId(), null);
        assertThat(dirCh7.characters()).extracting(VisualDtos.DirectorCharacterOption::name)
                .containsExactly("林青");
        assertThat(dirCh7.foreshadowings()).isEmpty();

        // 2. AS_OF 第 12 章第 1 节：能看到第 8 章初见的「舟师」，但看不到同章第 2 节的结盟关系
        var atCh12Sec1 = service.getProjection(ownerId, bookId, "graph", "AS_OF",
                null, fx.sec12_1.getId(), null, null, null, null, null);
        assertThat(atCh12Sec1.cutoff().chapterNo()).isEqualTo(12);
        assertThat(atCh12Sec1.cutoff().sectionId()).isEqualTo(fx.sec12_1.getId());
        assertThat(atCh12Sec1.cutoff().sectionNo()).isEqualTo(1);
        assertThat(atCh12Sec1.cutoff().narrativeOrder()).isEqualTo(12_001L);
        assertThat(atCh12Sec1.nodes()).extracting(VisualGraphNode::name).contains("林青", "舟师");
        assertThat(atCh12Sec1.edges()).extracting(VisualGraphEdge::relationType)
                .contains("初遇")
                .doesNotContain("结盟", "幕后密谋");
        assertThatThrownBy(() -> service.getEntityDetail(
                ownerId, bookId, "relation", fx.relAlliance.getId().toString(), "AS_OF", null, fx.sec12_1.getId(), null))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        // 3. AS_OF 第 12 章末：看到结盟与未回收的 #F03（且无底牌、无规划窗口、无未来回收、无 Ch.15 提示）
        var atCh12End = service.getProjection(ownerId, bookId, "lanes", "AS_OF",
                fx.ch12.getId(), null, null, null, null, null, null);
        assertThat(atCh12End.cutoff().narrativeOrder()).isEqualTo(12_999L);
        assertThat(atCh12End.edges()).extracting(VisualGraphEdge::relationType)
                .contains("初遇", "结盟")
                .doesNotContain("幕后密谋");
        assertThat(atCh12End.arcs()).hasSize(1);
        VisualForeshadowingArc f03At12 = atCh12End.arcs().getFirst();
        assertThat(f03At12.code()).isEqualTo("#F03");
        assertThat(f03At12.hiddenTruth()).isNull();
        assertThat(f03At12.targetChapterMin()).isNull();
        assertThat(f03At12.targetChapterMax()).isNull();
        assertThat(f03At12.resolvedChapterNo()).isNull();
        assertThat(f03At12.resolvedSectionId()).isNull();
        assertThat(f03At12.status()).isEqualTo("HINTED");
        assertThat(f03At12.alertState()).isEqualTo("OPEN");
        assertThat(f03At12.openEnded()).isTrue();
        assertThat(f03At12.hintChapters()).containsExactly(11);
        assertThat(f03At12.allowedDirectorModes()).containsExactly("HINT", "PAYOFF");

        var f03DetailAt12 = service.getEntityDetail(
                ownerId, bookId, "foreshadowing", fx.f03.getId().toString(), "AS_OF", fx.ch12.getId(), null, null);
        assertThat(f03DetailAt12.visibleFields()).doesNotContainKeys("hiddenTruth", "targetChapterMin", "targetChapterMax");
        assertThat(f03DetailAt12.visibleFields().get("resolvedChapterNo")).isNull();

        // 4. AS_OF 第 16 章末：看到 #F03 已回收（resolvedChapterNo == 16），但仍看不到第 18 章身亡
        var atCh16End = service.getProjection(ownerId, bookId, "lanes", "AS_OF",
                fx.ch16.getId(), null, null, null, null, null, null);
        VisualForeshadowingArc f03At16 = atCh16End.arcs().stream()
                .filter(a -> "#F03".equals(a.code())).findFirst().orElseThrow();
        assertThat(f03At16.resolvedChapterNo()).isEqualTo(16);
        assertThat(f03At16.resolvedSectionId()).isEqualTo(fx.sec16_1.getId());
        assertThat(f03At16.status()).isEqualTo("RESOLVED");
        assertThat(f03At16.alertState()).isEqualTo("RESOLVED");
        assertThat(f03At16.openEnded()).isFalse();
        assertThat(f03At16.hiddenTruth()).isNull();
        assertThat(f03At16.hintChapters()).containsExactly(11, 15);
        assertThat(f03At16.allowedDirectorModes()).isEmpty();
        VisualGraphNode zhoushiAt16 = atCh16End.nodes().stream()
                .filter(n -> "舟师".equals(n.name())).findFirst().orElseThrow();
        assertThat(zhoushiAt16.lifecycleStatus()).isEqualTo("ACTIVE").isNotEqualTo("DEAD");

        // 5. AS_OF 第 17 章末：「舟师」仍非 DEAD，且无快照角色「顾三」不泄露未来 DEAD 状态
        var atCh17End = service.getProjection(ownerId, bookId, "graph", "AS_OF",
                fx.ch17.getId(), null, null, null, null, null, null);
        VisualGraphNode zhoushiAt17 = atCh17End.nodes().stream()
                .filter(n -> "舟师".equals(n.name())).findFirst().orElseThrow();
        assertThat(zhoushiAt17.lifecycleStatus()).isEqualTo("ACTIVE").isNotEqualTo("DEAD");
        VisualGraphNode gusanAt17 = atCh17End.nodes().stream()
                .filter(n -> "顾三".equals(n.name())).findFirst().orElseThrow();
        assertThat(gusanAt17.lifecycleStatus()).isEqualTo("UNKNOWN").isNotEqualTo("DEAD");
        assertThat(gusanAt17.statusSummary()).isEqualTo("当时状态未记录");

        // 6. PLAN 视角：能看到全部人物（含未登场角色）、DEAD 状态、hiddenTruth、DUE / OVERDUE / PLANNED / RESOLVED 预警
        var plan = service.getProjection(ownerId, bookId, "lanes", "PLAN",
                null, null, null, null, null, null, null);
        assertThat(plan.cutoff().visibility()).isEqualTo("PLAN");
        assertThat(plan.cutoff().chapterNo()).isEqualTo(18);
        assertThat(plan.totalCharacters()).isEqualTo(4);
        assertThat(plan.nodes()).extracting(VisualGraphNode::name)
                .containsExactlyInAnyOrder("林青", "舟师", "顾三", "未登场反派");
        VisualGraphNode zhoushiPlan = plan.nodes().stream()
                .filter(n -> "舟师".equals(n.name())).findFirst().orElseThrow();
        assertThat(zhoushiPlan.lifecycleStatus()).isEqualTo("DEAD");
        assertThat(plan.edges()).extracting(VisualGraphEdge::relationType)
                .contains("初遇", "结盟", "幕后密谋", "旧怨");
        assertThat(plan.edges().stream().filter(VisualGraphEdge::undated).count()).isEqualTo(1);

        var arcByCode = plan.arcs().stream().collect(java.util.stream.Collectors.toMap(VisualForeshadowingArc::code, a -> a));
        assertThat(arcByCode.get("#F03").hiddenTruth()).isEqualTo("舟师实为皇室遗孤");
        assertThat(arcByCode.get("#F03").targetChapterMin()).isEqualTo(14);
        assertThat(arcByCode.get("#F03").targetChapterMax()).isEqualTo(17);
        assertThat(arcByCode.get("#F03").alertState()).isEqualTo("RESOLVED");
        assertThat(arcByCode.get("#F04").alertState()).isEqualTo("OVERDUE");
        assertThat(arcByCode.get("#F05").alertState()).isEqualTo("DUE");
        assertThat(arcByCode.get("#F06").alertState()).isEqualTo("PLANNED");
        assertThat(arcByCode.get("#F06").allowedDirectorModes()).containsExactly("PLANT");

        // 7. POV 过滤：AS_OF 下传 povId（支持角色名与别名匹配）可见对应私密关系与事件
        var atCh12WithPov = service.getProjection(ownerId, bookId, "graph", "AS_OF",
                fx.ch12.getId(), null, null, null, null, fx.zhoushi.getId(), null);
        assertThat(atCh12WithPov.edges()).extracting(VisualGraphEdge::relationType).contains("幕后密谋");
        assertThat(atCh12WithPov.events()).extracting(VisualDtos.VisualTimelineLaneEvent::eventTitle).contains("密室密谈");
    }

    @Test
    void emptyBookAndWindowOverlapAndCrossBookIsolationAreEnforced() {
        VisualProjectionService service = createService();
        when(volumeMapper.selectList(any())).thenReturn(List.of());
        when(chapterMapper.selectList(any())).thenReturn(List.of());
        when(sectionMapper.selectList(any())).thenReturn(List.of());
        when(characterMapper.selectList(any())).thenReturn(List.of());

        var empty = service.getProjection(ownerId, bookId, "graph", "AS_OF",
                null, null, null, null, null, null, null);
        assertThat(empty.cutoff().chapterNo()).isZero();
        assertThat(empty.cutoff().narrativeOrder()).isZero();
        assertThat(empty.cutoff().totalChapters()).isZero();
        assertThat(empty.nodes()).isEmpty();
        assertThat(empty.edges()).isEmpty();
        assertThat(empty.arcs()).isEmpty();
        assertThat(empty.totalCharacters()).isZero();
        assertThat(empty.visibleCharacters()).isZero();
        assertThat(empty.evidenceCoverageRatio()).isZero();

        // 跨书章节/小节/卷/视角人物统一返回 404
        SampleBookFixture fx = setupGoldenSampleBook();
        UUID foreignChapterId = UUID.randomUUID();
        UUID foreignSectionId = UUID.randomUUID();
        UUID foreignVolumeId = UUID.randomUUID();
        UUID foreignPovId = UUID.randomUUID();
        assertThatThrownBy(() -> service.getProjection(ownerId, bookId, "graph", "AS_OF",
                foreignChapterId, null, null, null, null, null, null))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.getProjection(ownerId, bookId, "graph", "AS_OF",
                null, foreignSectionId, null, null, null, null, null))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.getProjection(ownerId, bookId, "graph", "AS_OF",
                null, null, foreignVolumeId, null, null, null, null))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.getProjection(ownerId, bookId, "graph", "AS_OF",
                null, null, null, null, null, foreignPovId, null))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        // 窗口裁剪：Ch.9..14 窗口中 #F03（Ch.8 埋设 -> Ch.16 回收）在 Ch.16 AS_OF 下标记 continuesLeft & continuesRight
        var windowed = service.getProjection(ownerId, bookId, "lanes", "AS_OF",
                fx.ch16.getId(), null, null, 9, 14, null, null);
        VisualForeshadowingArc f03Win = windowed.arcs().stream()
                .filter(a -> "#F03".equals(a.code())).findFirst().orElseThrow();
        assertThat(f03Win.continuesLeft()).isTrue();
        assertThat(f03Win.continuesRight()).isTrue();
    }

    @Test
    void patchVisualRelationEnforcesOwnershipReadonlyOptimisticLockSelfLoopDuplicateAndSync() {
        VisualProjectionService service = createService();
        CharacterEntity lin = characterRow(bookId, "林青", "青儿", "PROTAGONIST", "CORE_PINNED", "青云宗");
        CharacterEntity zhou = characterRow(bookId, "舟师", "老渡", "SUPPORTING", "ACTIVE", "摆渡盟");
        when(characterMapper.selectList(any())).thenReturn(List.of(lin, zhou));

        // 1. 跨书关系返回 404 RELATION_NOT_FOUND
        UUID foreignRelId = UUID.randomUUID();
        CharacterRelationEntity foreignRel = relationRow(otherBookId, "林青", "舟师", "同盟", "AUTHOR", 12, null, 12_002L, "PUBLIC");
        foreignRel.setId(foreignRelId);
        when(relationMapper.selectById(foreignRelId)).thenReturn(foreignRel);
        assertThatThrownBy(() -> service.updateRelation(ownerId, bookId, foreignRelId,
                new UpdateVisualRelationRequest("林青", "舟师", "宿敌", "desc", 12, null, "PUBLIC", null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getCode()).isEqualTo("RELATION_NOT_FOUND");
                });

        // 2. AI 抽取关系只读 409 AI_RELATION_READONLY
        CharacterRelationEntity aiRel = relationRow(bookId, "林青", "舟师", "同行", "AI_EXTRACTED", 12, null, 12_001L, "PUBLIC");
        when(relationMapper.selectById(aiRel.getId())).thenReturn(aiRel);
        assertThatThrownBy(() -> service.updateRelation(ownerId, bookId, aiRel.getId(),
                new UpdateVisualRelationRequest("林青", "舟师", "结盟", "desc", 12, null, "PUBLIC", null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("AI_RELATION_READONLY");
                });

        // 3. 乐观锁冲突 409 RELATION_CONFLICT
        OffsetDateTime updatedAt = OffsetDateTime.of(2026, 11, 27, 8, 0, 0, 0, ZoneOffset.UTC);
        CharacterRelationEntity authorRel = relationRow(bookId, "林青", "舟师", "初遇", "AUTHOR", 8, null, 8_001L, "PUBLIC");
        authorRel.setUpdatedAt(updatedAt);
        when(relationMapper.selectById(authorRel.getId())).thenReturn(authorRel);
        assertThatThrownBy(() -> service.updateRelation(ownerId, bookId, authorRel.getId(),
                new UpdateVisualRelationRequest("林青", "舟师", "结盟", "desc", 12, null, "PUBLIC", updatedAt.minusSeconds(5))))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("RELATION_CONFLICT");
                });

        // 4. 自环（包括通过别名解析到同一规范名）返回 400 SELF_RELATION_FORBIDDEN
        assertThatThrownBy(() -> service.updateRelation(ownerId, bookId, authorRel.getId(),
                new UpdateVisualRelationRequest("林青", "青儿", "自守", "desc", 12, null, "PUBLIC", updatedAt)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getCode()).isEqualTo("SELF_RELATION_FORBIDDEN");
                });

        // 5. 自然键重复 409 RELATION_DUPLICATE
        CharacterRelationEntity existingDup = relationRow(bookId, "林青", "舟师", "生死之交", "AUTHOR", 12, null, 12_002L, "PUBLIC");
        when(relationMapper.selectOne(any())).thenReturn(existingDup);
        assertThatThrownBy(() -> service.updateRelation(ownerId, bookId, authorRel.getId(),
                new UpdateVisualRelationRequest("青儿", "老渡", "生死之交", "desc", 12, null, "PUBLIC", updatedAt)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("RELATION_DUPLICATE");
                });

        // 6. 跨书 sourceSectionId 返回 404 SECTION_NOT_FOUND
        when(relationMapper.selectOne(any())).thenReturn(null);
        ChapterEntity foreignCh = chapterRow(otherBookId, null, 12, "外书章");
        ChapterSectionEntity foreignSec = sectionRow(otherBookId, foreignCh, 2);
        when(sectionMapper.selectById(foreignSec.getId())).thenReturn(foreignSec);
        assertThatThrownBy(() -> service.updateRelation(ownerId, bookId, authorRel.getId(),
                new UpdateVisualRelationRequest("青儿", "老渡", "结盟", "渡口结盟", null, foreignSec.getId(), "PUBLIC", updatedAt)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getCode()).isEqualTo("SECTION_NOT_FOUND");
                });

        // 7. 正常改写生效（同时验证不同时区偏移的同一 Instant 乐观锁通过 + 规范名对齐 + 小节同步）
        ChapterEntity ch12 = chapterRow(bookId, null, 12, "第12章");
        ChapterSectionEntity sec12_2 = sectionRow(bookId, ch12, 2);
        when(sectionMapper.selectById(sec12_2.getId())).thenReturn(sec12_2);
        OffsetDateTime sameInstantPlus8 = updatedAt.withOffsetSameInstant(ZoneOffset.ofHours(8));

        var updated = service.updateRelation(ownerId, bookId, authorRel.getId(),
                new UpdateVisualRelationRequest("青儿", "老渡", "结盟", "黑水渡口立约", null, sec12_2.getId(), "林青,舟师", sameInstantPlus8));
        assertThat(updated.sourceCharacter()).isEqualTo("林青");
        assertThat(updated.targetCharacter()).isEqualTo("舟师");
        assertThat(updated.relationType()).isEqualTo("结盟");
        assertThat(updated.description()).isEqualTo("黑水渡口立约");
        assertThat(updated.sinceChapterNo()).isEqualTo(12);
        assertThat(updated.sourceSectionId()).isEqualTo(sec12_2.getId());
        assertThat(updated.narrativeOrder()).isEqualTo(12_002L);
        assertThat(updated.knownBy()).isEqualTo("林青,舟师");
        verify(bookService, org.mockito.Mockito.atLeastOnce()).lockStructure(bookId);
        verify(relationMapper).updateById(authorRel);
    }

    @Test
    void memoryServiceUpsertRelationCanonicalizesNamesAndValidatesSectionOwnership() {
        var lore = new LoreService(bookService, characterMapper, worldEntryMapper, foreshadowingMapper,
                chapterMapper, mock(PromptTemplateMapper.class));
        var memory = new MemoryService(bookService, lore, relationMapper, timelineMapper);
        memory.setChapterSectionMapper(sectionMapper);

        CharacterEntity lin = characterRow(bookId, "林青", "青儿", "PROTAGONIST", "CORE_PINNED", "");
        CharacterEntity zhou = characterRow(bookId, "舟师", "老渡", "SUPPORTING", "ACTIVE", "");
        when(characterMapper.selectList(any())).thenReturn(List.of(lin, zhou));

        ChapterEntity ch12 = chapterRow(bookId, null, 12, "第12章");
        ChapterSectionEntity sec12_2 = sectionRow(bookId, ch12, 2);
        when(sectionMapper.selectById(sec12_2.getId())).thenReturn(sec12_2);
        when(relationMapper.selectOne(any())).thenReturn(null);

        var created = memory.upsertRelation(ownerId, bookId, new RagDtos.UpsertRelationRequest(
                "青儿", "老渡", "结盟", "渡口并肩", null, 1.0, sec12_2.getId(), null, "AUTHOR", "PUBLIC"));
        assertThat(created.sourceCharacter()).isEqualTo("林青");
        assertThat(created.targetCharacter()).isEqualTo("舟师");
        assertThat(created.sinceChapterNo()).isEqualTo(12);
        assertThat(created.sourceSectionId()).isEqualTo(sec12_2.getId());
        assertThat(created.narrativeOrder()).isEqualTo(12_002L);

        ArgumentCaptor<CharacterRelationEntity> inserted = ArgumentCaptor.forClass(CharacterRelationEntity.class);
        verify(relationMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getSourceCharacter()).isEqualTo("林青");
        assertThat(inserted.getValue().getTargetCharacter()).isEqualTo("舟师");

        // 跨书小节被拒绝 404
        ChapterEntity foreignCh = chapterRow(otherBookId, null, 1, "外书");
        ChapterSectionEntity foreignSec = sectionRow(otherBookId, foreignCh, 1);
        when(sectionMapper.selectById(foreignSec.getId())).thenReturn(foreignSec);
        assertThatThrownBy(() -> memory.upsertRelation(ownerId, bookId, new RagDtos.UpsertRelationRequest(
                "青儿", "老渡", "结盟", "", 12, 1.0, foreignSec.getId(), null, "AUTHOR", "PUBLIC")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        // 自环被拒绝 400
        assertThatThrownBy(() -> memory.upsertRelation(ownerId, bookId, new RagDtos.UpsertRelationRequest(
                "林青", "青儿", "自守", "", 12, 1.0, null, null, "AUTHOR", "PUBLIC")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getCode()).isEqualTo("SELF_RELATION_FORBIDDEN");
                });
        verify(relationMapper, never()).updateById(any(CharacterRelationEntity.class));
    }

    @Test
    void entityDetailsEntityLanesCoverageAndControllerEndpointsFollowStrictContract() {
        SampleBookFixture fx = setupGoldenSampleBook();
        VisualProjectionService service = createService();
        VisualController controller = new VisualController(service);
        AuthenticatedUser principal = new AuthenticatedUser(ownerId, "author@example.test", "author", "AUTHOR");

        // Verify controller routes delegate with full entityLanes (CHARACTER + LOCATION), ETag header, and 304 short-circuit
        var projResp = controller.getProjection(principal, bookId, "lanes", "AS_OF",
                fx.ch12.getId(), null, null, null, null, null, null, null);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(projResp.getHeaders().getETag()).isNotBlank();
        var proj = projResp.getBody();
        assertThat(proj).isNotNull();
        assertThat(proj.entityLanes()).anyMatch(l -> "CHARACTER".equals(l.kind()) && "舟师".equals(l.label()));
        assertThat(proj.entityLanes()).anyMatch(l -> "LOCATION".equals(l.kind()) && "黑水渡口".equals(l.label()));
        assertThat(proj.evidenceCoverageRatio()).isBetween(0.0, 1.0).isGreaterThan(0.0);

        // Matching If-None-Match returns HTTP 304 Not Modified without body
        var notModifiedResp = controller.getProjection(principal, bookId, "lanes", "AS_OF",
                fx.ch12.getId(), null, null, null, null, null, null, projResp.getHeaders().getETag());
        assertThat(notModifiedResp.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(notModifiedResp.getBody()).isNull();

        // view=graph skips entityLanes computation; focusId narrows graph to 1-hop neighborhood
        var graphFocused = controller.getProjection(principal, bookId, "graph", "PLAN",
                null, null, null, null, null, null, fx.zhoushi.getId().toString(), null).getBody();
        assertThat(graphFocused).isNotNull();
        assertThat(graphFocused.entityLanes()).isEmpty();
        assertThat(graphFocused.nodes()).extracting(VisualGraphNode::name)
                .contains("舟师", "林青", "顾三")
                .doesNotContain("未登场反派");

        // Character detail at Ch.12 returns heatByChapter, evidence, relatedEdges, relatedArcs without future DEAD leak
        var zhoushiDetail = controller.getEntityDetail(principal, bookId, "character",
                fx.zhoushi.getId().toString(), "AS_OF", fx.ch12.getId(), null, null);
        assertThat(zhoushiDetail.kind()).isEqualTo("character");
        assertThat(zhoushiDetail.title()).isEqualTo("舟师");
        assertThat(zhoushiDetail.visibleFields().get("lifecycleStatus")).isEqualTo("ACTIVE");
        assertThat(zhoushiDetail.heatByChapter()).isNotEmpty();
        assertThat(zhoushiDetail.evidence()).isNotEmpty();
        assertThat(zhoushiDetail.relatedEdges()).extracting(VisualGraphEdge::relationType).contains("初遇", "结盟");
        assertThat(zhoushiDetail.relatedArcs()).extracting(VisualForeshadowingArc::code).contains("#F03");

        // Relation & Event details work for visible items at Ch.12
        var relDetail = controller.getEntityDetail(principal, bookId, "relation",
                fx.relAlliance.getId().toString(), "AS_OF", fx.ch12.getId(), null, null);
        assertThat(relDetail.kind()).isEqualTo("relation");
        assertThat(relDetail.editable()).isTrue();

        var dirOpts = controller.getDirectorOptions(principal, bookId, "AS_OF", fx.ch12.getId(), null);
        assertThat(dirOpts.cutoffChapterNo()).isEqualTo(12);
        assertThat(dirOpts.foreshadowings()).extracting(VisualForeshadowingArc::code).containsExactly("#F03");
        assertThat(dirOpts.foreshadowings().getFirst().hiddenTruth()).isNull();

        // ETag stability: mutating future-only Ch.18 data MUST NOT change AS_OF Ch.7 ETag
        String etagBefore = service.getProjection(ownerId, bookId, "graph", "AS_OF",
                fx.ch7.getId(), null, null, null, null, null, null).etag();
        fx.zhoushi.setStatusNotes("第18章追加未来备注");
        String etagAfter = service.getProjection(ownerId, bookId, "graph", "AS_OF",
                fx.ch7.getId(), null, null, null, null, null, null).etag();
        assertThat(etagAfter).isEqualTo(etagBefore);
    }

    @Test
    void confirmAiRelationPromotesWithoutDuplicatesAndUpdateEventCanonicalizesAliases() {
        VisualProjectionService service = createService();
        CharacterEntity lin = characterRow(bookId, "林青", "青儿", "PROTAGONIST", "CORE_PINNED", "青云宗");
        CharacterEntity zhou = characterRow(bookId, "舟师", "老渡", "SUPPORTING", "ACTIVE", "摆渡盟");
        when(characterMapper.selectList(any())).thenReturn(List.of(lin, zhou));

        // 1. In-place promote AI_EXTRACTED -> AUTHOR when no author duplicate exists
        CharacterRelationEntity aiRel = relationRow(bookId, "青儿", "老渡", "同行", "AI_EXTRACTED", 8, null, 8_001L, "PUBLIC");
        when(relationMapper.selectById(aiRel.getId())).thenReturn(aiRel);
        when(relationMapper.selectOne(any())).thenReturn(null);

        var promoted = service.confirmAiRelation(ownerId, bookId, aiRel.getId());
        assertThat(promoted.id()).isEqualTo(aiRel.getId());
        assertThat(promoted.source()).isEqualTo("AUTHOR");
        assertThat(promoted.sourceCharacter()).isEqualTo("林青");
        assertThat(promoted.targetCharacter()).isEqualTo("舟师");
        verify(relationMapper).updateById(aiRel);

        // 2. When an AUTHOR duplicate already exists, merge & delete the redundant AI edge
        CharacterRelationEntity aiDup = relationRow(bookId, "青儿", "老渡", "结盟", "AI_EXTRACTED", 12, null, 12_001L, "PUBLIC");
        aiDup.setDescription("AI 补充描述");
        CharacterRelationEntity existingAuthor = relationRow(bookId, "林青", "舟师", "结盟", "AUTHOR", 12, null, 12_002L, "PUBLIC");
        existingAuthor.setDescription("");
        when(relationMapper.selectById(aiDup.getId())).thenReturn(aiDup);
        when(relationMapper.selectOne(any())).thenReturn(existingAuthor);

        var merged = service.confirmAiRelation(ownerId, bookId, aiDup.getId());
        assertThat(merged.id()).isEqualTo(existingAuthor.getId());
        assertThat(merged.description()).isEqualTo("AI 补充描述");
        verify(relationMapper).deleteById(aiDup.getId());

        // 3. Inline timeline event update canonicalizes aliases and updates chapter order
        TimelineEventEntity ev = new TimelineEventEntity();
        ev.setId(UUID.randomUUID());
        ev.setBookId(bookId);
        ev.setChapterNo(8);
        ev.setEventTitle("初见");
        ev.setSummary("旧摘要");
        ev.setInvolvedCharacters("林青");
        ev.setLocation("旧渡口");
        ev.setSource("AI_EXTRACTED");
        when(timelineMapper.selectById(ev.getId())).thenReturn(ev);

        var updatedEv = service.updateEvent(ownerId, bookId, ev.getId(),
                new VisualDtos.UpdateVisualEventRequest(9, "夜渡立约", "新摘要", "青儿,老渡", "黑水渡口", null, "PUBLIC"));
        assertThat(updatedEv.chapterNo()).isEqualTo(9);
        assertThat(updatedEv.eventTitle()).isEqualTo("夜渡立约");
        assertThat(updatedEv.involvedCharacters()).isEqualTo("林青,舟师");
        assertThat(updatedEv.location()).isEqualTo("黑水渡口");
        assertThat(updatedEv.source()).isEqualTo("AUTHOR");
        verify(timelineMapper).updateById(ev);
    }

    private VisualProjectionService createService() {
        BookEntity book = new BookEntity();
        book.setId(bookId);
        book.setOwnerId(ownerId);
        when(bookService.requireOwnedBook(ownerId, bookId)).thenReturn(book);
        var lore = new LoreService(bookService, characterMapper, worldEntryMapper, foreshadowingMapper,
                chapterMapper, mock(PromptTemplateMapper.class));
        lore.setChapterSectionMapper(sectionMapper);
        return new VisualProjectionService(
                bookService, lore, volumeMapper, chapterMapper, sectionMapper,
                characterMapper, worldEntryMapper, foreshadowingMapper,
                relationMapper, snapshotMapper, timelineMapper, summaryMapper
        );
    }

    private SampleBookFixture setupGoldenSampleBook() {
        VolumeEntity vol1 = new VolumeEntity();
        vol1.setId(UUID.randomUUID());
        vol1.setBookId(bookId);
        vol1.setVolumeNo(1);
        vol1.setTitle("第一卷·风起渡口");

        ChapterEntity ch1 = chapterRow(bookId, vol1.getId(), 1, "初出青云");
        ChapterEntity ch7 = chapterRow(bookId, vol1.getId(), 7, "风雨欲来");
        ChapterEntity ch8 = chapterRow(bookId, vol1.getId(), 8, "黑水渡口");
        ChapterEntity ch12 = chapterRow(bookId, vol1.getId(), 12, "夜渡立约");
        ChapterEntity ch16 = chapterRow(bookId, vol1.getId(), 16, "玉佩真相");
        ChapterEntity ch17 = chapterRow(bookId, vol1.getId(), 17, "暗潮涌动");
        ChapterEntity ch18 = chapterRow(bookId, vol1.getId(), 18, "渡口诀别");

        ChapterSectionEntity sec1_1 = sectionRow(bookId, ch1, 1);
        ChapterSectionEntity sec7_1 = sectionRow(bookId, ch7, 1);
        ChapterSectionEntity sec8_1 = sectionRow(bookId, ch8, 1);
        ChapterSectionEntity sec12_1 = sectionRow(bookId, ch12, 1);
        ChapterSectionEntity sec12_2 = sectionRow(bookId, ch12, 2);
        ChapterSectionEntity sec16_1 = sectionRow(bookId, ch16, 1);
        ChapterSectionEntity sec17_1 = sectionRow(bookId, ch17, 1);
        ChapterSectionEntity sec18_1 = sectionRow(bookId, ch18, 1);

        CharacterEntity lin = characterRow(bookId, "林青", "青儿", "PROTAGONIST", "CORE_PINNED", "青云宗");
        CharacterEntity zhoushi = characterRow(bookId, "舟师", "老渡", "SUPPORTING", "DEAD", "摆渡盟");
        zhoushi.setStatusNotes("第18章为护送林青战死");
        CharacterEntity gusan = characterRow(bookId, "顾三", "顾行舟", "SUPPORTING", "DEAD", "暗阁");
        gusan.setStatusNotes("第18章同样殒命");
        CharacterEntity futureVillain = characterRow(bookId, "未登场反派", "", "ANTAGONIST", "ACTIVE", "幽冥殿");

        // Snapshots: 林青 Ch.1 ACTIVE, 舟师 Ch.8 ACTIVE, 舟师 Ch.18 DEAD (顾三无快照以验证 UNKNOWN)
        CharacterStateSnapshotEntity snapLin1 = snapshotRow(bookId, lin.getId(), sec1_1,
                """
                {"roleType":"PROTAGONIST","lifecycleStatus":"CORE_PINNED","realmOrLevel":"炼气三层","currentLocation":"青云山","statusNotes":"初出宗门","customAttributes":{"faction":"青云宗"}}
                """);
        CharacterStateSnapshotEntity snapZhou8 = snapshotRow(bookId, zhoushi.getId(), sec8_1,
                """
                {"roleType":"SUPPORTING","lifecycleStatus":"ACTIVE","realmOrLevel":"筑基期","currentLocation":"黑水渡口","statusNotes":"隐姓埋名的摆渡人","customAttributes":{"faction":"摆渡盟"}}
                """);
        CharacterStateSnapshotEntity snapZhou18 = snapshotRow(bookId, zhoushi.getId(), sec18_1,
                """
                {"roleType":"SUPPORTING","lifecycleStatus":"DEAD","realmOrLevel":"筑基期","currentLocation":"黑水渡口","statusNotes":"力竭身亡","customAttributes":{"faction":"摆渡盟"}}
                """);

        // Section summaries: Ch.1 林青, Ch.12 Sec 1 顾三出场（无快照）
        SectionSummaryEntity sum1 = summaryRow(bookId, sec1_1, "林青", "林青下山历练。");
        SectionSummaryEntity sum12_1 = summaryRow(bookId, sec12_1, "林青,顾三", "林青与顾三在渡口试探。");

        // Relations: Ch.8 初遇, Ch.12 Sec 2 结盟, Ch.12 Sec 1 私密关系(knownBy=老渡), 无时间标注旧关系
        CharacterRelationEntity relMeet = relationRow(bookId, "林青", "舟师", "初遇", "AUTHOR", 8, sec8_1.getId(), 8_001L, "PUBLIC");
        CharacterRelationEntity relAlliance = relationRow(bookId, "林青", "舟师", "结盟", "AUTHOR", 12, sec12_2.getId(), 12_002L, "PUBLIC");
        CharacterRelationEntity relSecret = relationRow(bookId, "舟师", "顾三", "幕后密谋", "AUTHOR", 12, sec12_1.getId(), 12_001L, "老渡");
        CharacterRelationEntity relUndated = relationRow(bookId, "林青", "未登场反派", "旧怨", "AUTHOR", null, null, 0L, "PUBLIC");

        // Timeline events
        TimelineEventEntity ev8 = eventRow(bookId, 8, sec8_1, "黑水渡口初见舟师", "林青登舟", "林青,舟师", "黑水渡口", "PUBLIC");
        TimelineEventEntity ev12Secret = eventRow(bookId, 12, sec12_1, "密室密谈", "舟师密会顾三", "舟师,顾三", "渡口暗室", "老渡");
        TimelineEventEntity ev18Death = eventRow(bookId, 18, sec18_1, "舟师陨落", "舟师为护林青身亡", "林青,舟师", "黑水渡口", "PUBLIC");

        // Foreshadowings: #F03 (planted Ch.8, hints Ch.11 & Ch.15, resolved Ch.16), #F04 OVERDUE, #F05 DUE, #F06 PLANNED
        ForeshadowingEntity f03 = clueRow(bookId, "#F03", "残缺渡牌", "木牌背面刻着半截龙纹", "舟师实为皇室遗孤",
                "RESOLVED", 8, sec8_1.getId(), 8_001L, 14, 17, 16, sec16_1.getId(), "林青,舟师",
                """
                [{"chapterNo":11,"sectionNo":1,"quote":"木牌遇水泛金","note":"初次暗示"},{"chapterNo":15,"sectionNo":1,"quote":"龙纹完整显现","note":"回收前兆"}]
                """);
        ForeshadowingEntity f04 = clueRow(bookId, "#F04", "逾期暗信", "一封未拆的火漆信", "信中写明内鬼姓名",
                "PLANTED", 14, null, 14_001L, 15, 16, null, null, "林青", "[]");
        ForeshadowingEntity f05 = clueRow(bookId, "#F05", "到期古铃", "渡口铜铃无风自响", "古铃召唤水府旧部",
                "PLANTED", 17, sec17_1.getId(), 17_001L, 18, 20, null, null, "舟师", "[]");
        ForeshadowingEntity f06 = clueRow(bookId, "#F06", "未埋终局印", "尚未登场的星渊玉玺", "终卷核心底牌",
                "PLANNED", null, null, null, 25, 30, null, null, "未登场反派", "[]");

        when(volumeMapper.selectList(any())).thenReturn(List.of(vol1));
        when(chapterMapper.selectList(any())).thenReturn(List.of(ch1, ch7, ch8, ch12, ch16, ch17, ch18));
        when(sectionMapper.selectList(any())).thenReturn(List.of(sec1_1, sec7_1, sec8_1, sec12_1, sec12_2, sec16_1, sec17_1, sec18_1));
        when(characterMapper.selectList(any())).thenReturn(List.of(lin, zhoushi, gusan, futureVillain));
        when(worldEntryMapper.selectList(any())).thenReturn(List.of());
        when(snapshotMapper.selectList(any())).thenReturn(List.of(snapLin1, snapZhou8, snapZhou18));
        when(summaryMapper.selectList(any())).thenReturn(List.of(sum1, sum12_1));
        when(relationMapper.selectList(any())).thenReturn(List.of(relMeet, relAlliance, relSecret, relUndated));
        when(timelineMapper.selectList(any())).thenReturn(List.of(ev8, ev12Secret, ev18Death));
        when(foreshadowingMapper.selectList(any())).thenReturn(List.of(f03, f04, f05, f06));

        return new SampleBookFixture(ch7, ch12, ch16, ch17, sec12_1, sec12_2, sec16_1, zhoushi, f03, relAlliance);
    }

    private record SampleBookFixture(
            ChapterEntity ch7,
            ChapterEntity ch12,
            ChapterEntity ch16,
            ChapterEntity ch17,
            ChapterSectionEntity sec12_1,
            ChapterSectionEntity sec12_2,
            ChapterSectionEntity sec16_1,
            CharacterEntity zhoushi,
            ForeshadowingEntity f03,
            CharacterRelationEntity relAlliance
    ) {
    }

    private static ChapterEntity chapterRow(UUID bookId, UUID volumeId, int chapterNo, String title) {
        var ch = new ChapterEntity();
        ch.setId(UUID.randomUUID());
        ch.setBookId(bookId);
        ch.setVolumeId(volumeId);
        ch.setChapterNo(chapterNo);
        ch.setTitle(title);
        ch.setWordCount(2400);
        ch.setStatus("DRAFT");
        return ch;
    }

    private static ChapterSectionEntity sectionRow(UUID bookId, ChapterEntity chapter, int sectionNo) {
        var sec = new ChapterSectionEntity();
        sec.setId(UUID.randomUUID());
        sec.setBookId(bookId);
        sec.setChapterId(chapter.getId());
        sec.setChapterNo(chapter.getChapterNo());
        sec.setSectionNo(sectionNo);
        sec.setNarrativeOrder(StructureService.narrativeOrder(chapter.getChapterNo(), sectionNo));
        sec.setTitle("第" + sectionNo + "节");
        sec.setContentHash("hash-" + chapter.getChapterNo() + "-" + sectionNo);
        return sec;
    }

    private static CharacterEntity characterRow(
            UUID bookId, String name, String aliases, String roleType, String lifecycleStatus, String faction
    ) {
        var c = new CharacterEntity();
        c.setId(UUID.randomUUID());
        c.setBookId(bookId);
        c.setName(name);
        c.setAliases(aliases);
        c.setRoleType(roleType);
        c.setLifecycleStatus(lifecycleStatus);
        c.setCustomAttributes(faction.isEmpty() ? "{}" : "{\"faction\":\"" + faction + "\"}");
        c.setLockedFields("[]");
        return c;
    }

    private static CharacterStateSnapshotEntity snapshotRow(
            UUID bookId, UUID characterId, ChapterSectionEntity section, String json
    ) {
        var s = new CharacterStateSnapshotEntity();
        s.setId(UUID.randomUUID());
        s.setBookId(bookId);
        s.setCharacterId(characterId);
        s.setSectionId(section.getId());
        s.setNarrativeOrder(section.getNarrativeOrder());
        s.setSnapshotJson(json);
        s.setSource("AI_EXTRACTED");
        s.setCreatedAt(OffsetDateTime.now());
        return s;
    }

    private static SectionSummaryEntity summaryRow(
            UUID bookId, ChapterSectionEntity section, String involvedCharacters, String summaryText
    ) {
        var s = new SectionSummaryEntity();
        s.setId(UUID.randomUUID());
        s.setBookId(bookId);
        s.setChapterId(section.getChapterId());
        s.setSectionId(section.getId());
        s.setChapterNo(section.getChapterNo());
        s.setSectionNo(section.getSectionNo());
        s.setNarrativeOrder(section.getNarrativeOrder());
        s.setContentHash(section.getContentHash());
        s.setInvolvedCharacters(involvedCharacters);
        s.setSummaryText(summaryText);
        s.setStatus("READY");
        return s;
    }

    private static CharacterRelationEntity relationRow(
            UUID bookId, String src, String dst, String type, String source,
            Integer sinceCh, UUID sectionId, long order, String knownBy
    ) {
        var r = new CharacterRelationEntity();
        r.setId(UUID.randomUUID());
        r.setBookId(bookId);
        r.setSourceCharacter(src);
        r.setTargetCharacter(dst);
        r.setRelationType(type);
        r.setDescription(src + "与" + dst + type);
        r.setSource(source);
        r.setSinceChapterNo(sinceCh);
        r.setSourceSectionId(sectionId);
        r.setNarrativeOrder(order);
        r.setKnownBy(knownBy);
        r.setConfidence(1.0);
        r.setUpdatedAt(OffsetDateTime.now());
        return r;
    }

    private static TimelineEventEntity eventRow(
            UUID bookId, int chapterNo, ChapterSectionEntity sec, String title,
            String summary, String involved, String location, String knownBy
    ) {
        var e = new TimelineEventEntity();
        e.setId(UUID.randomUUID());
        e.setBookId(bookId);
        e.setChapterNo(chapterNo);
        e.setSourceSectionId(sec.getId());
        e.setNarrativeOrder(sec.getNarrativeOrder());
        e.setEventTitle(title);
        e.setSummary(summary);
        e.setInvolvedCharacters(involved);
        e.setLocation(location);
        e.setSource("AUTHOR");
        e.setKnownBy(knownBy);
        return e;
    }

    private static ForeshadowingEntity clueRow(
            UUID bookId, String code, String title, String surfaceClue, String hiddenTruth,
            String status, Integer plantedCh, UUID plantedSecId, Long plantedOrder,
            Integer minCh, Integer maxCh, Integer resolvedCh, UUID resolvedSecId,
            String involved, String hintHistoryJson
    ) {
        var f = new ForeshadowingEntity();
        f.setId(UUID.randomUUID());
        f.setBookId(bookId);
        f.setCode(code);
        f.setTitle(title);
        f.setDescription(surfaceClue);
        f.setSurfaceClue(surfaceClue);
        f.setHiddenTruth(hiddenTruth);
        f.setStatus(status);
        f.setImportance("MAIN_ARC");
        f.setRevealPolicy("HIDE_TRUTH");
        f.setPlantedChapterNo(plantedCh);
        f.setPlantedSectionId(plantedSecId);
        f.setPlantedNarrativeOrder(plantedOrder);
        f.setTargetChapterMin(minCh);
        f.setTargetChapterMax(maxCh);
        f.setResolvedChapterNo(resolvedCh);
        f.setResolvedSectionId(resolvedSecId);
        f.setInvolvedCharacters(involved);
        f.setHintHistoryJson(hintHistoryJson);
        f.setSource("AUTHOR");
        f.setAuthorLocked(false);
        return f;
    }
}
