package studio.foliarc.biz.ripple.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import studio.foliarc.biz.billing.ModelPricing;
import studio.foliarc.biz.billing.service.BillingService;
import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.lore.LoreNames;
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
import studio.foliarc.biz.ripple.dto.RippleDtos.ApplyPatchesRequest;
import studio.foliarc.biz.ripple.dto.RippleDtos.ApplyRipplePatchesResponse;
import studio.foliarc.biz.ripple.dto.RippleDtos.FactDeltaItem;
import studio.foliarc.biz.ripple.dto.RippleDtos.GeneratePatchesRequest;
import studio.foliarc.biz.ripple.dto.RippleDtos.InternalAnalysisResultRequest;
import studio.foliarc.biz.ripple.dto.RippleDtos.InternalCandidateSearchRequest;
import studio.foliarc.biz.ripple.dto.RippleDtos.InternalCandidateSearchResponse;
import studio.foliarc.biz.ripple.dto.RippleDtos.InternalCandidateSection;
import studio.foliarc.biz.ripple.dto.RippleDtos.InternalImpactInput;
import studio.foliarc.biz.ripple.dto.RippleDtos.InternalPatchBatchContext;
import studio.foliarc.biz.ripple.dto.RippleDtos.InternalPatchBatchResultRequest;
import studio.foliarc.biz.ripple.dto.RippleDtos.InternalPatchResultItem;
import studio.foliarc.biz.ripple.dto.RippleDtos.InternalPatchTarget;
import studio.foliarc.biz.ripple.dto.RippleDtos.InternalRippleJobContext;
import studio.foliarc.biz.ripple.dto.RippleDtos.PatchHunkItem;
import studio.foliarc.biz.ripple.dto.RippleDtos.RippleImpactResponse;
import studio.foliarc.biz.ripple.dto.RippleDtos.RippleJobDetailResponse;
import studio.foliarc.biz.ripple.dto.RippleDtos.RippleJobResponse;
import studio.foliarc.biz.ripple.dto.RippleDtos.RipplePatchResponse;
import studio.foliarc.biz.ripple.dto.RippleDtos.StartRippleAnalyzeRequest;
import studio.foliarc.biz.ripple.entity.RippleImpactEntity;
import studio.foliarc.biz.ripple.entity.RippleJobEntity;
import studio.foliarc.biz.ripple.entity.RipplePatchEntity;
import studio.foliarc.biz.ripple.mapper.RippleCandidateMapper;
import studio.foliarc.biz.ripple.mapper.RippleImpactMapper;
import studio.foliarc.biz.ripple.mapper.RippleJobMapper;
import studio.foliarc.biz.ripple.mapper.RipplePatchMapper;
import studio.foliarc.biz.style.service.StyleAnchorService;
import studio.foliarc.biz.vip.dto.VipDtos.ResolvedUserEntitlements;
import studio.foliarc.biz.vip.dto.VipDtos.VipEntitlements;
import studio.foliarc.biz.vip.service.VipEntitlementService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class RippleEditService {

    private static final Logger log = LoggerFactory.getLogger(RippleEditService.class);
    private static final String ANALYZING = "ANALYZING";
    private static final String NO_CHANGE = "NO_CHANGE";
    private static final String READY_TO_PATCH = "READY_TO_PATCH";
    private static final String BLAST_RADIUS_EXCEEDED = "BLAST_RADIUS_EXCEEDED";
    private static final String PATCHING = "PATCHING";
    private static final String READY_FOR_REVIEW = "READY_FOR_REVIEW";
    private static final String PARTIALLY_APPLIED = "PARTIALLY_APPLIED";
    private static final String APPLIED = "APPLIED";
    private static final String FAILED = "FAILED";
    private static final String CANCELLED = "CANCELLED";

    private static final String BACKWARD = "BACKWARD";
    private static final String FORWARD = "FORWARD";
    private static final String STRONG_CONFLICT = "STRONG_CONFLICT";
    private static final String MINOR_MENTION = "MINOR_MENTION";
    private static final Set<String> SEVERITIES = Set.of(STRONG_CONFLICT, MINOR_MENTION, "UNAFFECTED");

    private static final String UNSELECTED = "UNSELECTED";
    private static final String QUEUED = "QUEUED";
    private static final String SKIPPED = "SKIPPED";
    private static final String PENDING = "PENDING";
    private static final String ACCEPTED = "ACCEPTED";
    private static final String REJECTED = "REJECTED";
    private static final String VERSION_CONFLICT = "VERSION_CONFLICT";
    private static final String MANUAL_REVIEW_REQUIRED = "MANUAL_REVIEW_REQUIRED";

    private static final Set<String> GENERATABLE = Set.of(
            READY_TO_PATCH, BLAST_RADIUS_EXCEEDED, READY_FOR_REVIEW, PARTIALLY_APPLIED);
    private static final Set<String> APPLICABLE = Set.of(READY_FOR_REVIEW, PARTIALLY_APPLIED, APPLIED);
    private static final Set<String> REVIEWABLE = Set.of(
            READY_FOR_REVIEW, ACCEPTED, VERSION_CONFLICT, MANUAL_REVIEW_REQUIRED);

    private static final long FREEZE_PER_PATCH_MICROS = 200_000L;
    private static final String PATCH_MODEL = "fast-router";
    private static final TypeReference<List<FactDeltaItem>> FACT_DELTAS = new TypeReference<>() {};
    private static final TypeReference<List<PatchHunkItem>> PATCH_HUNKS = new TypeReference<>() {};
    private static final ResolvedUserEntitlements FREE = new ResolvedUserEntitlements(
            "FREE", "免费版", "FREE_DEFAULT", null, null, 10_000, 20_000_000L,
            VipEntitlementService.defaultEntitlements("FREE"));

    private final RippleJobMapper jobMapper;
    private final RippleImpactMapper impactMapper;
    private final RipplePatchMapper patchMapper;
    private final RippleCandidateMapper candidateMapper;
    private final BookService bookService;
    private final ChapterMapper chapterMapper;
    private final ChapterSectionMapper sectionMapper;
    private final SectionVersionMapper sectionVersionMapper;
    private final ChapterVersionMapper chapterVersionMapper;
    private final CharacterMapper characterMapper;
    private final VipEntitlementService vipEntitlementService;
    private final BillingService billingService;
    private final OutboxService outboxService;
    private final StyleAnchorService styleAnchorService;
    private final StoryMemoryContextService storyMemoryContextService;
    private final KnowledgeDocumentService knowledgeDocumentService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate refreshTransaction;

    public RippleEditService(
            RippleJobMapper jobMapper, RippleImpactMapper impactMapper, RipplePatchMapper patchMapper,
            RippleCandidateMapper candidateMapper, BookService bookService, ChapterMapper chapterMapper,
            ChapterSectionMapper sectionMapper, SectionVersionMapper sectionVersionMapper,
            ChapterVersionMapper chapterVersionMapper, CharacterMapper characterMapper,
            @Nullable VipEntitlementService vipEntitlementService, BillingService billingService,
            OutboxService outboxService, @Nullable StyleAnchorService styleAnchorService,
            @Nullable StoryMemoryContextService storyMemoryContextService,
            @Nullable KnowledgeDocumentService knowledgeDocumentService,
            ObjectMapper objectMapper, PlatformTransactionManager transactionManager
    ) {
        this.jobMapper = jobMapper;
        this.impactMapper = impactMapper;
        this.patchMapper = patchMapper;
        this.candidateMapper = candidateMapper;
        this.bookService = bookService;
        this.chapterMapper = chapterMapper;
        this.sectionMapper = sectionMapper;
        this.sectionVersionMapper = sectionVersionMapper;
        this.chapterVersionMapper = chapterVersionMapper;
        this.characterMapper = characterMapper;
        this.vipEntitlementService = vipEntitlementService;
        this.billingService = billingService;
        this.outboxService = outboxService;
        this.styleAnchorService = styleAnchorService;
        this.storyMemoryContextService = storyMemoryContextService;
        this.knowledgeDocumentService = knowledgeDocumentService;
        this.objectMapper = objectMapper;
        refreshTransaction = new TransactionTemplate(transactionManager);
        refreshTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public RippleJobResponse startAnalyze(UUID ownerId, UUID bookId, UUID sectionId, StartRippleAnalyzeRequest request) {
        bookService.requireOwnedBook(ownerId, bookId);
        ChapterSectionEntity source = requireSection(bookId, sectionId);
        ResolvedUserEntitlements resolved = resolveEntitlements(ownerId);
        enforceDailyQuota(ownerId, resolved);

        ScanRange range = resolveRange(bookId, source, resolved, request);
        RippleJobEntity job = new RippleJobEntity();
        job.setId(UUID.randomUUID());
        job.setBookId(bookId);
        job.setOwnerId(ownerId);
        job.setSourceSectionId(sectionId);
        job.setSourceChapterNo(source.getChapterNo());
        job.setSourceNarrativeOrder(source.getNarrativeOrder());
        job.setVipPlanCode(resolved.planCode());
        job.setRangeMode(range.mode().name());
        job.setRangeStartChapter(range.startChapter());
        job.setRangeEndChapter(range.endChapter());
        job.setRangeStartOrder(range.startOrder());
        job.setRangeEndOrder(range.endOrder());
        job.setSelectedVolumeIds(objectMapper.writeValueAsString(range.volumeIds()));
        job.setMaxCandidatesCap(resolved.entitlements().rippleMaxCandidates());
        job.setMaxPatchBatchLimit(resolved.entitlements().rippleMaxPatchChapters());
        job.setOldContentSnapshot(resolveOldContent(source, request));
        job.setNewContentSnapshot(request.newContent() == null ? text(source.getContent()) : request.newContent());
        job.setChangeNote(text(request.changeNote()));
        jobMapper.insert(job);

        String key = "RIPPLE_ANALYZE:" + job.getId();
        enqueue(job, "RIPPLE_ANALYZE", RabbitMqTopologyConfig.RK_RIPPLE_ANALYZE, key,
                Map.of("source_section_id", sectionId.toString()));
        if (vipEntitlementService != null) {
            afterCommit(() -> vipEntitlementService.incrementRippleScanToday(ownerId));
        }
        return toJobResponse(job);
    }

    private void enforceDailyQuota(UUID ownerId, ResolvedUserEntitlements resolved) {
        int limit = resolved.entitlements().rippleDailyScanLimit();
        if (limit <= 0) {
            return;
        }

        // Serialize quota checks across the author's books. Count committed jobs even if Redis lags.
        jobMapper.lockOwnerForScanQuota(ownerId);
        OffsetDateTime startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
        long persisted = jobMapper.selectCount(new LambdaQueryWrapper<RippleJobEntity>()
                .eq(RippleJobEntity::getOwnerId, ownerId)
                .ge(RippleJobEntity::getCreatedAt, startOfDay));
        long used = Math.max(persisted, vipEntitlementService == null ? 0
                : vipEntitlementService.getRippleScansUsedToday(ownerId));
        if (used >= limit) {
            throw quotaExceeded(resolved, "RIPPLE_DAILY_SCANS", limit, used + 1,
                    "当前套餐每日最多扫描 " + limit + " 次，今日次数已用完");
        }
    }

    private ScanRange resolveRange(UUID bookId, ChapterSectionEntity source, ResolvedUserEntitlements resolved,
                                   StartRippleAnalyzeRequest request) {
        String requestedMode = normalized(request.rangeMode());
        RangeMode mode;
        try {
            mode = requestedMode.isEmpty() ? RangeMode.BIDIRECTIONAL : RangeMode.valueOf(requestedMode);
        } catch (IllegalArgumentException ex) {
            throw BizException.badRequest("INVALID_RIPPLE_RANGE", "不支持的扫描范围模式");
        }

        VipEntitlements entitlements = resolved.entitlements();
        int sourceChapter = source.getChapterNo();
        int maxChapter = Math.max(sourceChapter, chapterMapper.findMaxChapterNo(bookId));
        int maxRange = entitlements.rippleMaxRangeChapters();
        if (mode == RangeMode.FULL_BOOK) {
            if (!entitlements.rippleAllowFullBook()) {
                throw quotaExceeded(resolved, "RIPPLE_RANGE", maxRange, maxChapter, "当前套餐未开放全书扫描");
            }
            return chapterRange(mode, 1, maxChapter, List.of());
        }

        List<UUID> volumes = mode == RangeMode.CUSTOM_RANGE
                ? List.copyOf(requiredIds(request.volumeIds())) : List.of();
        int start;
        int end;
        switch (mode) {
            case FORWARD -> {
                start = sourceChapter;
                long requestedEnd = request.rangeEndChapter() == null
                        ? sourceChapter + (long) maxRange - 1 : request.rangeEndChapter();
                end = (int) Math.min(maxChapter, requestedEnd);
            }
            case BACKWARD -> {
                long requestedStart = request.rangeStartChapter() == null
                        ? sourceChapter - (long) maxRange + 1 : request.rangeStartChapter();
                start = (int) Math.max(1, requestedStart);
                end = sourceChapter;
            }
            case BIDIRECTIONAL -> {
                long requestedStart = request.rangeStartChapter() == null
                        ? sourceChapter - maxRange / 2L : request.rangeStartChapter();
                long requestedEnd = request.rangeEndChapter() == null
                        ? sourceChapter + maxRange / 2L : request.rangeEndChapter();
                start = (int) Math.max(1, requestedStart);
                end = (int) Math.min(maxChapter, requestedEnd);
                if (start > sourceChapter || end < sourceChapter) {
                    throw BizException.badRequest("INVALID_RIPPLE_RANGE", "双向扫描范围必须包含源章节");
                }
                if (maxRange > 0 && (long) end - start + 1 > maxRange) {
                    long left = Math.min(sourceChapter - start, maxRange / 2L);
                    long right = Math.min(end - sourceChapter, maxRange - 1L - left);
                    start = (int) Math.max(start, sourceChapter - (maxRange - 1L - right));
                    end = (int) (sourceChapter + right);
                }
            }
            case CUSTOM_RANGE -> {
                if (volumes.isEmpty()) {
                    if (request.rangeStartChapter() == null || request.rangeEndChapter() == null) {
                        throw BizException.badRequest("INVALID_RIPPLE_RANGE", "请选择起止章节或至少一卷");
                    }
                    start = request.rangeStartChapter();
                    end = request.rangeEndChapter();
                } else {
                    Map<UUID, ChapterMapper.VolumeChapterRange> ranges = indexBy(
                            chapterMapper.findVolumeChapterRanges(bookId), ChapterMapper.VolumeChapterRange::getVolumeId);
                    start = maxChapter;
                    end = 0;
                    for (UUID volumeId : volumes) {
                        ChapterMapper.VolumeChapterRange selected = ranges.get(volumeId);
                        if (selected == null) {
                            throw BizException.badRequest("INVALID_RIPPLE_RANGE", "所选卷不属于本书或没有章节");
                        }
                        start = Math.min(start, selected.getStartChapterNo());
                        end = Math.max(end, selected.getEndChapterNo());
                    }
                }
            }
            default -> throw new IllegalStateException("Unhandled ripple range " + mode);
        }

        if (start < 1 || start > end) {
            throw BizException.badRequest("INVALID_RIPPLE_RANGE", "扫描范围的起止章节无效");
        }
        long span = (long) end - start + 1;
        if (span > maxRange) {
            throw quotaExceeded(resolved, "RIPPLE_RANGE", maxRange, span,
                    "当前套餐单次最多扫描 " + maxRange + " 章，本次选择了 " + span + " 章");
        }
        if (end > maxChapter) {
            throw BizException.badRequest("INVALID_RIPPLE_RANGE", "扫描范围超出本书现有章节");
        }

        long startOrder = mode == RangeMode.FORWARD ? source.getNarrativeOrder() + 1
                : StructureService.narrativeOrder(start, 1);
        long endOrder = mode == RangeMode.BACKWARD ? Math.max(1L, source.getNarrativeOrder() - 1)
                : StructureService.narrativeOrder(end, 999);
        return new ScanRange(mode, start, end, startOrder, endOrder, volumes);
    }

    private static ScanRange chapterRange(RangeMode mode, int start, int end, List<UUID> volumeIds) {
        return new ScanRange(mode, start, end, StructureService.narrativeOrder(start, 1),
                StructureService.narrativeOrder(end, 999), volumeIds);
    }

    private String resolveOldContent(ChapterSectionEntity source, StartRippleAnalyzeRequest request) {
        if (request.oldContent() != null) {
            return request.oldContent();
        }
        if (request.oldVersionId() != null) {
            SectionVersionEntity snapshot = sectionVersionMapper.selectById(request.oldVersionId());
            if (snapshot != null && source.getId().equals(snapshot.getSectionId())) {
                return text(snapshot.getContent());
            }
            if (snapshot == null && Boolean.TRUE.equals(source.getIsDefault())) {
                ChapterVersionEntity legacy = chapterVersionMapper.selectById(request.oldVersionId());
                if (legacy != null && source.getChapterId().equals(legacy.getChapterId())) {
                    return text(legacy.getContent());
                }
            }
            throw BizException.notFound("RIPPLE_VERSION_NOT_FOUND", "找不到源小节的历史快照");
        }
        SectionVersionEntity latest = sectionVersionMapper.selectOne(new LambdaQueryWrapper<SectionVersionEntity>()
                .eq(SectionVersionEntity::getSectionId, source.getId())
                .orderByDesc(SectionVersionEntity::getVersionNo)
                .last("LIMIT 1"));
        return latest == null ? "" : text(latest.getContent());
    }

    public List<RippleJobResponse> listJobs(UUID ownerId, UUID bookId) {
        bookService.requireOwnedBook(ownerId, bookId);
        return jobMapper.selectList(new LambdaQueryWrapper<RippleJobEntity>()
                        .eq(RippleJobEntity::getBookId, bookId)
                        .eq(RippleJobEntity::getOwnerId, ownerId)
                        .orderByDesc(RippleJobEntity::getCreatedAt, RippleJobEntity::getId)
                        .last("LIMIT 20"))
                .stream().map(RippleEditService::toJobResponse).toList();
    }

    @Transactional(readOnly = true)
    public RippleJobDetailResponse getJobDetail(UUID ownerId, UUID bookId, UUID jobId) {
        bookService.requireOwnedBook(ownerId, bookId);
        return detail(requireJob(jobId, bookId, ownerId, false));
    }

    public InternalRippleJobContext getJobContext(UUID jobId) {
        RippleJobEntity job = requireJob(jobId);
        requireStatus(job, Set.of(ANALYZING));
        BookEntity book = bookService.requireOwnedBook(job.getOwnerId(), job.getBookId());
        int sourceSectionNo = (int) (job.getSourceNarrativeOrder()
                - StructureService.narrativeOrder(job.getSourceChapterNo(), 0));
        return new InternalRippleJobContext(jobId, job.getBookId(), job.getOwnerId(), book.getTitle(),
                book.getGenre(), book.getGenreCode(), text(book.getSubGenreTags()), job.getSourceSectionId(),
                job.getSourceChapterNo(), sourceSectionNo, job.getSourceNarrativeOrder(), job.getRangeMode(),
                job.getRangeStartChapter(), job.getRangeEndChapter(), job.getRangeStartOrder(), job.getRangeEndOrder(),
                job.getMaxCandidatesCap(), job.getMaxPatchBatchLimit(), job.getOldContentSnapshot(),
                job.getNewContentSnapshot(), job.getChangeNote(), styleAnchors(job.getBookId()));
    }

    @Transactional(readOnly = true)
    public InternalCandidateSearchResponse searchCandidates(UUID jobId, InternalCandidateSearchRequest request) {
        RippleJobEntity job = requireJob(jobId);
        requireStatus(job, Set.of(ANALYZING));
        int requestedLimit = request.limit() == null ? job.getMaxCandidatesCap() : request.limit();
        int limit = Math.min(requestedLimit, job.getMaxCandidatesCap());
        int scanned = Math.toIntExact(sectionMapper.selectCount(sectionsInRange(job)));
        if (scanned == 0 || limit <= 0) {
            return new InternalCandidateSearchResponse(scanned, List.of());
        }
        List<String> terms = expandedTerms(job.getBookId(), request);
        List<InternalCandidateSection> matches = terms.isEmpty() ? List.of() : candidateMapper.findCandidates(job, terms, limit);
        return new InternalCandidateSearchResponse(scanned, matches);
    }

    private List<String> expandedTerms(UUID bookId, InternalCandidateSearchRequest request) {
        Set<String> entities = new LinkedHashSet<>();
        list(request.entities()).forEach(name -> addTerm(entities, name));
        Set<String> terms = new LinkedHashSet<>(entities);
        if (!entities.isEmpty()) {
            for (CharacterEntity character : characterMapper.selectList(new LambdaQueryWrapper<CharacterEntity>()
                    .eq(CharacterEntity::getBookId, bookId)
                    .select(CharacterEntity::getName, CharacterEntity::getAliases))) {
                Set<String> names = new LinkedHashSet<>();
                addTerm(names, character.getName());
                LoreNames.split(character.getAliases()).forEach(alias -> addTerm(names, alias));
                if (names.stream().anyMatch(entities::contains)) {
                    terms.addAll(names);
                }
            }
        }
        list(request.keywords()).forEach(keyword -> addTerm(terms, keyword));
        return List.copyOf(terms);
    }

    private static void addTerm(Set<String> terms, String term) {
        if (term != null && !term.isBlank()) {
            terms.add(term.strip().toLowerCase(Locale.ROOT));
        }
    }

    @Transactional
    public RippleJobResponse saveAnalysisResult(UUID jobId, InternalAnalysisResultRequest request) {
        RippleJobEntity job = requireJob(jobId, null, null, true);
        if (!ANALYZING.equals(job.getStatus())) {
            return toJobResponse(job);
        }

        job.setFactDeltasJson(objectMapper.writeValueAsString(list(request.factDeltas())));
        job.setChangeSummary(text(request.changeSummary()));
        job.setScannedSectionCount(nonNegative(request.scannedSectionCount()));
        job.setCandidateSectionCount(nonNegative(request.candidateSectionCount()));
        job.setActualCostMicros(Math.max(0L, Objects.requireNonNullElse(request.actualCostMicros(), 0L)));
        job.setErrorMessage(request.errorMessage());

        List<RippleImpactEntity> impacts = verifiedImpacts(job, list(request.impacts()));
        impactMapper.delete(jobImpacts(jobId));
        int backward = 0;
        int forward = 0;
        int minor = 0;
        for (RippleImpactEntity impact : impacts) {
            impactMapper.insert(impact);
            if (MINOR_MENTION.equals(impact.getSeverity())) {
                minor++;
            } else if (STRONG_CONFLICT.equals(impact.getSeverity())) {
                if (BACKWARD.equals(impact.getDirection())) {
                    backward++;
                } else {
                    forward++;
                }
            }
        }

        job.setBackwardConflictCount(backward);
        job.setForwardConflictCount(forward);
        job.setMinorMentionCount(minor);
        int strong = backward + forward;
        long estimate = strong * 150_000L + minor * 80_000L;
        int discountBps = resolveEntitlements(job.getOwnerId()).creditDiscountBps();
        job.setEstimatedCostMicros(discountBps > 0 && discountBps < 10_000
                ? Math.round(estimate * (discountBps / 10_000.0)) : estimate);

        String status;
        if (FAILED.equals(normalized(request.status()))) {
            status = FAILED;
        } else if (NO_CHANGE.equals(normalized(request.status())) || strong + minor == 0) {
            status = NO_CHANGE;
        } else {
            status = strong > job.getMaxPatchBatchLimit() ? BLAST_RADIUS_EXCEEDED : READY_TO_PATCH;
        }
        saveJob(job, status);
        return toJobResponse(job);
    }

    private List<RippleImpactEntity> verifiedImpacts(RippleJobEntity job, List<InternalImpactInput> reported) {
        Set<UUID> targetIds = reported.stream().filter(Objects::nonNull)
                .map(InternalImpactInput::targetSectionId)
                .filter(id -> id != null && !id.equals(job.getSourceSectionId()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (targetIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, ChapterSectionEntity> targets = indexBy(sectionMapper.selectList(sectionsInRange(job)
                .in(ChapterSectionEntity::getId, targetIds)), ChapterSectionEntity::getId);
        Set<UUID> chapterIds = targets.values().stream().map(ChapterSectionEntity::getChapterId)
                .collect(Collectors.toSet());
        Map<UUID, ChapterEntity> chapters = chapterIds.isEmpty() ? Map.of()
                : indexBy(chapterMapper.selectByIds(chapterIds), ChapterEntity::getId);
        Map<UUID, RippleImpactEntity> impacts = new LinkedHashMap<>();
        for (InternalImpactInput item : reported) {
            if (item == null) {
                continue;
            }
            ChapterSectionEntity section = targets.get(item.targetSectionId());
            String severity = normalized(item.severity());
            if (section == null || section.getNarrativeOrder() == job.getSourceNarrativeOrder()
                    || !SEVERITIES.contains(severity)) {
                continue;
            }
            String direction = section.getNarrativeOrder() < job.getSourceNarrativeOrder() ? BACKWARD : FORWARD;
            if (!direction.equals(normalized(item.direction())) || impacts.containsKey(section.getId())) {
                continue;
            }
            ChapterEntity chapter = chapters.get(section.getChapterId());
            RippleImpactEntity impact = new RippleImpactEntity();
            impact.setId(UUID.randomUUID());
            impact.setJobId(job.getId());
            impact.setTargetSectionId(section.getId());
            impact.setChapterNo(section.getChapterNo());
            impact.setSectionNo(section.getSectionNo());
            impact.setNarrativeOrder(section.getNarrativeOrder());
            impact.setChapterTitle(chapter == null ? "" : text(chapter.getTitle()));
            impact.setDirection(direction);
            impact.setSeverity(severity);
            impact.setConflictReason(text(item.conflictReason()));
            impact.setMatchedExcerpt(text(item.matchedExcerpt()));
            impacts.put(section.getId(), impact);
        }
        return List.copyOf(impacts.values());
    }

    @Transactional
    public RippleJobDetailResponse generatePatches(UUID ownerId, UUID bookId, UUID jobId, GeneratePatchesRequest request) {
        bookService.requireOwnedBook(ownerId, bookId);
        RippleJobEntity job = requireJob(jobId, bookId, ownerId, true);
        requireStatus(job, GENERATABLE);
        List<UUID> requested = list(request.impactIds());
        if (requested.isEmpty()) {
            throw BizException.badRequest("RIPPLE_IMPACTS_REQUIRED", "请选择要生成补丁的影响项");
        }
        if (requested.size() > job.getMaxPatchBatchLimit()) {
            throw quotaExceeded(resolveEntitlements(ownerId), "RIPPLE_PATCH_CHAPTERS",
                    job.getMaxPatchBatchLimit(), requested.size(),
                    "单批最多生成 " + job.getMaxPatchBatchLimit() + " 个补丁，请分批选择");
        }
        Set<UUID> impactIds = requiredIds(requested);
        Map<UUID, RippleImpactEntity> impacts = indexBy(impactMapper.selectByIds(impactIds), RippleImpactEntity::getId);
        List<RipplePatchEntity> patches = new ArrayList<>();
        int batchNo = job.getNextBatchNo();
        for (UUID impactId : impactIds) {
            RippleImpactEntity impact = requireImpact(jobId, impacts.get(impactId));
            if (APPLIED.equals(impact.getPatchStatus())) {
                throw BizException.conflict("RIPPLE_IMPACT_APPLIED", "已应用的影响项不能重新生成补丁");
            }
            ChapterSectionEntity target = requireSection(bookId, impact.getTargetSectionId());
            RipplePatchEntity patch = new RipplePatchEntity();
            patch.setId(UUID.randomUUID());
            patch.setJobId(jobId);
            patch.setImpactId(impactId);
            patch.setTargetSectionId(target.getId());
            patch.setBatchNo(batchNo);
            patch.setBaseVersion(target.getVersion());
            patches.add(patch);
        }

        String batchKey = jobId + ":b" + batchNo;
        long frozen = billingService.freezeCustom(ownerId, "RIPPLE_FREEZE:" + batchKey,
                patches.size() * FREEZE_PER_PATCH_MICROS, PATCH_MODEL, "蝴蝶效应补丁批次预冻结");

        // A regenerated impact has one reviewable patch. Keep superseded batches for history.
        patchMapper.update(null, new LambdaUpdateWrapper<RipplePatchEntity>()
                .eq(RipplePatchEntity::getJobId, jobId)
                .in(RipplePatchEntity::getImpactId, impactIds)
                .in(RipplePatchEntity::getStatus, REVIEWABLE)
                .set(RipplePatchEntity::getStatus, REJECTED)
                .set(RipplePatchEntity::getUpdatedAt, now()));
        for (RipplePatchEntity patch : patches) {
            patchMapper.insert(patch);
            updateImpact(impacts.get(patch.getImpactId()), QUEUED);
        }

        job.setFrozenCostMicros(frozen);
        job.setNextBatchNo(batchNo + 1);
        saveJob(job, PATCHING);
        enqueue(job, "RIPPLE_PATCH", RabbitMqTopologyConfig.RK_RIPPLE_PATCH, "RIPPLE_PATCH:" + batchKey,
                Map.of("batch_no", batchNo));
        return detail(job);
    }

    @Transactional(readOnly = true)
    public InternalPatchBatchContext getPatchBatchTargets(UUID jobId, int batchNo) {
        RippleJobEntity job = requireJob(jobId);
        BookEntity book = bookService.requireOwnedBook(job.getOwnerId(), job.getBookId());
        List<InternalPatchTarget> targets = new ArrayList<>();
        if (activeBatch(job, batchNo)) {
            List<RipplePatchEntity> patches = pendingPatches(jobId, batchNo);
            Map<UUID, ChapterSectionEntity> sections = sectionsForPatches(job.getBookId(), patches);
            Map<UUID, RippleImpactEntity> impacts = impactsForPatches(patches);
            for (RipplePatchEntity patch : patches) {
                ChapterSectionEntity section = sections.get(patch.getTargetSectionId());
                RippleImpactEntity impact = impacts.get(patch.getImpactId());
                if (section != null && impact != null) {
                    targets.add(new InternalPatchTarget(patch.getId(), impact.getId(), section.getId(),
                            section.getChapterNo(), section.getSectionNo(), impact.getChapterTitle(),
                            impact.getDirection(), impact.getConflictReason(), patch.getBaseVersion(),
                            text(section.getContent())));
                }
            }
        }
        return new InternalPatchBatchContext(jobId, job.getBookId(), job.getOwnerId(), batchNo,
                book.getGenreCode(), job.getChangeSummary(), styleAnchors(job.getBookId()),
                readList(job.getFactDeltasJson(), FACT_DELTAS), List.copyOf(targets));
    }

    @Transactional
    public RippleJobResponse savePatchBatchResult(UUID jobId, int batchNo, InternalPatchBatchResultRequest request) {
        RippleJobEntity job = requireJob(jobId, null, null, true);
        if (!activeBatch(job, batchNo)) {
            return toJobResponse(job);
        }
        Map<UUID, InternalPatchResultItem> results = new LinkedHashMap<>();
        for (InternalPatchResultItem item : list(request.patches())) {
            if (item != null && item.patchId() != null) {
                results.putIfAbsent(item.patchId(), item);
            }
        }
        List<RipplePatchEntity> pending = pendingPatches(jobId, batchNo);
        Map<UUID, ChapterSectionEntity> sections = sectionsForPatches(job.getBookId(), pending);
        Map<UUID, RippleImpactEntity> impacts = impactsForPatches(pending);
        for (RipplePatchEntity patch : pending) {
            InternalPatchResultItem result = results.get(patch.getId());
            ChapterSectionEntity section = sections.get(patch.getTargetSectionId());
            List<PatchHunkItem> hunks = result == null ? List.of() : list(result.hunks());
            boolean ready = result != null && section != null && hasAnchors(text(section.getContent()), hunks)
                    && !MANUAL_REVIEW_REQUIRED.equals(normalized(result.status()));
            patch.setHunksJson(objectMapper.writeValueAsString(hunks.stream().filter(Objects::nonNull).toList()));
            updatePatch(patch, ready ? READY_FOR_REVIEW : MANUAL_REVIEW_REQUIRED);
            updateImpact(requireImpact(jobId, impacts.get(patch.getImpactId())), READY_FOR_REVIEW);
        }

        long charged = settleBatch(job, batchNo, nonNegative(request.inputTokens()), nonNegative(request.cachedTokens()),
                nonNegative(request.outputTokens()), "蝴蝶效应补丁批次结算");
        job.setActualCostMicros(job.getActualCostMicros() + charged);
        job.setErrorMessage(request.errorMessage());
        saveJob(job, text(request.errorMessage()).isBlank() ? READY_FOR_REVIEW : FAILED);
        return toJobResponse(job);
    }

    @Transactional
    public ApplyRipplePatchesResponse applyPatches(UUID ownerId, UUID bookId, UUID jobId, ApplyPatchesRequest request) {
        bookService.requireOwnedBook(ownerId, bookId);
        bookService.lockStructure(bookId);
        RippleJobEntity job = requireJob(jobId, bookId, ownerId, true);
        requireStatus(job, APPLICABLE);

        Set<UUID> acceptedIds = requiredIds(request.patchIds());
        Set<UUID> rejectedIds = requiredIds(request.rejectedPatchIds());
        if (acceptedIds.stream().anyMatch(rejectedIds::contains)) {
            throw BizException.badRequest("RIPPLE_PATCH_DECISION_CONFLICT", "同一补丁不能同时采纳和跳过");
        }
        Set<UUID> patchIds = new LinkedHashSet<>(acceptedIds);
        patchIds.addAll(rejectedIds);
        if (patchIds.isEmpty()) {
            return new ApplyRipplePatchesResponse(job.getStatus(), 0, 0, 0, 0, List.of());
        }
        Map<UUID, RipplePatchEntity> patches = indexBy(patchMapper.selectByIds(patchIds), RipplePatchEntity::getId);
        for (UUID patchId : patchIds) {
            RipplePatchEntity patch = patches.get(patchId);
            if (patch == null || !jobId.equals(patch.getJobId())) {
                throw BizException.notFound("RIPPLE_PATCH_NOT_FOUND", "找不到该任务的补丁");
            }
            String decidedStatus = acceptedIds.contains(patchId) ? APPLIED : REJECTED;
            if (!decidedStatus.equals(patch.getStatus()) && !REVIEWABLE.contains(patch.getStatus())) {
                throw BizException.conflict("RIPPLE_PATCH_STATUS_CONFLICT", "当前补丁无法执行该操作");
            }
        }
        Map<UUID, RippleImpactEntity> impacts = impactsForPatches(patches.values());
        Map<UUID, ChapterEntity> lockedChapters = new LinkedHashMap<>();
        List<ChapterSectionEntity> applied = new ArrayList<>();
        int conflicts = 0;
        int manualReviews = 0;
        for (UUID patchId : acceptedIds) {
            RipplePatchEntity patch = patches.get(patchId);
            if (APPLIED.equals(patch.getStatus())) {
                continue;
            }
            RippleImpactEntity impact = requireImpact(jobId, impacts.get(patch.getImpactId()));
            ChapterSectionEntity changed = applyPatch(ownerId, bookId, patch, lockedChapters);
            if (changed != null) {
                applied.add(changed);
                updateImpact(impact, APPLIED);
            } else if (VERSION_CONFLICT.equals(patch.getStatus())) {
                conflicts++;
            } else {
                manualReviews++;
            }
        }
        int rejected = 0;
        for (UUID patchId : rejectedIds) {
            RipplePatchEntity patch = patches.get(patchId);
            if (!REJECTED.equals(patch.getStatus())) {
                updatePatch(patch, REJECTED);
                updateImpact(requireImpact(jobId, impacts.get(patch.getImpactId())), SKIPPED);
                rejected++;
            }
        }

        Set<UUID> chaptersToIndex = updateAppliedChapters(applied, lockedChapters);
        long remaining = impactMapper.selectCount(jobImpacts(jobId)
                .eq(RippleImpactEntity::getSeverity, STRONG_CONFLICT)
                .in(RippleImpactEntity::getPatchStatus, UNSELECTED, QUEUED, READY_FOR_REVIEW));
        saveJob(job, remaining == 0 ? APPLIED : PARTIALLY_APPLIED);
        if (!applied.isEmpty()) {
            afterCommit(() -> refreshAppliedContent(ownerId, bookId, applied, chaptersToIndex));
        }
        return new ApplyRipplePatchesResponse(job.getStatus(), applied.size(), conflicts, manualReviews, rejected,
                applied.stream().map(ChapterSectionEntity::getId).toList());
    }

    private ChapterSectionEntity applyPatch(UUID ownerId, UUID bookId, RipplePatchEntity patch,
                                           Map<UUID, ChapterEntity> lockedChapters) {
        if (VERSION_CONFLICT.equals(patch.getStatus())) {
            return null;
        }
        ChapterSectionEntity target = requireSection(bookId, patch.getTargetSectionId());
        // Ordinary section and legacy chapter edits acquire this same chapter lock.
        lockedChapters.computeIfAbsent(target.getChapterId(), chapterId -> lockChapter(bookId, chapterId));
        ChapterSectionEntity section = requireSection(bookId, patch.getTargetSectionId());
        if (!Objects.equals(patch.getBaseVersion(), section.getVersion())) {
            updatePatch(patch, VERSION_CONFLICT);
            return null;
        }
        if (MANUAL_REVIEW_REQUIRED.equals(patch.getStatus())) {
            return null;
        }
        String original = text(section.getContent());
        String updated = applyHunks(original, readList(patch.getHunksJson(), PATCH_HUNKS));
        if (updated == null) {
            updatePatch(patch, MANUAL_REVIEW_REQUIRED);
            return null;
        }

        section.setContent(updated);
        section.setWordCount(WordCounter.count(updated));
        section.setContentHash(StoryMemoryContextService.sha256Hex(updated));
        section.setSummaryDirty(true);
        section.setUpdatedAt(now());
        // The @Version interceptor checks the base version and writes baseVersion + 1.
        if (sectionMapper.updateById(section) == 0) {
            updatePatch(patch, VERSION_CONFLICT);
            return null;
        }

        SectionVersionEntity snapshot = new SectionVersionEntity();
        snapshot.setId(UUID.randomUUID());
        snapshot.setSectionId(section.getId());
        snapshot.setVersionNo(sectionVersionMapper.findMaxVersionNo(section.getId()) + 1);
        snapshot.setContent(original);
        snapshot.setChangeSummary("蝴蝶效应修补前自动快照");
        snapshot.setCreatedBy(ownerId);
        sectionVersionMapper.insert(snapshot);

        updatePatch(patch, APPLIED);
        return section;
    }

    private static boolean hasAnchors(String content, List<PatchHunkItem> hunks) {
        return !hunks.isEmpty() && hunks.stream().allMatch(hunk -> hunk != null && hunk.originalParagraph() != null
                && !hunk.originalParagraph().isBlank() && hunk.patchedParagraph() != null
                && content.contains(hunk.originalParagraph()));
    }

    private static String applyHunks(String content, List<PatchHunkItem> hunks) {
        if (!hasAnchors(content, hunks)) {
            return null;
        }
        StringBuilder updated = new StringBuilder(content);
        for (PatchHunkItem hunk : hunks) {
            int offset = updated.indexOf(hunk.originalParagraph());
            if (offset < 0) {
                return null;
            }
            updated.replace(offset, offset + hunk.originalParagraph().length(), hunk.patchedParagraph());
        }
        return updated.toString();
    }

    private ChapterEntity lockChapter(UUID bookId, UUID chapterId) {
        chapterMapper.lockForStructure(bookId, chapterId);
        ChapterEntity chapter = chapterMapper.selectById(chapterId);
        if (chapter == null || !bookId.equals(chapter.getBookId())) {
            throw BizException.notFound("CHAPTER_NOT_FOUND", "章节不存在");
        }
        return chapter;
    }

    private Set<UUID> updateAppliedChapters(List<ChapterSectionEntity> applied,
                                            Map<UUID, ChapterEntity> lockedChapters) {
        Set<UUID> updatedChapters = new LinkedHashSet<>();
        for (ChapterSectionEntity section : applied) {
            UUID chapterId = section.getChapterId();
            if (!updatedChapters.add(chapterId)) {
                continue;
            }
            ChapterEntity chapter = lockedChapters.get(chapterId);
            if (StructureService.MULTI_SECTION.equals(chapter.getStructureMode())) {
                List<ChapterSectionEntity> sections = sectionMapper.selectList(ChapterSectionMapper.inChapter(chapterId)
                        .select(ChapterSectionEntity::getWordCount));
                chapter.setWordCount(sections.stream().mapToInt(ChapterSectionEntity::getWordCount).sum());
            } else {
                chapter.setContent(section.getContent());
                chapter.setWordCount(section.getWordCount());
                chapter.setContentHash(section.getContentHash());
                chapter.setSummaryDirty(true);
            }
            chapter.setUpdatedAt(now());
            if (chapterMapper.updateById(chapter) == 0) {
                throw BizException.conflict("VERSION_CONFLICT", "章节版本已变更，请刷新后重试");
            }
        }
        return updatedChapters;
    }

    private void refreshAppliedContent(UUID ownerId, UUID bookId, List<ChapterSectionEntity> applied,
                                       Set<UUID> chaptersToIndex) {
        if (storyMemoryContextService != null) {
            for (ChapterSectionEntity section : applied) {
                refreshSafely(section.getId(), () -> {
                    chapterMapper.lockForStructure(bookId, section.getChapterId());
                    ChapterSectionEntity current = sectionMapper.selectById(section.getId());
                    if (current != null && Objects.equals(current.getContentHash(), section.getContentHash())) {
                        storyMemoryContextService.invalidateAndEnqueueSectionDistill(ownerId, bookId, current);
                    }
                });
            }
        }
        if (knowledgeDocumentService != null) {
            for (UUID chapterId : chaptersToIndex) {
                refreshSafely(chapterId, () -> refreshChapterIndex(ownerId, bookId, chapterId));
            }
        }
    }

    private void refreshChapterIndex(UUID ownerId, UUID bookId, UUID chapterId) {
        // Content can change again between the patch commit and this best-effort transaction.
        chapterMapper.lockForStructure(bookId, chapterId);
        ChapterEntity chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            return;
        }
        boolean hasContent;
        if (StructureService.MULTI_SECTION.equals(chapter.getStructureMode())) {
            hasContent = sectionMapper.selectList(ChapterSectionMapper.inChapter(chapterId)
                            .select(ChapterSectionEntity::getContent))
                    .stream().anyMatch(section -> !text(section.getContent()).isBlank());
        } else {
            hasContent = !text(chapter.getContent()).isBlank();
        }
        if (hasContent) {
            knowledgeDocumentService.syncChapter(ownerId, bookId, chapterId, false);
        } else {
            knowledgeDocumentService.deleteBySourceChapter(ownerId, bookId, chapterId);
        }
    }

    private void refreshSafely(UUID id, Runnable refresh) {
        try {
            refreshTransaction.executeWithoutResult(status -> refresh.run());
        } catch (RuntimeException ex) {
            log.warn("Ripple edit applied but content refresh failed for {}: {}", id, ex.getMessage());
        }
    }

    @Transactional
    public RippleJobResponse cancelJob(UUID ownerId, UUID bookId, UUID jobId) {
        bookService.requireOwnedBook(ownerId, bookId);
        RippleJobEntity job = requireJob(jobId, bookId, ownerId, true);
        if (CANCELLED.equals(job.getStatus())) {
            return toJobResponse(job);
        }
        int batchNo = job.getNextBatchNo() - 1;
        if (job.getFrozenCostMicros() > 0) {
            settleBatch(job, batchNo, 0, 0, 0, "任务取消释放");
        }
        if (activeBatch(job, batchNo)) {
            List<RipplePatchEntity> pending = pendingPatches(jobId, batchNo);
            Map<UUID, RippleImpactEntity> impacts = impactsForPatches(pending);
            for (RipplePatchEntity patch : pending) {
                updatePatch(patch, REJECTED);
                updateImpact(requireImpact(jobId, impacts.get(patch.getImpactId())), SKIPPED);
            }
        }
        saveJob(job, CANCELLED);
        return toJobResponse(job);
    }

    private long settleBatch(RippleJobEntity job, int batchNo, int inputTokens, int cachedTokens, int outputTokens,
                             String description) {
        long charged = billingService.settleCustom(job.getOwnerId(), "RIPPLE_SETTLE:" + job.getId() + ":b" + batchNo,
                job.getFrozenCostMicros(), PATCH_MODEL, inputTokens, cachedTokens, outputTokens, description);
        job.setFrozenCostMicros(0);
        return charged;
    }

    private static boolean activeBatch(RippleJobEntity job, int batchNo) {
        return PATCHING.equals(job.getStatus()) && batchNo > 0 && batchNo == job.getNextBatchNo() - 1;
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private RippleJobDetailResponse detail(RippleJobEntity job) {
        List<RippleImpactResponse> backward = new ArrayList<>();
        List<RippleImpactResponse> forward = new ArrayList<>();
        Map<UUID, RippleImpactEntity> impacts = indexBy(impactMapper.selectList(jobImpacts(job.getId())
                .orderByAsc(RippleImpactEntity::getNarrativeOrder)), RippleImpactEntity::getId);
        for (RippleImpactEntity impact : impacts.values()) {
            (BACKWARD.equals(impact.getDirection()) ? backward : forward).add(toImpactResponse(impact));
        }

        List<RipplePatchResponse> patches = new ArrayList<>();
        for (RipplePatchEntity patch : patchMapper.selectList(jobPatches(job.getId())
                .orderByAsc(RipplePatchEntity::getBatchNo, RipplePatchEntity::getCreatedAt, RipplePatchEntity::getId))) {
            RippleImpactEntity impact = impacts.get(patch.getImpactId());
            if (impact != null) {
                patches.add(new RipplePatchResponse(patch.getId(), patch.getJobId(), patch.getImpactId(),
                        patch.getTargetSectionId(), impact.getChapterNo(), impact.getSectionNo(), impact.getChapterTitle(),
                        impact.getDirection(), patch.getBatchNo(), patch.getBaseVersion(),
                        readList(patch.getHunksJson(), PATCH_HUNKS), patch.getStatus(), patch.getUpdatedAt()));
            }
        }

        return new RippleJobDetailResponse(toJobResponse(job), readList(job.getFactDeltasJson(), FACT_DELTAS),
                List.copyOf(backward), List.copyOf(forward), List.copyOf(patches));
    }

    private RippleJobEntity requireJob(UUID jobId) {
        RippleJobEntity job = jobMapper.selectById(jobId);
        if (job == null) {
            throw BizException.notFound("RIPPLE_JOB_NOT_FOUND", "蝴蝶效应任务不存在");
        }
        return job;
    }

    private RippleJobEntity requireJob(UUID jobId, UUID bookId, UUID ownerId, boolean lock) {
        LambdaQueryWrapper<RippleJobEntity> query = new LambdaQueryWrapper<RippleJobEntity>()
                .eq(RippleJobEntity::getId, jobId)
                .eq(bookId != null, RippleJobEntity::getBookId, bookId)
                .eq(ownerId != null, RippleJobEntity::getOwnerId, ownerId);
        RippleJobEntity job = jobMapper.selectOne(lock ? query.last("FOR NO KEY UPDATE") : query);
        if (job == null) {
            throw BizException.notFound("RIPPLE_JOB_NOT_FOUND", "蝴蝶效应任务不存在");
        }
        return job;
    }

    private ChapterSectionEntity requireSection(UUID bookId, UUID sectionId) {
        ChapterSectionEntity section = sectionMapper.selectById(sectionId);
        if (section == null || !bookId.equals(section.getBookId())) {
            throw BizException.notFound("SECTION_NOT_FOUND", "小节不存在");
        }
        return section;
    }

    private static RippleImpactEntity requireImpact(UUID jobId, RippleImpactEntity impact) {
        if (impact == null || !jobId.equals(impact.getJobId())) {
            throw BizException.notFound("RIPPLE_IMPACT_NOT_FOUND", "找不到该任务的影响项");
        }
        return impact;
    }

    private static void requireStatus(RippleJobEntity job, Set<String> allowed) {
        if (!allowed.contains(job.getStatus())) {
            throw BizException.conflict("RIPPLE_JOB_STATUS_CONFLICT", "任务当前状态无法执行该操作");
        }
    }

    private static Set<UUID> requiredIds(List<UUID> values) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (UUID id : list(values)) {
            if (id == null || !ids.add(id)) {
                throw BizException.badRequest("INVALID_RIPPLE_SELECTION", "选择项必须有效且不能重复");
            }
        }
        return ids;
    }

    private List<RipplePatchEntity> pendingPatches(UUID jobId, int batchNo) {
        return patchMapper.selectList(jobPatches(jobId)
                .eq(RipplePatchEntity::getBatchNo, batchNo)
                .eq(RipplePatchEntity::getStatus, PENDING)
                .orderByAsc(RipplePatchEntity::getCreatedAt, RipplePatchEntity::getId));
    }

    private Map<UUID, RippleImpactEntity> impactsForPatches(Collection<RipplePatchEntity> patches) {
        Set<UUID> ids = patches.stream().map(RipplePatchEntity::getImpactId).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : indexBy(impactMapper.selectByIds(ids), RippleImpactEntity::getId);
    }

    private Map<UUID, ChapterSectionEntity> sectionsForPatches(UUID bookId, List<RipplePatchEntity> patches) {
        Set<UUID> ids = patches.stream().map(RipplePatchEntity::getTargetSectionId).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : indexBy(sectionMapper.selectList(new LambdaQueryWrapper<ChapterSectionEntity>()
                .eq(ChapterSectionEntity::getBookId, bookId).in(ChapterSectionEntity::getId, ids)), ChapterSectionEntity::getId);
    }

    private static LambdaQueryWrapper<ChapterSectionEntity> sectionsInRange(RippleJobEntity job) {
        return new LambdaQueryWrapper<ChapterSectionEntity>()
                .eq(ChapterSectionEntity::getBookId, job.getBookId())
                .between(ChapterSectionEntity::getNarrativeOrder, job.getRangeStartOrder(), job.getRangeEndOrder())
                .ne(ChapterSectionEntity::getId, job.getSourceSectionId());
    }

    private static LambdaQueryWrapper<RippleImpactEntity> jobImpacts(UUID jobId) {
        return new LambdaQueryWrapper<RippleImpactEntity>().eq(RippleImpactEntity::getJobId, jobId);
    }

    private static LambdaQueryWrapper<RipplePatchEntity> jobPatches(UUID jobId) {
        return new LambdaQueryWrapper<RipplePatchEntity>().eq(RipplePatchEntity::getJobId, jobId);
    }

    private void saveJob(RippleJobEntity job, String status) {
        job.setStatus(status);
        job.setUpdatedAt(now());
        jobMapper.updateById(job);
    }

    private void updatePatch(RipplePatchEntity patch, String status) {
        patch.setStatus(status);
        patch.setUpdatedAt(now());
        patchMapper.updateById(patch);
    }

    private void updateImpact(RippleImpactEntity impact, String status) {
        impact.setPatchStatus(status);
        impactMapper.updateById(impact);
    }

    private ResolvedUserEntitlements resolveEntitlements(UUID ownerId) {
        ResolvedUserEntitlements resolved = vipEntitlementService == null ? null : vipEntitlementService.resolveForUser(ownerId);
        return Objects.requireNonNullElse(resolved, FREE);
    }

    private BizException quotaExceeded(ResolvedUserEntitlements resolved, String feature, long limit,
                                       long requested, String message) {
        if (vipEntitlementService != null) {
            return vipEntitlementService.quotaExceeded(resolved, feature, limit, requested, message);
        }
        return BizException.vipQuotaExceeded(message, Map.of("feature", feature, "currentLimit", Long.toString(limit),
                "requested", Long.toString(requested), "currentPlan", resolved.planCode()));
    }

    private void enqueue(RippleJobEntity job, String eventType, String routingKey, String idempotencyKey,
                         Map<String, Object> additionalPayload) {
        Map<String, Object> payload = new LinkedHashMap<>(additionalPayload);
        payload.put("job_id", job.getId().toString());
        payload.put("book_id", job.getBookId().toString());
        payload.put("owner_id", job.getOwnerId().toString());
        payload.put("idempotency_key", idempotencyKey);
        outboxService.enqueue("RIPPLE_JOB", job.getId(), eventType, routingKey, idempotencyKey, payload);
    }

    private String styleAnchors(UUID bookId) {
        return styleAnchorService == null ? "" : text(styleAnchorService.buildStyleAnchorsText(bookId));
    }

    private <T> List<T> readList(String json, TypeReference<List<T>> type) {
        return json == null || json.isBlank() ? List.of() : list(objectMapper.readValue(json, type));
    }

    private static <K, V> Map<K, V> indexBy(Collection<V> rows, Function<V, K> key) {
        Map<K, V> result = new LinkedHashMap<>();
        for (V row : rows) {
            result.put(key.apply(row), row);
        }
        return result;
    }

    private static <T> List<T> list(List<T> values) {
        return Objects.requireNonNullElse(values, List.of());
    }

    private static String text(String value) {
        return Objects.requireNonNullElse(value, "");
    }

    private static String normalized(String value) {
        return text(value).strip().toUpperCase(Locale.ROOT);
    }

    private static int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static RippleJobResponse toJobResponse(RippleJobEntity job) {
        return new RippleJobResponse(job.getId(), job.getBookId(), job.getSourceSectionId(), job.getSourceChapterNo(),
                job.getSourceNarrativeOrder(), job.getVipPlanCode(), job.getRangeMode(), job.getRangeStartChapter(),
                job.getRangeEndChapter(), job.getMaxCandidatesCap(), job.getMaxPatchBatchLimit(), job.getStatus(),
                job.getChangeSummary(), job.getScannedSectionCount(), job.getCandidateSectionCount(),
                job.getBackwardConflictCount(), job.getForwardConflictCount(), job.getMinorMentionCount(),
                job.getEstimatedCostMicros(), ModelPricing.toCredits(job.getEstimatedCostMicros()),
                job.getActualCostMicros(), ModelPricing.toCredits(job.getActualCostMicros()), job.getErrorMessage(),
                job.getCreatedAt(), job.getUpdatedAt());
    }

    private static RippleImpactResponse toImpactResponse(RippleImpactEntity impact) {
        return new RippleImpactResponse(impact.getId(), impact.getJobId(), impact.getTargetSectionId(),
                impact.getChapterNo(), impact.getSectionNo(), impact.getNarrativeOrder(), impact.getChapterTitle(),
                impact.getDirection(), impact.getSeverity(), impact.getConflictReason(), impact.getMatchedExcerpt(),
                impact.getPatchStatus());
    }

    private enum RangeMode { FORWARD, BACKWARD, BIDIRECTIONAL, CUSTOM_RANGE, FULL_BOOK }

    private record ScanRange(RangeMode mode, int startChapter, int endChapter, long startOrder, long endOrder,
                             List<UUID> volumeIds) {}
}
