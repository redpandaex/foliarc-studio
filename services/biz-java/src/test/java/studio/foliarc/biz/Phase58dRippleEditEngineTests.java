package studio.foliarc.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import studio.foliarc.biz.auth.InternalTokenFilter;
import studio.foliarc.biz.billing.entity.BillingAccountEntity;
import studio.foliarc.biz.billing.entity.BillingLedgerEntryEntity;
import studio.foliarc.biz.billing.mapper.BillingAccountMapper;
import studio.foliarc.biz.billing.mapper.BillingLedgerEntryMapper;
import studio.foliarc.biz.billing.mapper.GuardrailAuditLogMapper;
import studio.foliarc.biz.billing.service.BillingService;
import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.config.mybatis.PostgresUuidTypeHandler;
import studio.foliarc.biz.gateway.RedisRateLimiterService;
import studio.foliarc.biz.lore.entity.CharacterEntity;
import studio.foliarc.biz.lore.mapper.CharacterMapper;
import studio.foliarc.biz.memory.service.StoryMemoryContextService;
import studio.foliarc.biz.novel.entity.BookEntity;
import studio.foliarc.biz.novel.entity.ChapterEntity;
import studio.foliarc.biz.novel.entity.ChapterSectionEntity;
import studio.foliarc.biz.novel.entity.ChapterVersionEntity;
import studio.foliarc.biz.novel.entity.SectionVersionEntity;
import studio.foliarc.biz.novel.mapper.ChapterMapper;
import studio.foliarc.biz.novel.mapper.ChapterSectionMapper;
import studio.foliarc.biz.novel.mapper.ChapterVersionMapper;
import studio.foliarc.biz.novel.mapper.SectionVersionMapper;
import studio.foliarc.biz.novel.service.BookService;
import studio.foliarc.biz.novel.service.StructureService;
import studio.foliarc.biz.novel.service.WordCounter;
import studio.foliarc.biz.outbox.RabbitMqTopologyConfig;
import studio.foliarc.biz.outbox.service.OutboxService;
import studio.foliarc.biz.rag.service.KnowledgeDocumentService;
import studio.foliarc.biz.ripple.controller.InternalRippleController;
import studio.foliarc.biz.ripple.dto.RippleDtos.ApplyPatchesRequest;
import studio.foliarc.biz.ripple.dto.RippleDtos.FactDeltaItem;
import studio.foliarc.biz.ripple.dto.RippleDtos.GeneratePatchesRequest;
import studio.foliarc.biz.ripple.dto.RippleDtos.InternalAnalysisResultRequest;
import studio.foliarc.biz.ripple.dto.RippleDtos.InternalCandidateSearchRequest;
import studio.foliarc.biz.ripple.dto.RippleDtos.InternalCandidateSection;
import studio.foliarc.biz.ripple.dto.RippleDtos.InternalImpactInput;
import studio.foliarc.biz.ripple.dto.RippleDtos.InternalPatchBatchResultRequest;
import studio.foliarc.biz.ripple.dto.RippleDtos.InternalPatchResultItem;
import studio.foliarc.biz.ripple.dto.RippleDtos.PatchHunkItem;
import studio.foliarc.biz.ripple.dto.RippleDtos.StartRippleAnalyzeRequest;
import studio.foliarc.biz.ripple.entity.RippleImpactEntity;
import studio.foliarc.biz.ripple.entity.RippleJobEntity;
import studio.foliarc.biz.ripple.entity.RipplePatchEntity;
import studio.foliarc.biz.ripple.mapper.RippleCandidateMapper;
import studio.foliarc.biz.ripple.mapper.RippleImpactMapper;
import studio.foliarc.biz.ripple.mapper.RippleJobMapper;
import studio.foliarc.biz.ripple.mapper.RipplePatchMapper;
import studio.foliarc.biz.ripple.service.RippleEditService;
import studio.foliarc.biz.style.service.StyleAnchorService;
import studio.foliarc.biz.vip.dto.VipDtos.ResolvedUserEntitlements;
import studio.foliarc.biz.vip.service.VipEntitlementService;
import tools.jackson.databind.ObjectMapper;

class Phase58dRippleEditEngineTests {

    private static final FactDeltaItem FACT = new FactDeltaItem(
            "CHARACTER_STATE", "沈舟", "战死", "负伤逃走", List.of("旧玉佩"));

    private final UUID ownerId = UUID.randomUUID();
    private final UUID bookId = UUID.randomUUID();
    private final RippleJobMapper jobs = mock(RippleJobMapper.class);
    private final RippleImpactMapper impacts = mock(RippleImpactMapper.class);
    private final RipplePatchMapper patches = mock(RipplePatchMapper.class);
    private final RippleCandidateMapper candidates = mock(RippleCandidateMapper.class);
    private final BookService books = mock(BookService.class);
    private final ChapterMapper chapters = mock(ChapterMapper.class);
    private final ChapterSectionMapper sections = mock(ChapterSectionMapper.class);
    private final SectionVersionMapper sectionVersions = mock(SectionVersionMapper.class);
    private final ChapterVersionMapper chapterVersions = mock(ChapterVersionMapper.class);
    private final CharacterMapper characters = mock(CharacterMapper.class);
    private final VipEntitlementService vip = mock(VipEntitlementService.class);
    private final BillingService billing = mock(BillingService.class);
    private final OutboxService outbox = mock(OutboxService.class);
    private final StyleAnchorService style = mock(StyleAnchorService.class);
    private final StoryMemoryContextService memory = mock(StoryMemoryContextService.class);
    private final KnowledgeDocumentService knowledge = mock(KnowledgeDocumentService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ObjectMapper json = new ObjectMapper();
    private final RippleEditService service = new RippleEditService(jobs, impacts, patches, candidates, books,
            chapters, sections, sectionVersions, chapterVersions, characters, vip, billing, outbox, style, memory,
            knowledge, json, transactions);
    private final List<RippleJobEntity> createdJobs = new ArrayList<>();
    private ChapterSectionEntity source;

    @BeforeAll
    static void initializeMapperMetadata() {
        var configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(PostgresUuidTypeHandler.class);
        var assistant = new MapperBuilderAssistant(configuration, "phase58d");
        for (Class<?> entity : List.of(ChapterSectionEntity.class, RipplePatchEntity.class, BillingLedgerEntryEntity.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @BeforeEach
    void setUp() {
        source = section(100, 2, "沈舟负伤逃走。", 1);
        BookEntity book = new BookEntity();
        book.setId(bookId);
        book.setOwnerId(ownerId);
        book.setTitle("暮色行舟");
        book.setGenre("玄幻");
        book.setGenreCode("XUANHUAN");
        when(books.requireOwnedBook(ownerId, bookId)).thenReturn(book);
        when(chapters.findMaxChapterNo(bookId)).thenReturn(4_000);
        when(jobs.insert(any(RippleJobEntity.class))).thenAnswer(call -> {
            RippleJobEntity job = call.getArgument(0);
            createdJobs.add(job);
            when(jobs.selectById(job.getId())).thenReturn(job);
            return 1;
        });
        when(vip.quotaExceeded(any(), anyString(), anyLong(), anyLong(), anyString())).thenAnswer(call ->
                BizException.vipQuotaExceeded(call.getArgument(4), Map.of(
                        "feature", call.<String>getArgument(1),
                        "currentLimit", String.valueOf(call.<Long>getArgument(2)),
                        "requested", String.valueOf(call.<Long>getArgument(3)))));
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void freeFallbackRejectsWideCustomRangeAndFullBook() {
        assertQuota(() -> start("CUSTOM_RANGE", 1, 51, null), "RIPPLE_RANGE", 50);
        assertQuota(() -> start("FULL_BOOK", null, null, null), "RIPPLE_RANGE", 50);

        assertThat(createdJobs).isEmpty();
        verifyNoInteractions(outbox);
        verify(vip, never()).incrementRippleScanToday(ownerId);
    }

    @Test
    void freeDailyQuotaUsesRedisAndCommittedJobs() {
        when(vip.resolveForUser(ownerId)).thenReturn(entitlements("FREE", 10_000));
        when(vip.getRippleScansUsedToday(ownerId)).thenReturn(3, 0);
        when(jobs.selectCount(any())).thenReturn(0L, 3L);

        assertQuota(() -> start(null, null, null, null), "RIPPLE_DAILY_SCANS", 3);
        assertQuota(() -> start(null, null, null, null), "RIPPLE_DAILY_SCANS", 3);

        verify(jobs, times(2)).lockOwnerForScanQuota(ownerId);
        verifyNoInteractions(outbox);
        verify(vip, never()).incrementRippleScanToday(ownerId);
    }

    @Test
    void studioCanScanFullBookAndCustomRangeOfExactlyTwoThousandChapters() {
        when(vip.resolveForUser(ownerId)).thenReturn(entitlements("STUDIO", 8000));
        assertRange(start("FULL_BOOK", null, null, null), 1, 4_000, 1_001L, 4_000_999L);
        RippleJobEntity custom = start("CUSTOM_RANGE", 200, 2_199, null);
        assertRange(custom, 200, 2_199, 200_001L, 2_199_999L);
        assertThat(custom.getVipPlanCode()).isEqualTo("STUDIO");
        assertThat(custom.getMaxCandidatesCap()).isEqualTo(150);
        assertThat(custom.getMaxPatchBatchLimit()).isEqualTo(100);
        assertQuota(() -> start("CUSTOM_RANGE", 200, 2_200, null), "RIPPLE_RANGE", 2_000);
        verify(vip, never()).getRippleScansUsedToday(ownerId);
    }

    @Test
    void rangesUseSourceSectionBoundariesAndTheSelectedVolumesSpan() {
        assertRange(start("FORWARD", null, 115, null), 100, 115, 100_003L, 115_999L);
        assertRange(start("BACKWARD", 88, null, null), 88, 100, 88_001L, 100_001L);
        assertRange(start(null, null, null, null), 75, 124, 75_001L, 124_999L);
        assertRange(start("BIDIRECTIONAL", 94, 108, null), 94, 108, 94_001L, 108_999L);

        UUID first = UUID.randomUUID();
        UUID last = UUID.randomUUID();
        when(chapters.findVolumeChapterRanges(bookId)).thenReturn(List.of(volume(first, 81, 90), volume(last, 96, 110)));
        RippleJobEntity custom = start("CUSTOM_RANGE", null, null, List.of(first, last));
        assertRange(custom, 81, 110, 81_001L, 110_999L);
        assertThat(json.readTree(custom.getSelectedVolumeIds()).get(0).asString()).isEqualTo(first.toString());
        assertThatThrownBy(() -> start("CUSTOM_RANGE", null, null, List.of(UUID.randomUUID())))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo("INVALID_RIPPLE_RANGE"));
    }

    @Test
    void snapshotsAreScopedToTheSourceAndWorkerContextUsesTheStoredEdit() {
        UUID versionId = UUID.randomUUID();
        SectionVersionEntity foreign = new SectionVersionEntity();
        foreign.setSectionId(UUID.randomUUID());
        when(sectionVersions.selectById(versionId)).thenReturn(foreign);
        var request = new StartRippleAnalyzeRequest(null, null, null, null, versionId, null, "修改后的正文", "让沈舟活着");
        assertThatThrownBy(() -> service.startAnalyze(ownerId, bookId, source.getId(), request))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo("RIPPLE_VERSION_NOT_FOUND"));

        source.setIsDefault(true);
        ChapterVersionEntity legacy = new ChapterVersionEntity();
        legacy.setChapterId(source.getChapterId());
        legacy.setContent("沈舟战死。");
        when(sectionVersions.selectById(versionId)).thenReturn(null);
        when(chapterVersions.selectById(versionId)).thenReturn(legacy);
        when(style.buildStyleAnchorsText(bookId)).thenReturn("夜色像一匹暗绸。");
        var response = service.startAnalyze(ownerId, bookId, source.getId(), request);
        var context = service.getJobContext(response.id());

        assertThat(context.sourceSectionNo()).isEqualTo(2);
        assertThat(context.oldContent()).isEqualTo("沈舟战死。");
        assertThat(context.newContent()).isEqualTo("修改后的正文");
        assertThat(context.changeNote()).isEqualTo("让沈舟活着");
        assertThat(context.styleAnchorsText()).isEqualTo("夜色像一匹暗绸。");
        String key = "RIPPLE_ANALYZE:" + response.id();
        verify(outbox).enqueue("RIPPLE_JOB", response.id(), "RIPPLE_ANALYZE", RabbitMqTopologyConfig.RK_RIPPLE_ANALYZE,
                key, Map.of("job_id", response.id().toString(), "book_id", bookId.toString(), "owner_id", ownerId.toString(),
                        "source_section_id", source.getId().toString(), "idempotency_key", key));
        verify(vip).incrementRippleScanToday(ownerId);

        SectionVersionEntity latest = new SectionVersionEntity();
        latest.setContent("最近一次小节快照");
        when(sectionVersions.selectOne(any())).thenReturn(latest);
        assertThat(start(null, null, null, null).getOldContentSnapshot()).isEqualTo(latest.getContent());
    }

    @Test
    void candidateSearchExpandsAliasesAndPassesTheRangeSourceExclusionAndCapToPostgres() {
        RippleJobEntity job = job("ANALYZING");
        job.setMaxCandidatesCap(2);
        when(sections.selectCount(any())).thenReturn(8L);
        CharacterEntity character = new CharacterEntity();
        character.setName("沈舟");
        character.setAliases("小舟、阿舟, Captain Zhou");
        CharacterEntity unrelated = new CharacterEntity();
        unrelated.setName("陆砚");
        unrelated.setAliases("阿砚");
        when(characters.selectList(any())).thenReturn(List.of(character, unrelated));
        var match = new InternalCandidateSection(UUID.randomUUID(), UUID.randomUUID(), 101, 1, 101_001L,
                "援兵", "潜行", 4L, "", "阿舟潜入城中。", "CONTENT");
        List<String> terms = List.of("沈舟", "小舟", "阿舟", "captain zhou", "旧玉佩");
        when(candidates.findCandidates(job, terms, 2)).thenReturn(List.of(match));

        var result = service.searchCandidates(job.getId(), new InternalCandidateSearchRequest(
                List.of("  沈舟  "), List.of("旧玉佩", "小舟"), 20));

        assertThat(result.scannedSectionCount()).isEqualTo(8);
        assertThat(result.candidates()).containsExactly(match);
        ArgumentCaptor<LambdaQueryWrapper<ChapterSectionEntity>> range = ArgumentCaptor.captor();
        verify(sections).selectCount(range.capture());
        assertThat(range.getValue().getSqlSegment()).contains("book_id =", "narrative_order BETWEEN", "id <>")
                .doesNotContain("LIMIT");
        assertThat(range.getValue().getParamNameValuePairs().values())
                .containsExactlyInAnyOrder(bookId, job.getRangeStartOrder(), job.getRangeEndOrder(), source.getId());

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(PostgresUuidTypeHandler.class);
        configuration.addMapper(RippleCandidateMapper.class);
        var sql = configuration.getMappedStatement(RippleCandidateMapper.class.getName() + ".findCandidates")
                .getBoundSql(Map.of("job", job, "terms", terms, "limit", 2));
        assertThat(sql.getSql()).contains("LEFT JOIN section_summaries", "s.narrative_order BETWEEN ? AND ?",
                "s.id != ?", "ORDER BY hits.summary_hits + hits.content_hits DESC", "LIMIT ?");
    }

    @Test
    void analysisCountsValidatedImpactsAndEnforcesBlastRadius() {
        RippleJobEntity job = job("ANALYZING");
        job.setMaxPatchBatchLimit(1);
        when(vip.resolveForUser(ownerId)).thenReturn(entitlements("SVIP", 9000));
        ChapterSectionEntity before = section(99, 1, "", 1);
        ChapterSectionEntity after = section(101, 1, "", 1);
        ChapterSectionEntity minor = section(102, 1, "", 1);
        ChapterSectionEntity wrongDirection = section(98, 1, "", 1);
        when(sections.selectList(any())).thenReturn(List.of(before, after, minor, wrongDirection));
        when(chapters.selectByIds(anyCollection())).thenReturn(List.of(chapter(before, "出城")));
        var report = new InternalAnalysisResultRequest("READY_TO_PATCH", "沈舟由死改伤", List.of(FACT), 20, 5,
                List.of(reported(before, "BACKWARD", "STRONG_CONFLICT"),
                        reported(after, "FORWARD", "STRONG_CONFLICT"),
                        reported(minor, "FORWARD", "MINOR_MENTION"),
                        reported(source, "FORWARD", "STRONG_CONFLICT"),
                        reported(wrongDirection, "FORWARD", "STRONG_CONFLICT"),
                        reported(after, "FORWARD", "STRONG_CONFLICT")), 12_345L, null);

        var saved = service.saveAnalysisResult(job.getId(), report);
        assertThat(saved.status()).isEqualTo("BLAST_RADIUS_EXCEEDED");
        assertThat(saved.backwardConflictCount()).isEqualTo(1);
        assertThat(saved.forwardConflictCount()).isEqualTo(1);
        assertThat(saved.minorMentionCount()).isEqualTo(1);
        assertThat(saved.estimatedCostMicros()).isEqualTo(342_000L);
        assertThat(saved.actualCostMicros()).isEqualTo(12_345L);
        assertThat(saved.scannedSectionCount()).isEqualTo(20);
        assertThat(saved.candidateSectionCount()).isEqualTo(5);

        ArgumentCaptor<RippleImpactEntity> rows = ArgumentCaptor.forClass(RippleImpactEntity.class);
        verify(impacts, times(3)).insert(rows.capture());
        assertThat(rows.getAllValues()).extracting(RippleImpactEntity::getTargetSectionId)
                .containsExactly(before.getId(), after.getId(), minor.getId());
        RippleImpactEntity verified = rows.getAllValues().getFirst();
        assertThat(verified.getNarrativeOrder()).isEqualTo(before.getNarrativeOrder());
        assertThat(verified.getChapterTitle()).isEqualTo("出城");
        var detail = service.getJobDetail(ownerId, bookId, job.getId());
        assertThat(detail.factDeltas()).containsExactly(FACT);

        assertThat(service.saveAnalysisResult(job.getId(), report)).isEqualTo(saved);
        verify(impacts).delete(any());
    }

    @Test
    void noChangeAndFailureDoNotOfferPatches() {
        RippleJobEntity noChanges = job("ANALYZING");
        var empty = new InternalAnalysisResultRequest("READY_TO_PATCH", "", List.of(), 2, 0, List.of(), 0L, null);
        assertThat(service.saveAnalysisResult(noChanges.getId(), empty).status()).isEqualTo("NO_CHANGE");

        RippleJobEntity failed = job("ANALYZING");
        var error = new InternalAnalysisResultRequest("FAILED", "", List.of(), 0, 0, List.of(), 0L, "worker error");
        assertThat(service.saveAnalysisResult(failed.getId(), error).status()).isEqualTo("FAILED");
        assertThat(failed.getErrorMessage()).isEqualTo("worker error");
    }

    @Test
    void generatesAndSettlesTwoBatchesWithSeparateIdempotencyKeys() {
        RippleJobEntity job = job("BLAST_RADIUS_EXCEEDED");
        job.setMaxPatchBatchLimit(1);
        job.setActualCostMicros(17L);
        ChapterSectionEntity first = section(99, 1, "沈舟已经死了。", 7);
        ChapterSectionEntity second = section(101, 1, "城里没有沈舟。", 3);
        RippleImpactEntity firstImpact = impact(job, first);
        RippleImpactEntity secondImpact = impact(job, second);
        List<RippleImpactEntity> available = List.of(firstImpact, secondImpact);
        when(impacts.selectList(any())).thenReturn(available);
        when(impacts.selectByIds(anyCollection())).thenAnswer(call -> {
            List<UUID> ids = List.copyOf(call.getArgument(0));
            return available.stream().filter(row -> ids.contains(row.getId())).toList();
        });
        when(sections.selectList(any())).thenReturn(List.of(first, second));
        when(style.buildStyleAnchorsText(bookId)).thenReturn("短句叙事");
        when(billing.freezeCustom(eq(ownerId), anyString(), anyLong(), eq("fast-router"), anyString()))
                .thenAnswer(call -> call.getArgument(2));
        when(billing.settleCustom(eq(ownerId), anyString(), eq(200_000L), eq("fast-router"),
                anyInt(), anyInt(), anyInt(), anyString())).thenReturn(1_000L, 2_000L);
        List<RipplePatchEntity> stored = new ArrayList<>();
        when(patches.insert(any(RipplePatchEntity.class))).thenAnswer(call -> {
            stored.add(call.getArgument(0));
            return 1;
        });
        when(patches.selectList(any())).thenAnswer(call -> {
            LambdaQueryWrapper<RipplePatchEntity> query = call.getArgument(0);
            if (query.getSqlSegment().contains("status =")) {
                return stored.stream().filter(row -> "PENDING".equals(row.getStatus())
                        && query.getParamNameValuePairs().containsValue(row.getBatchNo())).toList();
            }
            return List.copyOf(stored);
        });

        assertQuota(() -> service.generatePatches(ownerId, bookId, job.getId(),
                new GeneratePatchesRequest(List.of(firstImpact.getId(), secondImpact.getId()))), "RIPPLE_PATCH_CHAPTERS", 1);
        verifyNoInteractions(billing, outbox);

        var batchOne = service.generatePatches(ownerId, bookId, job.getId(),
                new GeneratePatchesRequest(List.of(firstImpact.getId())));
        assertThat(batchOne.job().status()).isEqualTo("PATCHING");
        assertThat(firstImpact.getPatchStatus()).isEqualTo("QUEUED");
        assertThat(job.getFrozenCostMicros()).isEqualTo(200_000L);
        assertThat(batchOne.patches()).singleElement().satisfies(patch -> {
            assertThat(patch.batchNo()).isEqualTo(1);
            assertThat(patch.baseVersion()).isEqualTo(7L);
        });
        var context = service.getPatchBatchTargets(job.getId(), 1);
        assertThat(context.styleAnchorsText()).isEqualTo("短句叙事");
        assertThat(context.targets()).singleElement().satisfies(target -> {
            assertThat(target.patchId()).isEqualTo(stored.getFirst().getId());
            assertThat(target.currentContent()).isEqualTo(first.getContent());
        });
        var resultOne = patchResult(stored.getFirst(), first.getContent());
        assertThat(service.savePatchBatchResult(job.getId(), 1, resultOne).status()).isEqualTo("READY_FOR_REVIEW");
        assertThat(stored.getFirst().getStatus()).isEqualTo("READY_FOR_REVIEW");

        var batchTwo = service.generatePatches(ownerId, bookId, job.getId(),
                new GeneratePatchesRequest(List.of(secondImpact.getId())));
        assertThat(batchTwo.patches()).extracting(row -> row.batchNo()).containsExactly(1, 2);
        assertThat(service.savePatchBatchResult(job.getId(), 1, resultOne).status()).isEqualTo("PATCHING");
        var resultTwo = patchResult(stored.getLast(), "缺少原文锚点");
        assertThat(service.savePatchBatchResult(job.getId(), 2, resultTwo).status()).isEqualTo("READY_FOR_REVIEW");
        assertThat(stored.getLast().getStatus()).isEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat(secondImpact.getPatchStatus()).isEqualTo("READY_FOR_REVIEW");
        assertThat(job.getFrozenCostMicros()).isZero();
        assertThat(job.getActualCostMicros()).isEqualTo(3_017L);
        assertThat(job.getNextBatchNo()).isEqualTo(3);

        for (int batchNo = 1; batchNo <= 2; batchNo++) {
            String key = job.getId() + ":b" + batchNo;
            verify(billing).freezeCustom(eq(ownerId), eq("RIPPLE_FREEZE:" + key), eq(200_000L), eq("fast-router"), anyString());
            verify(billing).settleCustom(eq(ownerId), eq("RIPPLE_SETTLE:" + key), eq(200_000L), eq("fast-router"),
                    eq(1_000), eq(500), eq(250), anyString());
            verify(outbox).enqueue("RIPPLE_JOB", job.getId(), "RIPPLE_PATCH", RabbitMqTopologyConfig.RK_RIPPLE_PATCH,
                    "RIPPLE_PATCH:" + key, Map.of("job_id", job.getId().toString(), "book_id", bookId.toString(),
                            "owner_id", ownerId.toString(), "batch_no", batchNo, "idempotency_key", "RIPPLE_PATCH:" + key));
        }
    }

    @Test
    void appliesExactHunksAndOnlyRefreshesWrittenSectionsAfterCommit() {
        RippleJobEntity job = job("READY_FOR_REVIEW");
        ChapterSectionEntity accepted = section(99, 1, "城门关闭。\n\n沈舟战死。\n\n众人守城。", 5);
        ChapterSectionEntity stale = section(101, 1, "沈舟战死。", 6);
        ChapterSectionEntity missingAnchor = section(102, 1, "旧句已被改写。", 5);
        ChapterSectionEntity rejected = section(103, 1, "沈舟战死。", 5);
        var applyImpact = impact(job, accepted);
        var staleImpact = impact(job, stale);
        var missingImpact = impact(job, missingAnchor);
        var rejectedImpact = impact(job, rejected);
        var goodPatch = patch(job, applyImpact, 5, new PatchHunkItem("沈舟战死。", "沈舟负伤逃走。", "统一人物状态"));
        var stalePatch = patch(job, staleImpact, 5, new PatchHunkItem("沈舟战死。", "沈舟逃走。", ""));
        var missingPatch = patch(job, missingImpact, 5, new PatchHunkItem("沈舟战死。", "沈舟逃走。", ""));
        var rejectedPatch = patch(job, rejectedImpact, 5, new PatchHunkItem("沈舟战死。", "沈舟逃走。", ""));
        when(impacts.selectByIds(anyCollection())).thenReturn(List.of(applyImpact, staleImpact, missingImpact, rejectedImpact));
        when(patches.selectByIds(anyCollection())).thenReturn(List.of(goodPatch, stalePatch, missingPatch, rejectedPatch));
        when(impacts.selectCount(any())).thenReturn(2L);
        ChapterEntity updatedChapter = chapter(accepted, "守城");
        when(chapters.selectById(accepted.getChapterId())).thenReturn(updatedChapter);
        when(chapters.selectById(stale.getChapterId())).thenReturn(chapter(stale, "追兵"));
        when(chapters.selectById(missingAnchor.getChapterId())).thenReturn(chapter(missingAnchor, "余波"));
        when(chapters.updateById(updatedChapter)).thenReturn(1);
        when(sections.updateById(accepted)).thenAnswer(call -> {
            accepted.setVersion(6L);
            return 1;
        });
        when(sectionVersions.findMaxVersionNo(accepted.getId())).thenReturn(2);
        String before = accepted.getContent();

        var decision = new ApplyPatchesRequest(List.of(goodPatch.getId(), stalePatch.getId(), missingPatch.getId()),
                List.of(rejectedPatch.getId()));
        TransactionSynchronizationManager.initSynchronization();
        try {
            var result = service.applyPatches(ownerId, bookId, job.getId(), decision);
            assertThat(result.jobStatus()).isEqualTo("PARTIALLY_APPLIED");
            assertThat(result.appliedCount()).isEqualTo(1);
            assertThat(result.conflictCount()).isEqualTo(1);
            assertThat(result.manualReviewCount()).isEqualTo(1);
            assertThat(result.rejectedCount()).isEqualTo(1);
            assertThat(result.appliedSectionIds()).containsExactly(accepted.getId());
            verifyNoInteractions(memory, knowledge);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(goodPatch.getStatus()).isEqualTo("APPLIED");
        assertThat(stalePatch.getStatus()).isEqualTo("VERSION_CONFLICT");
        assertThat(missingPatch.getStatus()).isEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat(rejectedPatch.getStatus()).isEqualTo("REJECTED");
        assertThat(applyImpact.getPatchStatus()).isEqualTo("APPLIED");
        assertThat(rejectedImpact.getPatchStatus()).isEqualTo("SKIPPED");
        assertThat(accepted.getContent()).isEqualTo("城门关闭。\n\n沈舟负伤逃走。\n\n众人守城。");
        assertThat(accepted.getVersion()).isEqualTo(6L);
        assertThat(accepted.getSummaryDirty()).isTrue();
        assertThat(accepted.getContentHash()).isEqualTo(StoryMemoryContextService.sha256Hex(accepted.getContent()));
        assertThat(updatedChapter.getContent()).isEqualTo(accepted.getContent());
        assertThat(updatedChapter.getWordCount()).isEqualTo(WordCounter.count(accepted.getContent()));

        ArgumentCaptor<SectionVersionEntity> backup = ArgumentCaptor.forClass(SectionVersionEntity.class);
        verify(sectionVersions).insert(backup.capture());
        assertThat(backup.getValue().getContent()).isEqualTo(before);
        assertThat(backup.getValue().getVersionNo()).isEqualTo(3);
        assertThat(backup.getValue().getChangeSummary()).isEqualTo("蝴蝶效应修补前自动快照");
        assertThat(backup.getValue().getSectionId()).isEqualTo(accepted.getId());

        var locks = inOrder(books, jobs, chapters, sections);
        locks.verify(books).lockStructure(bookId);
        locks.verify(jobs).selectOne(any());
        locks.verify(chapters).lockForStructure(bookId, accepted.getChapterId());
        locks.verify(sections).updateById(accepted);
        verify(sections, times(1)).updateById(any(ChapterSectionEntity.class));
        verify(memory).invalidateAndEnqueueSectionDistill(ownerId, bookId, accepted);
        verify(memory, times(1)).invalidateAndEnqueueSectionDistill(any(), any(), any());
        verify(knowledge).syncChapter(ownerId, bookId, accepted.getChapterId(), false);
        verify(knowledge, times(1)).syncChapter(any(), any(), any(), eq(false));
    }

    @Test
    void multiSectionChapterAggregatesWordsAndAnIndexFailureKeepsThePatchApplied() {
        RippleJobEntity job = job("READY_FOR_REVIEW");
        ChapterSectionEntity edited = section(101, 2, "战死。", 3);
        ChapterSectionEntity primary = section(101, 1, "城门静默。", 4);
        primary.setChapterId(edited.getChapterId());
        ChapterEntity chapter = chapter(primary, "外城");
        chapter.setStructureMode(StructureService.MULTI_SECTION);
        RippleImpactEntity impact = impact(job, edited);
        RipplePatchEntity patch = patch(job, impact, 3, new PatchHunkItem("战死。", "负伤逃走。", "统一"));
        when(patches.selectByIds(anyCollection())).thenReturn(List.of(patch));
        when(impacts.selectByIds(anyCollection())).thenReturn(List.of(impact));
        when(chapters.selectById(chapter.getId())).thenReturn(chapter);
        when(chapters.updateById(chapter)).thenReturn(1);
        when(sections.updateById(edited)).thenReturn(1);
        when(sections.selectList(any())).thenReturn(List.of(primary, edited));
        doThrow(new IllegalStateException("index unavailable")).when(knowledge)
                .syncChapter(ownerId, bookId, chapter.getId(), false);

        var decision = new ApplyPatchesRequest(List.of(patch.getId()), List.of());
        var result = service.applyPatches(ownerId, bookId, job.getId(), decision);

        assertThat(result.jobStatus()).isEqualTo("APPLIED");
        assertThat(result.appliedSectionIds()).containsExactly(edited.getId());
        assertThat(chapter.getWordCount()).isEqualTo(primary.getWordCount() + WordCounter.count("负伤逃走。"));
        assertThat(chapter.getContent()).isEqualTo(primary.getContent());
        verify(memory).invalidateAndEnqueueSectionDistill(ownerId, bookId, edited);
        assertThat(service.applyPatches(ownerId, bookId, job.getId(), decision).appliedCount()).isZero();
        verify(sectionVersions).insert(any(SectionVersionEntity.class));
        verify(knowledge).syncChapter(ownerId, bookId, chapter.getId(), false);
    }

    @Test
    void deferredRefreshUsesTheChapterContentSavedAfterThePatch() {
        RippleJobEntity job = job("READY_FOR_REVIEW");
        ChapterSectionEntity edited = section(101, 1, "旧段落", 3);
        ChapterEntity chapter = chapter(edited, "外城");
        RippleImpactEntity impact = impact(job, edited);
        RipplePatchEntity patch = patch(job, impact, 3, new PatchHunkItem("旧段落", "", "删除"));
        when(patches.selectByIds(anyCollection())).thenReturn(List.of(patch));
        when(impacts.selectByIds(anyCollection())).thenReturn(List.of(impact));
        when(chapters.selectById(chapter.getId())).thenReturn(chapter);
        when(chapters.updateById(chapter)).thenReturn(1);
        when(sections.updateById(edited)).thenReturn(1);

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(service.applyPatches(ownerId, bookId, job.getId(),
                    new ApplyPatchesRequest(List.of(patch.getId()), List.of())).appliedCount()).isEqualTo(1);
            assertThat(chapter.getContent()).isEmpty();
            ChapterSectionEntity newer = section(101, 1, "作者刚保存的新正文", 5);
            newer.setId(edited.getId());
            newer.setChapterId(chapter.getId());
            newer.setContentHash(StoryMemoryContextService.sha256Hex(newer.getContent()));
            when(sections.selectById(edited.getId())).thenReturn(newer);
            when(chapters.selectById(chapter.getId())).thenReturn(chapter(newer, "外城"));
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verifyNoInteractions(memory);
        verify(knowledge).syncChapter(ownerId, bookId, chapter.getId(), false);
        verify(knowledge, never()).deleteBySourceChapter(any(), any(), any());
    }

    @Test
    void cancelReleasesTheOutstandingBatchOnceAndIgnoresLateResults() {
        RippleJobEntity job = job("PATCHING");
        job.setNextBatchNo(3);
        job.setFrozenCostMicros(200_000L);
        RippleImpactEntity impact = impact(job, section(101, 1, "旧段落", 0));
        impact.setPatchStatus("QUEUED");
        RipplePatchEntity patch = patch(job, impact, 0);
        patch.setStatus("PENDING");
        patch.setBatchNo(2);
        when(patches.selectList(any())).thenReturn(List.of(patch));
        when(impacts.selectByIds(anyCollection())).thenReturn(List.of(impact));

        assertThat(service.cancelJob(ownerId, bookId, job.getId()).status()).isEqualTo("CANCELLED");
        assertThat(job.getFrozenCostMicros()).isZero();
        assertThat(patch.getStatus()).isEqualTo("REJECTED");
        assertThat(impact.getPatchStatus()).isEqualTo("SKIPPED");
        assertThat(service.cancelJob(ownerId, bookId, job.getId()).status()).isEqualTo("CANCELLED");
        assertThat(service.savePatchBatchResult(job.getId(), 2, patchResult(patch, "旧段落")).status())
                .isEqualTo("CANCELLED");
        assertThat(service.getPatchBatchTargets(job.getId(), 2).targets()).isEmpty();
        verify(billing).settleCustom(ownerId, "RIPPLE_SETTLE:" + job.getId() + ":b2",
                200_000L, "fast-router", 0, 0, 0, "任务取消释放");
        verifyNoInteractions(memory, knowledge);
    }

    @Test
    @SuppressWarnings("unchecked")
    void customBillingReplaysFreezesAndSettlementsAndRefundsAtTheVipRate() {
        BillingAccountMapper accounts = mock(BillingAccountMapper.class);
        BillingLedgerEntryMapper ledger = mock(BillingLedgerEntryMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> redisValues = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(redisValues);
        when(redisValues.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        BillingAccountEntity account = new BillingAccountEntity();
        account.setUserId(ownerId);
        account.setBalanceMicros(1_000_000L);
        account.setFrozenMicros(0L);
        account.setMonthlyBudgetMicros(5_000_000L);
        account.setMonthlySpentMicros(0L);
        account.setBudgetMonth(YearMonth.now().toString());
        account.setVersion(0L);
        when(accounts.selectById(ownerId)).thenReturn(account);
        when(accounts.selectForUpdate(ownerId)).thenReturn(account);
        when(accounts.updateById(account)).thenReturn(1);

        Map<String, BillingLedgerEntryEntity> entries = new HashMap<>();
        when(ledger.insert(any(BillingLedgerEntryEntity.class))).thenAnswer(call -> {
            BillingLedgerEntryEntity entry = call.getArgument(0);
            entries.put(entry.getRequestId(), entry);
            return 1;
        });
        when(ledger.selectOne(any())).thenAnswer(call -> {
            LambdaQueryWrapper<BillingLedgerEntryEntity> query = call.getArgument(0);
            query.getSqlSegment();
            return entries.get(query.getParamNameValuePairs().values().iterator().next());
        });
        BillingService realBilling = new BillingService(accounts, ledger, mock(GuardrailAuditLogMapper.class), redis,
                new TransactionTemplate(transactions), new RedisRateLimiterService(redis, 20, 120_000),
                CircuitBreaker.ofDefaults("ripple-test"), Bulkhead.ofDefaults("ripple-test"), 0, 5_000_000L);
        realBilling.setVipEntitlementService(vip);
        when(vip.resolveForUser(ownerId)).thenReturn(entitlements("STUDIO", 8000));

        assertThat(realBilling.freezeCustom(ownerId, "zero", 0L, "fast-router", "freeze")).isZero();
        assertThat(realBilling.freezeCustom(ownerId, "freeze-1", 200_000L, "fast-router", "freeze")).isEqualTo(200_000L);
        assertThat(realBilling.freezeCustom(ownerId, "freeze-1", 100_000L, "fast-router", "freeze")).isEqualTo(200_000L);
        assertThat(account.getBalanceMicros()).isEqualTo(800_000L);
        assertThat(account.getFrozenMicros()).isEqualTo(200_000L);

        assertThat(realBilling.settleCustom(ownerId, "settle-1", 200_000L, "fast-router",
                2_000, 1_000, 500, "settle")).isEqualTo(2_080L);
        assertThat(realBilling.settleCustom(ownerId, "settle-1", 200_000L, "fast-router",
                0, 0, 0, "replay")).isEqualTo(2_080L);
        assertThat(account.getBalanceMicros()).isEqualTo(997_920L);
        assertThat(account.getMonthlySpentMicros()).isEqualTo(2_080L);
        assertThat(account.getFrozenMicros()).isZero();

        realBilling.freezeCustom(ownerId, "freeze-2", 120_000L, "fast-router", "freeze");
        assertThat(realBilling.settleCustom(ownerId, "settle-2", 120_000L, "fast-router",
                0, 0, 0, "release")).isZero();
        assertThat(realBilling.settleCustom(ownerId, "settle-2", 120_000L, "fast-router",
                10, 0, 0, "late result")).isZero();
        assertThat(account.getBalanceMicros()).isEqualTo(997_920L);
        assertThat(account.getFrozenMicros()).isZero();
        assertThat(entries).hasSize(4);
        assertThat(entries.get("freeze-1").getEntryType()).isEqualTo("FREEZE");
        assertThat(entries.get("settle-1").getEntryType()).isEqualTo("SETTLE");
        assertThat(entries.get("settle-2").getEntryType()).isEqualTo("RELEASE");

        account.setMonthlyBudgetMicros(100_000L);
        assertThatThrownBy(() -> realBilling.freezeCustom(ownerId, "over-budget", 100_000L, "fast-router", "freeze"))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo("MONTHLY_BUDGET_EXCEEDED"));
        account.setMonthlyBudgetMicros(5_000_000L);
        assertThatThrownBy(() -> realBilling.freezeCustom(ownerId, "over-balance", 1_000_000L, "fast-router", "freeze"))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo("INSUFFICIENT_BALANCE"));
    }

    @Test
    void internalControllerUsesWorkerFieldNamesBehindTheTokenFilter() throws Exception {
        RippleJobEntity job = job("ANALYZING");
        var mvc = MockMvcBuilders.standaloneSetup(new InternalRippleController(service))
                .addFilters(new InternalTokenFilter(PathPatternRequestMatcher.pathPattern("/internal/v1/ripple/**"),
                        "ripple-test-token"))
                .build();
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/internal/v1/ripple/jobs/{jobId}/context", job.getId());

        assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(403);
        var response = mvc.perform(request.header("X-Internal-Token", "ripple-test-token")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var payload = json.readTree(response.getContentAsString());
        assertThat(payload.get("sourceSectionId").asString()).isEqualTo(source.getId().toString());
        assertThat(payload.get("sourceNarrativeOrder").asLong()).isEqualTo(source.getNarrativeOrder());
        assertThat(payload.get("rangeEndOrder").asLong()).isEqualTo(job.getRangeEndOrder());
    }

    private RippleJobEntity start(String mode, Integer from, Integer to, List<UUID> volumeIds) {
        service.startAnalyze(ownerId, bookId, source.getId(),
                new StartRippleAnalyzeRequest(mode, from, to, volumeIds, null, null, null, null));
        return createdJobs.getLast();
    }

    private RippleJobEntity job(String status) {
        RippleJobEntity job = new RippleJobEntity();
        job.setId(UUID.randomUUID());
        job.setOwnerId(ownerId);
        job.setBookId(bookId);
        job.setSourceSectionId(source.getId());
        job.setSourceChapterNo(source.getChapterNo());
        job.setSourceNarrativeOrder(source.getNarrativeOrder());
        job.setRangeStartChapter(75);
        job.setRangeEndChapter(124);
        job.setRangeStartOrder(75_001L);
        job.setRangeEndOrder(124_999L);
        job.setStatus(status);
        when(jobs.selectById(job.getId())).thenReturn(job);
        when(jobs.selectOne(any())).thenReturn(job);
        return job;
    }

    private ChapterSectionEntity section(int chapterNo, int sectionNo, String content, long version) {
        ChapterSectionEntity row = new ChapterSectionEntity();
        row.setId(UUID.randomUUID());
        row.setBookId(bookId);
        row.setChapterId(UUID.randomUUID());
        row.setChapterNo(chapterNo);
        row.setSectionNo(sectionNo);
        row.setNarrativeOrder(StructureService.narrativeOrder(chapterNo, sectionNo));
        row.setContent(content);
        row.setWordCount(WordCounter.count(content));
        row.setVersion(version);
        row.setIsDefault(sectionNo == 1);
        when(sections.selectById(row.getId())).thenReturn(row);
        return row;
    }

    private static ChapterEntity chapter(ChapterSectionEntity section, String title) {
        ChapterEntity chapter = new ChapterEntity();
        chapter.setId(section.getChapterId());
        chapter.setBookId(section.getBookId());
        chapter.setChapterNo(section.getChapterNo());
        chapter.setContent(section.getContent());
        chapter.setTitle(title);
        chapter.setStructureMode(StructureService.SINGLE);
        chapter.setVersion(2L);
        return chapter;
    }

    private static ChapterMapper.VolumeChapterRange volume(UUID id, int from, int to) {
        var range = new ChapterMapper.VolumeChapterRange();
        range.setVolumeId(id);
        range.setStartChapterNo(from);
        range.setEndChapterNo(to);
        return range;
    }

    private static ResolvedUserEntitlements entitlements(String plan, int discountBps) {
        return new ResolvedUserEntitlements(plan, plan, "ACTIVE", null, null, discountBps, 0L,
                VipEntitlementService.defaultEntitlements(plan));
    }

    private static InternalImpactInput reported(ChapterSectionEntity target, String direction, String severity) {
        return new InternalImpactInput(target.getId(), 0, 0, 0L, "不可信标题", direction, severity, "冲突", "沈舟");
    }

    private static RippleImpactEntity impact(RippleJobEntity job, ChapterSectionEntity target) {
        RippleImpactEntity impact = new RippleImpactEntity();
        impact.setId(UUID.randomUUID());
        impact.setJobId(job.getId());
        impact.setTargetSectionId(target.getId());
        impact.setChapterNo(target.getChapterNo());
        impact.setSectionNo(target.getSectionNo());
        impact.setNarrativeOrder(target.getNarrativeOrder());
        impact.setDirection(target.getNarrativeOrder() < job.getSourceNarrativeOrder() ? "BACKWARD" : "FORWARD");
        impact.setSeverity("STRONG_CONFLICT");
        impact.setPatchStatus("READY_FOR_REVIEW");
        return impact;
    }

    private RipplePatchEntity patch(RippleJobEntity job, RippleImpactEntity impact, long version, PatchHunkItem... hunks) {
        RipplePatchEntity patch = new RipplePatchEntity();
        patch.setId(UUID.randomUUID());
        patch.setJobId(job.getId());
        patch.setImpactId(impact.getId());
        patch.setTargetSectionId(impact.getTargetSectionId());
        patch.setBaseVersion(version);
        patch.setStatus("READY_FOR_REVIEW");
        patch.setHunksJson(json.writeValueAsString(List.of(hunks)));
        return patch;
    }

    private static InternalPatchBatchResultRequest patchResult(RipplePatchEntity patch, String original) {
        return new InternalPatchBatchResultRequest(List.of(new InternalPatchResultItem(patch.getId(), "READY_FOR_REVIEW",
                List.of(new PatchHunkItem(original, "沈舟成功逃走。", "统一人物状态")))), 1_000, 500, 250, null);
    }

    private static void assertQuota(Runnable action, String feature, int limit) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(ex.getCode()).isEqualTo("VIP_QUOTA_EXCEEDED");
            assertThat(ex.getDetails()).containsEntry("feature", feature).containsEntry("currentLimit", String.valueOf(limit));
        });
    }

    private static void assertRange(RippleJobEntity job, int from, int to, long startOrder, long endOrder) {
        assertThat(job.getRangeStartChapter()).isEqualTo(from);
        assertThat(job.getRangeEndChapter()).isEqualTo(to);
        assertThat(job.getRangeStartOrder()).isEqualTo(startOrder);
        assertThat(job.getRangeEndOrder()).isEqualTo(endOrder);
    }
}
