package studio.foliarc.biz.memory.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.lore.dto.LoreDtos;
import studio.foliarc.biz.lore.service.LoreService;
import studio.foliarc.biz.memory.entity.ChapterSummaryEntity;
import studio.foliarc.biz.memory.entity.CharacterRelationEntity;
import studio.foliarc.biz.memory.entity.SectionSummaryEntity;
import studio.foliarc.biz.memory.entity.StoryRollupSummaryEntity;
import studio.foliarc.biz.memory.entity.VolumeSummaryEntity;
import studio.foliarc.biz.memory.mapper.ChapterSummaryMapper;
import studio.foliarc.biz.memory.mapper.CharacterRelationMapper;
import studio.foliarc.biz.memory.mapper.SectionSummaryMapper;
import studio.foliarc.biz.memory.mapper.StoryRollupSummaryMapper;
import studio.foliarc.biz.memory.mapper.VolumeSummaryMapper;
import studio.foliarc.biz.novel.dto.NovelDtos;
import studio.foliarc.biz.novel.entity.BookEntity;
import studio.foliarc.biz.novel.entity.ChapterEntity;
import studio.foliarc.biz.novel.entity.ChapterSectionEntity;
import studio.foliarc.biz.novel.entity.VolumeEntity;
import studio.foliarc.biz.novel.mapper.BookMapper;
import studio.foliarc.biz.novel.mapper.ChapterMapper;
import studio.foliarc.biz.novel.mapper.ChapterSectionMapper;
import studio.foliarc.biz.novel.mapper.VolumeMapper;
import studio.foliarc.biz.novel.service.BookService;
import studio.foliarc.biz.novel.service.StructureService;
import studio.foliarc.biz.novel.service.WordCounter;
import studio.foliarc.biz.outbox.RabbitMqTopologyConfig;
import studio.foliarc.biz.outbox.service.OutboxService;
import studio.foliarc.biz.rag.dto.RagDtos;

/**
 * Builds story context from the charter, preceding prose, rollup tree and active story entities.
 * Keeps legacy chapter and volume summaries available to older workers and books without sections.
 */
@Service
public class StoryMemoryContextService {

    private static final Logger log = LoggerFactory.getLogger(StoryMemoryContextService.class);
    public static final int CHAPTERS_PER_VOLUME = 10;
    public static final int RECENT_SUMMARY_WINDOW = 10;
    private static final long EAGER_ROLLUP_DISTANCE = 500_000L;

    private final BookService bookService;
    private final BookMapper bookMapper;
    private final ChapterMapper chapterMapper;
    private final ChapterSummaryMapper chapterSummaryMapper;
    private final VolumeSummaryMapper volumeSummaryMapper;
    private final LoreService loreService;
    private final CharacterRelationMapper relationMapper;
    private final OutboxService outboxService;
    private ChapterSectionMapper chapterSectionMapper;
    private VolumeMapper volumeMapper;
    private SectionSummaryMapper sectionSummaryMapper;
    private StoryRollupSummaryMapper storyRollupSummaryMapper;
    private MemoryService memoryService;

    public StoryMemoryContextService(
            BookService bookService,
            BookMapper bookMapper,
            ChapterMapper chapterMapper,
            ChapterSummaryMapper chapterSummaryMapper,
            VolumeSummaryMapper volumeSummaryMapper,
            LoreService loreService,
            CharacterRelationMapper relationMapper,
            OutboxService outboxService
    ) {
        this.bookService = bookService;
        this.bookMapper = bookMapper;
        this.chapterMapper = chapterMapper;
        this.chapterSummaryMapper = chapterSummaryMapper;
        this.volumeSummaryMapper = volumeSummaryMapper;
        this.loreService = loreService;
        this.relationMapper = relationMapper;
        this.outboxService = outboxService;
    }

    @Autowired(required = false)
    public void setChapterSectionMapper(ChapterSectionMapper chapterSectionMapper) {
        this.chapterSectionMapper = chapterSectionMapper;
    }

    @Autowired(required = false)
    public void setVolumeMapper(VolumeMapper volumeMapper) {
        this.volumeMapper = volumeMapper;
    }

    @Autowired(required = false)
    public void setSectionSummaryMapper(SectionSummaryMapper sectionSummaryMapper) {
        this.sectionSummaryMapper = sectionSummaryMapper;
    }

    @Autowired(required = false)
    public void setStoryRollupSummaryMapper(StoryRollupSummaryMapper storyRollupSummaryMapper) {
        this.storyRollupSummaryMapper = storyRollupSummaryMapper;
    }

    @Autowired(required = false)
    public void setMemoryService(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    public record FiveLayerContext(
            String storyCharter,
            String hotWindowContext,
            String rollingSummaryContext,
            String activeStateBrief,
            String previousChaptersCombined,
            String effectivePrecisionMode
    ) {
        public FiveLayerContext(String storyCharter, String hotWindowContext, String rollingSummaryContext,
                                String activeStateBrief, String previousChaptersCombined) {
            this(storyCharter, hotWindowContext, rollingSummaryContext, activeStateBrief, previousChaptersCombined, "PRECISE");
        }
    }

    public static String configuredPrecisionMode(BookEntity book) {
        String mode = book == null ? "" : safe(book.getPrecisionMode()).toUpperCase(Locale.ROOT);
        return switch (mode) {
            case "PRECISE", "EXPEDITION", "EPIC" -> mode;
            default -> "AUTO";
        };
    }

    public static String determinePrecisionMode(BookEntity book, long totalUnits) {
        String override = configuredPrecisionMode(book);
        if (!"AUTO".equals(override)) return override;
        if (totalUnits <= 300) return "PRECISE";
        return totalUnits <= 1_000 ? "EXPEDITION" : "EPIC";
    }

    public static long currentNarrativeOrder(ChapterEntity chapter, ChapterSectionEntity section) {
        if (section != null) return section.getNarrativeOrder();
        return chapter == null || chapter.getChapterNo() == null ? 0L : StructureService.narrativeOrder(chapter.getChapterNo(), 1);
    }

    public static String sha256Hex(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public static int volumeNoForChapter(int chapterNo) {
        return Math.max(1, ((Math.max(1, chapterNo) - 1) / CHAPTERS_PER_VOLUME) + 1);
    }

    /**
     * Invalidates the compatibility section for chapter edits and supports the legacy worker
     * for earlier deployments without a section for the chapter.
     */
    public void invalidateAndEnqueueSummary(UUID ownerId, UUID bookId, ChapterEntity chapter) {
        if (chapter == null || chapter.getContent() == null) {
            return;
        }
        boolean distillable = WordCounter.count(chapter.getContent()) >= 60;
        if (chapterSectionMapper != null) {
            ChapterSectionEntity section = chapterSectionMapper.selectOne(ChapterSectionMapper.inChapter(chapter.getId())
                    .eq(ChapterSectionEntity::getIsDefault, true).last("LIMIT 1"));
            if (section != null) {
                if (Boolean.TRUE.equals(section.getSummaryDirty())) {
                    markSectionSummariesStale(bookId, section);
                    if (distillable) enqueueSectionDistill(ownerId, bookId, section);
                }
                return;
            }
        }
        try {
            String contentHash = (chapter.getContentHash() != null && !chapter.getContentHash().isBlank())
                    ? chapter.getContentHash()
                    : sha256Hex(chapter.getContent());

            ChapterSummaryEntity existing = chapterSummaryMapper.selectOne(
                    new LambdaQueryWrapper<ChapterSummaryEntity>()
                            .eq(ChapterSummaryEntity::getChapterId, chapter.getId())
            );
            if (existing != null && contentHash.equals(existing.getContentHash()) && "READY".equals(existing.getStatus())) {
                return;
            }
            if (existing != null) {
                existing.setStatus("STALE");
                chapterSummaryMapper.updateById(existing);
            }

            if (!distillable && existing == null) return;
            int volNo = volumeNoForChapter(chapter.getChapterNo());
            VolumeSummaryEntity existingVol = volumeSummaryMapper.selectOne(
                    new LambdaQueryWrapper<VolumeSummaryEntity>()
                            .eq(VolumeSummaryEntity::getBookId, bookId)
                            .eq(VolumeSummaryEntity::getVolumeNo, volNo)
            );
            if (existingVol != null && !"STALE".equals(existingVol.getStatus())) {
                existingVol.setStatus("STALE");
                volumeSummaryMapper.updateById(existingVol);
            }
            if (!distillable) return;

            String idempotencyKey = "CHAPTER_SUMMARY:" + chapter.getId() + ":v" + chapter.getVersion() + ":" + contentHash;
            Map<String, Object> payload = new HashMap<>();
            payload.put("book_id", bookId.toString());
            payload.put("owner_id", ownerId.toString());
            payload.put("chapter_id", chapter.getId().toString());
            payload.put("chapter_no", chapter.getChapterNo());
            payload.put("chapter_title", chapter.getTitle() == null ? "" : chapter.getTitle());
            payload.put("content_hash", contentHash);
            payload.put("idempotency_key", idempotencyKey);

            outboxService.enqueue(
                    "CHAPTER",
                    chapter.getId(),
                    "CHAPTER_SUMMARY",
                    RabbitMqTopologyConfig.RK_SUMMARY,
                    idempotencyKey,
                    payload
            );
        } catch (Exception ex) {
            log.warn("Skipped async summary enqueue for chapter {}: {}", chapter.getId(), ex.getMessage());
        }
    }

    public void invalidateAndEnqueueSectionDistill(UUID ownerId, UUID bookId, ChapterSectionEntity section) {
        invalidateAndEnqueueSectionDistill(ownerId, bookId, section, true);
    }

    private void invalidateAndEnqueueSectionDistill(
            UUID ownerId, UUID bookId, ChapterSectionEntity section, boolean invalidate
    ) {
        if (invalidate) markSectionSummariesStale(bookId, section);
        enqueueSectionDistill(ownerId, bookId, section);
    }

    private void enqueueSectionDistill(UUID ownerId, UUID bookId, ChapterSectionEntity section) {
        String key = "SECTION_DISTILL:" + section.getId() + ":v" + section.getVersion();
        Map<String, Object> payload = new HashMap<>();
        payload.put("book_id", bookId.toString());
        payload.put("owner_id", ownerId.toString());
        payload.put("chapter_id", section.getChapterId().toString());
        payload.put("section_id", section.getId().toString());
        payload.put("chapter_no", section.getChapterNo());
        payload.put("section_no", section.getSectionNo());
        payload.put("narrative_order", section.getNarrativeOrder());
        payload.put("content_hash", safe(section.getContentHash()));
        payload.put("idempotency_key", key);
        outboxService.enqueue("SECTION", section.getId(), "SECTION_DISTILL", RabbitMqTopologyConfig.RK_DISTILL, key, payload);
    }

    private void markSectionSummariesStale(UUID bookId, ChapterSectionEntity section) {
        if (sectionSummaryMapper != null) {
            sectionSummaryMapper.update(null, new LambdaUpdateWrapper<SectionSummaryEntity>()
                    .eq(SectionSummaryEntity::getSectionId, section.getId())
                    .ne(SectionSummaryEntity::getStatus, "STALE")
                    .set(SectionSummaryEntity::getStatus, "STALE")
                    .set(SectionSummaryEntity::getUpdatedAt, OffsetDateTime.now()));
        }
        markRollupsStaleForSection(bookId, section);
    }

    public void markRollupsStaleForSection(UUID bookId, ChapterSectionEntity section) {
        markStoryRollupsStale(bookId, section.getNarrativeOrder(), section.getNarrativeOrder());
        if (Boolean.TRUE.equals(section.getIsDefault())) {
            chapterSummaryMapper.update(null, new LambdaUpdateWrapper<ChapterSummaryEntity>()
                    .eq(ChapterSummaryEntity::getChapterId, section.getChapterId())
                    .ne(ChapterSummaryEntity::getStatus, "STALE")
                    .set(ChapterSummaryEntity::getStatus, "STALE")
                    .set(ChapterSummaryEntity::getUpdatedAt, OffsetDateTime.now()));
            markLegacyVolumeSummariesStale(bookId, section.getChapterNo());
        }
    }

    public void markRollupsStaleForChapter(UUID bookId, int chapterNo) {
        markStoryRollupsStale(bookId, StructureService.narrativeOrder(chapterNo, 1),
                StructureService.narrativeOrder(chapterNo, 999));
        markLegacyVolumeSummariesStale(bookId, chapterNo);
    }

    private void markStoryRollupsStale(UUID bookId, long start, long end) {
        markStoryRollupsStale(bookId, start, end, 0);
    }

    private void markStoryRollupsStale(UUID bookId, long start, long end, int lowerTier) {
        if (storyRollupSummaryMapper != null) {
            storyRollupSummaryMapper.update(null, new LambdaUpdateWrapper<StoryRollupSummaryEntity>()
                    .eq(StoryRollupSummaryEntity::getBookId, bookId)
                    .gt(lowerTier > 0, StoryRollupSummaryEntity::getTierLevel, lowerTier)
                    .le(StoryRollupSummaryEntity::getStartOrder, end)
                    .ge(StoryRollupSummaryEntity::getEndOrder, start)
                    .ne(StoryRollupSummaryEntity::getStatus, "STALE")
                    .set(StoryRollupSummaryEntity::getStatus, "STALE")
                    .set(StoryRollupSummaryEntity::getUpdatedAt, OffsetDateTime.now()));
        }
    }

    private void markLegacyVolumeSummariesStale(UUID bookId, int chapterNo) {
        volumeSummaryMapper.update(null, new LambdaUpdateWrapper<VolumeSummaryEntity>()
                .eq(VolumeSummaryEntity::getBookId, bookId)
                .le(VolumeSummaryEntity::getStartChapterNo, chapterNo)
                .ge(VolumeSummaryEntity::getEndChapterNo, chapterNo)
                .ne(VolumeSummaryEntity::getStatus, "STALE")
                .set(VolumeSummaryEntity::getStatus, "STALE")
                .set(VolumeSummaryEntity::getUpdatedAt, OffsetDateTime.now()));
    }

    public FiveLayerContext buildFiveLayerContext(
            UUID ownerId,
            BookEntity book,
            ChapterEntity currentChapter,
            String currentDraft,
            String instruction
    ) {
        return buildAdaptiveContext(ownerId, book, currentChapter, null, currentDraft, instruction);
    }

    public FiveLayerContext buildAdaptiveContext(
            UUID ownerId, BookEntity book, ChapterEntity currentChapter, ChapterSectionEntity currentSection,
            String currentDraft, String instruction
    ) {
        if (chapterSectionMapper == null) {
            return buildLegacyContext(ownerId, book, currentChapter, currentDraft, instruction);
        }
        UUID bookId = book.getId();
        long currentOrder = currentNarrativeOrder(currentChapter, currentSection);
        long totalUnits = chapterSectionMapper.selectCount(new LambdaQueryWrapper<ChapterSectionEntity>()
                .eq(ChapterSectionEntity::getBookId, bookId)
                .lt(currentOrder > 0, ChapterSectionEntity::getNarrativeOrder, currentOrder));
        String mode = determinePrecisionMode(book, totalUnits);
        if (currentOrder == 0) {
            return new FiveLayerContext(resolveEffectiveCharter(book), "", "",
                    buildEntityScopedState(ownerId, bookId, 1, "", currentDraft, instruction, ""), "", mode);
        }
        if (totalUnits == 0 && currentSection == null && chapterSectionMapper.selectOne(
                new LambdaQueryWrapper<ChapterSectionEntity>().select(ChapterSectionEntity::getId)
                        .eq(ChapterSectionEntity::getBookId, bookId).last("LIMIT 1")) == null) {
            return buildLegacyContext(ownerId, book, currentChapter, currentDraft, instruction);
        }

        ChapterSectionEntity preceding = chapterSectionMapper.selectOne(new LambdaQueryWrapper<ChapterSectionEntity>()
                .eq(ChapterSectionEntity::getBookId, bookId)
                .lt(ChapterSectionEntity::getNarrativeOrder, currentOrder)
                .gt(ChapterSectionEntity::getWordCount, 0)
                .orderByDesc(ChapterSectionEntity::getNarrativeOrder)
                .last("LIMIT 1"));
        String hotTail = preceding == null ? "" : hotTail(preceding.getContent());
        String hotWindow = "";
        if (preceding != null) {
            hotWindow = "【上一节（第 " + preceding.getChapterNo() + " 章 / 第 " + preceding.getSectionNo()
                    + " 节《" + preceding.getTitle() + "》）原文衔接】\n" + hotTail;
            if (Boolean.TRUE.equals(preceding.getSummaryDirty())) {
                // The edit already invalidated covering summaries. A reader can safely re-request the job.
                invalidateAndEnqueueSectionDistill(ownerId, bookId, preceding, false);
            }
        }

        List<SectionSummaryEntity> recent = sectionSummaryMapper == null ? List.of() : sectionSummaryMapper.selectList(
                new LambdaQueryWrapper<SectionSummaryEntity>()
                        .select(SectionSummaryEntity::getNarrativeOrder, SectionSummaryEntity::getSummaryText,
                                SectionSummaryEntity::getChapterNo, SectionSummaryEntity::getSectionNo,
                                SectionSummaryEntity::getInvolvedCharacters)
                        .eq(SectionSummaryEntity::getBookId, bookId)
                        .eq(SectionSummaryEntity::getStatus, "READY")
                        .lt(SectionSummaryEntity::getNarrativeOrder, currentOrder)
                        .orderByDesc(SectionSummaryEntity::getNarrativeOrder)
                        .last("LIMIT " + RECENT_SUMMARY_WINDOW));
        String previousCharacters = preceding == null ? "" : recent.stream()
                .filter(s -> s.getNarrativeOrder().equals(preceding.getNarrativeOrder()))
                .map(s -> safe(s.getInvolvedCharacters())).findFirst().orElse("");
        VolumeEntity volume = findVolume(bookId, currentChapter == null ? null : currentChapter.getVolumeId());
        String rolling = AdaptiveMemoryAssembler.assemble(storyRollupSummaryMapper, bookId, currentOrder, mode,
                volume == null ? "" : volume.getArcGoal(), recent);
        int maxChapterNo = preceding == null ? (int) (currentOrder / 1000) - 1 : preceding.getChapterNo();
        String activeState = buildEntityScopedState(ownerId, bookId, Math.max(1, maxChapterNo),
                hotTail, currentDraft, instruction, previousCharacters);
        return new FiveLayerContext(resolveEffectiveCharter(book), hotWindow, rolling, activeState,
                combineContext(rolling, hotWindow), mode);
    }

    private VolumeEntity findVolume(UUID bookId, UUID volumeId) {
        if (volumeMapper == null) return null;
        if (volumeId != null) return volumeMapper.selectById(volumeId);
        return volumeMapper.selectOne(new LambdaQueryWrapper<VolumeEntity>()
                .eq(VolumeEntity::getBookId, bookId).eq(VolumeEntity::getVolumeNo, 1));
    }

    private FiveLayerContext buildLegacyContext(
            UUID ownerId, BookEntity book, ChapterEntity currentChapter, String currentDraft, String instruction
    ) {
        String storyCharter = resolveEffectiveCharter(book);

        if (currentChapter == null || currentChapter.getChapterNo() == null || currentChapter.getChapterNo() <= 1) {
            String l3 = buildEntityScopedState(ownerId, book.getId(), 1, "", currentDraft, instruction, "");
            return new FiveLayerContext(storyCharter, "", "", l3, "", determinePrecisionMode(book, 0));
        }

        int currentNo = currentChapter.getChapterNo();
        String precisionMode = determinePrecisionMode(book, currentNo - 1L);

        // Legacy books need some prose for unsummarized fallback entries. Keep this window bounded as well.
        List<ChapterEntity> earlierChapters = chapterMapper.selectList(
                new LambdaQueryWrapper<ChapterEntity>()
                        .eq(ChapterEntity::getBookId, book.getId())
                        .lt(ChapterEntity::getChapterNo, currentNo)
                        .gt(ChapterEntity::getWordCount, 0)
                        .orderByDesc(ChapterEntity::getChapterNo)
                        .last("LIMIT 22")
        );
        List<ChapterEntity> nonEmptyEarlier = (earlierChapters == null ? List.<ChapterEntity>of() : earlierChapters)
                .stream()
                .filter(c -> c.getContent() != null && !c.getContent().isBlank())
                .sorted(Comparator.comparing(ChapterEntity::getChapterNo))
                .toList();

        if (nonEmptyEarlier.isEmpty()) {
            String l3 = buildEntityScopedState(ownerId, book.getId(), currentNo - 1, "", currentDraft, instruction, "");
            return new FiveLayerContext(storyCharter, "", "", l3, "", precisionMode);
        }

        // L1 Hot Window: verbatim tail of immediately preceding non-empty chapter (N-1)
        ChapterEntity immediatePrev = nonEmptyEarlier.getLast();
        String hotTail = hotTail(immediatePrev.getContent());
        String hotWindow = "【上一章（第 " + immediatePrev.getChapterNo() + " 章《" + immediatePrev.getTitle() + "》）原文衔接】\n" + hotTail;

        // L2 Hierarchical Rolling Summaries:
        // - Recent window: up to RECENT_SUMMARY_WINDOW (10) chapters before currentNo
        // - Older chapters before recent window: Volume Milestones (10 chapters per volume)
        int recentStartNo = Math.max(1, currentNo - RECENT_SUMMARY_WINDOW);

        List<ChapterSummaryEntity> storedSummaries = chapterSummaryMapper.selectList(
                new LambdaQueryWrapper<ChapterSummaryEntity>()
                        .eq(ChapterSummaryEntity::getBookId, book.getId())
                        .lt(ChapterSummaryEntity::getChapterNo, currentNo)
                        .orderByDesc(ChapterSummaryEntity::getChapterNo)
                        .last("LIMIT 22")
        );
        Map<Integer, ChapterSummaryEntity> summaryByNo = new HashMap<>();
        if (storedSummaries != null) {
            for (ChapterSummaryEntity s : storedSummaries) {
                summaryByNo.put(s.getChapterNo(), s);
            }
        }

        List<VolumeSummaryEntity> storedVolumes = volumeSummaryMapper.selectList(
                new LambdaQueryWrapper<VolumeSummaryEntity>()
                        .eq(VolumeSummaryEntity::getBookId, book.getId())
                        .lt(VolumeSummaryEntity::getEndChapterNo, recentStartNo)
                        .orderByDesc(VolumeSummaryEntity::getEndChapterNo)
                        .last("LIMIT 28")
        );

        StringBuilder l2 = new StringBuilder();
        if (storedVolumes != null && !storedVolumes.isEmpty()) {
            l2.append("【前卷主线里程碑】\n");
            int coveredUntil = 0;
            for (VolumeSummaryEntity v : storedVolumes.stream().sorted(Comparator.comparing(VolumeSummaryEntity::getEndChapterNo)).toList()) {
                l2.append("- 第 ").append(v.getVolumeNo()).append(" 卷（第 ")
                        .append(v.getStartChapterNo()).append("~").append(v.getEndChapterNo()).append(" 章）：")
                        .append(v.getSummaryText().trim()).append("\n");
                coveredUntil = Math.max(coveredUntil, v.getEndChapterNo());
            }
            l2.append("\n");
            // If there are early chapters not yet rolled into a volume summary, add ultra-brief fallback lines
            if (coveredUntil < recentStartNo - 1) {
                for (ChapterEntity ch : nonEmptyEarlier) {
                    if (ch.getChapterNo() > coveredUntil && ch.getChapterNo() < recentStartNo) {
                        ChapterSummaryEntity sum = summaryByNo.get(ch.getChapterNo());
                        String line = sum != null ? sum.getSummaryText().trim() : briefFallbackExcerpt(ch.getContent(), 120);
                        l2.append("- 第 ").append(ch.getChapterNo()).append(" 章《").append(ch.getTitle()).append("》：")
                                .append(line).append("\n");
                    }
                }
                l2.append("\n");
            }
        } else if (recentStartNo > 1) {
            // No volume summaries generated yet for older chapters -> fallback to chapter summaries or concise 120-char recaps
            List<String> earlyLines = new ArrayList<>();
            for (ChapterEntity ch : nonEmptyEarlier) {
                if (ch.getChapterNo() < recentStartNo) {
                    ChapterSummaryEntity sum = summaryByNo.get(ch.getChapterNo());
                    String line = sum != null ? sum.getSummaryText().trim() : briefFallbackExcerpt(ch.getContent(), 120);
                    earlyLines.add("- 第 " + ch.getChapterNo() + " 章《" + ch.getTitle() + "》：" + line);
                }
            }
            if (!earlyLines.isEmpty()) {
                l2.append("【更早章节脉络】\n");
                // Cap fallback lines to avoid prompt explosion on un-summarized massive imports
                int startIdx = Math.max(0, earlyLines.size() - 12);
                for (int i = startIdx; i < earlyLines.size(); i++) {
                    l2.append(earlyLines.get(i)).append("\n");
                }
                l2.append("\n");
            }
        }

        // Recent 10 chapters structured summaries (excluding immediatePrev full duplication, or concise summary + hook)
        List<String> recentLines = new ArrayList<>();
        String prevInvolvedChars = "";
        for (ChapterEntity ch : nonEmptyEarlier) {
            if (ch.getChapterNo() < recentStartNo) {
                continue;
            }
            ChapterSummaryEntity sum = summaryByNo.get(ch.getChapterNo());
            if (ch.getId().equals(immediatePrev.getId())) {
                if (sum != null) {
                    prevInvolvedChars = sum.getInvolvedCharacters() == null ? "" : sum.getInvolvedCharacters();
                    if (sum.getEndingHook() != null && !sum.getEndingHook().isBlank()) {
                        recentLines.add("- 第 " + ch.getChapterNo() + " 章《" + ch.getTitle() + "》章末悬念：" + sum.getEndingHook().trim());
                    }
                }
                continue;
            }
            if (sum != null) {
                StringBuilder entry = new StringBuilder("- 第 ")
                        .append(ch.getChapterNo()).append(" 章《").append(ch.getTitle()).append("》：")
                        .append(sum.getSummaryText().trim());
                if (sum.getEndingHook() != null && !sum.getEndingHook().isBlank()) {
                    entry.append("（钩子：").append(sum.getEndingHook().trim()).append("）");
                }
                recentLines.add(entry.toString());
            } else {
                recentLines.add("- 第 " + ch.getChapterNo() + " 章《" + ch.getTitle() + "》："
                        + briefFallbackExcerpt(ch.getContent(), 220));
            }
        }
        String rollingSummary = AdaptiveMemoryAssembler.assembleLegacy(l2.toString().trim(), recentLines);

        // L3 Entity-Scoped Dynamic State (Protagonists + characters appearing in N-1/draft/instruction + OPEN foreshadowings)
        String activeState = buildEntityScopedState(
                ownerId,
                book.getId(),
                currentNo - 1,
                hotTail,
                currentDraft,
                instruction,
                prevInvolvedChars
        );

        return new FiveLayerContext(storyCharter, hotWindow, rollingSummary, activeState,
                combineContext(rollingSummary, hotWindow), precisionMode);
    }

    private static String hotTail(String content) {
        String body = content.trim();
        if (body.length() <= 2400) return body;
        int start = body.length() - 2200;
        if (Character.isLowSurrogate(body.charAt(start))) start++;
        return "……\n" + body.substring(start);
    }

    private static String combineContext(String rolling, String hotWindow) {
        if (rolling.isBlank()) return hotWindow;
        return hotWindow.isBlank() ? rolling : rolling + "\n\n" + hotWindow;
    }

    private String buildEntityScopedState(
            UUID ownerId,
            UUID bookId,
            int maxChapterNo,
            String hotTail,
            String currentDraft,
            String instruction,
            String prevInvolvedChars
    ) {
        try {
            List<LoreDtos.CharacterResponse> allChars = loreService.listCharacters(ownerId, bookId, null);
            List<LoreDtos.ForeshadowingResponse> openClues = loreService.listForeshadowings(ownerId, bookId, "OPEN");

            String contextProbe = (hotTail == null ? "" : hotTail) + "\n"
                    + (currentDraft == null ? "" : currentDraft) + "\n"
                    + (instruction == null ? "" : instruction) + "\n"
                    + (prevInvolvedChars == null ? "" : prevInvolvedChars);

            List<LoreDtos.CharacterResponse> scopedChars = new ArrayList<>();
            Set<String> activeNames = new LinkedHashSet<>();
            for (LoreDtos.CharacterResponse c : allChars) {
                boolean isProtagonist = "PROTAGONIST".equalsIgnoreCase(c.roleType());
                boolean mentioned = c.name() != null && !c.name().isBlank() && contextProbe.contains(c.name());
                if (isProtagonist || mentioned) {
                    scopedChars.add(c);
                    activeNames.add(c.name());
                }
                if (scopedChars.size() >= 10) {
                    break;
                }
            }
            // Fallback: if book has <= 6 characters total and none matched probe yet, include them all
            if (scopedChars.isEmpty() && !allChars.isEmpty()) {
                for (LoreDtos.CharacterResponse c : allChars.subList(0, Math.min(6, allChars.size()))) {
                    scopedChars.add(c);
                    activeNames.add(c.name());
                }
            }

            var relQuery = new LambdaQueryWrapper<CharacterRelationEntity>()
                    .eq(CharacterRelationEntity::getBookId, bookId)
                    .orderByDesc(CharacterRelationEntity::getUpdatedAt);
            if (maxChapterNo > 0) {
                relQuery.and(w -> w.isNull(CharacterRelationEntity::getSinceChapterNo)
                        .or().le(CharacterRelationEntity::getSinceChapterNo, maxChapterNo));
            }
            List<CharacterRelationEntity> allRels = relationMapper.selectList(relQuery);
            List<CharacterRelationEntity> scopedRels = (allRels == null ? List.<CharacterRelationEntity>of() : allRels)
                    .stream()
                    .filter(r -> activeNames.isEmpty()
                            || activeNames.contains(r.getSourceCharacter())
                            || activeNames.contains(r.getTargetCharacter()))
                    .limit(8)
                    .toList();

            List<LoreDtos.ForeshadowingResponse> scopedClues = openClues.stream()
                    .filter(f -> f.plantedChapterNo() == null || maxChapterNo <= 0 || f.plantedChapterNo() <= maxChapterNo)
                    .limit(10)
                    .toList();

            StringBuilder sb = new StringBuilder();
            if (!scopedChars.isEmpty()) {
                sb.append("【当前活跃人物状态】\n");
                for (LoreDtos.CharacterResponse c : scopedChars) {
                    sb.append("- ").append(c.name());
                    List<String> attrs = new ArrayList<>();
                    if (c.realmOrLevel() != null && !c.realmOrLevel().isBlank()) attrs.add("境界:" + c.realmOrLevel());
                    if (c.currentLocation() != null && !c.currentLocation().isBlank()) attrs.add("位置:" + c.currentLocation());
                    if (c.inventory() != null && !c.inventory().isBlank()) attrs.add("物品:" + c.inventory());
                    if (c.statusNotes() != null && !c.statusNotes().isBlank()) attrs.add("状态:" + c.statusNotes());
                    if (!attrs.isEmpty()) {
                        sb.append("（").append(String.join("；", attrs)).append("）");
                    }
                    sb.append("\n");
                }
            }
            if (!scopedRels.isEmpty()) {
                sb.append("\n【相关人物关系】\n");
                for (CharacterRelationEntity r : scopedRels) {
                    sb.append("- ").append(r.getSourceCharacter()).append(" -[").append(r.getRelationType())
                            .append("]-> ").append(r.getTargetCharacter());
                    if (r.getDescription() != null && !r.getDescription().isBlank()) {
                        sb.append("：").append(r.getDescription());
                    }
                    sb.append("\n");
                }
            }
            if (!scopedClues.isEmpty()) {
                sb.append("\n【待回收伏笔（OPEN）】\n");
                for (LoreDtos.ForeshadowingResponse f : scopedClues) {
                    sb.append("- ");
                    if (f.plantedChapterNo() != null) {
                        sb.append("[第").append(f.plantedChapterNo()).append("章] ");
                    }
                    sb.append(f.title());
                    if (f.description() != null && !f.description().isBlank()) {
                        sb.append("：").append(f.description());
                    }
                    sb.append("\n");
                }
            }
            return sb.toString().trim();
        } catch (Exception ex) {
            log.warn("Entity-scoped state assembly skipped for book {}: {}", bookId, ex.getMessage());
            return "";
        }
    }

    // --- Book Story Charter (L0 Static Base: author_defined > auto_inferred) ---

    public static String resolveEffectiveCharter(BookEntity book) {
        if (book == null) {
            return "";
        }
        if ("AUTHOR_DEFINED".equals(book.getCharterSource())
                && book.getAuthorCharter() != null && !book.getAuthorCharter().isBlank()) {
            return book.getAuthorCharter().trim();
        }
        if (book.getAuthorCharter() != null && !book.getAuthorCharter().isBlank()) {
            return book.getAuthorCharter().trim();
        }
        if (book.getAutoCharter() != null && !book.getAutoCharter().isBlank()) {
            return book.getAutoCharter().trim();
        }
        return book.getSynopsis() != null ? book.getSynopsis().trim() : "";
    }

    public NovelDtos.BookCharterResponse getBookCharter(UUID ownerId, UUID bookId) {
        BookEntity book = bookService.requireOwnedBook(ownerId, bookId);
        return toCharterResponse(book);
    }

    @Transactional
    public NovelDtos.BookCharterResponse saveAuthorCharter(UUID ownerId, UUID bookId, String charterText) {
        BookEntity book = bookService.requireOwnedBook(ownerId, bookId);
        String cleaned = charterText == null ? "" : charterText.trim();
        if (cleaned.isEmpty()) {
            book.setAuthorCharter(null);
            book.setCharterSource(
                    book.getAutoCharter() != null && !book.getAutoCharter().isBlank() ? "AUTO_INFERRED" : "NONE"
            );
        } else {
            book.setAuthorCharter(cleaned);
            book.setCharterSource("AUTHOR_DEFINED");
        }
        bookMapper.updateById(book);
        return toCharterResponse(book);
    }

    // --- Internal Worker Callbacks (Chapter Summary, Volume Summary, Auto Charter) ---

    public RagDtos.InternalSectionPayload loadSectionForWorker(UUID sectionId) {
        requireSectionMemory();
        ChapterSectionEntity section = requireSection(sectionId);
        BookEntity book = bookService.getBookByIdUnchecked(section.getBookId());
        ChapterEntity chapter = requireSectionChapter(section);
        VolumeEntity volume = findVolume(book.getId(), chapter.getVolumeId());
        return new RagDtos.InternalSectionPayload(
                sectionId, chapter.getId(), chapter.getVolumeId(), book.getId(), book.getOwnerId(),
                section.getChapterNo(), section.getSectionNo(), section.getNarrativeOrder(),
                chapter.getTitle(), section.getTitle(), section.getContent(), section.getContentHash(),
                volume == null ? "" : volume.getArcGoal(),
                safe(sectionSummaryMapper.findPreviousEndingHook(book.getId(), section.getNarrativeOrder())),
                emptyIfNull(chapterSectionMapper.firstSectionSample(book.getId())),
                sectionSample(section.getContent()), book.getTitle(), book.getGenre(), book.getSynopsis()
        );
    }

    @Transactional
    public RagDtos.InternalApplyDistillResponse applySectionDistill(UUID bookId, RagDtos.InternalApplyDistillRequest req) {
        requireSectionMemory();
        // Serialize readiness milestones across concurrent completions for this book.
        bookMapper.lockForStructure(bookId);
        BookEntity book = bookService.getBookByIdUnchecked(bookId);
        ChapterSectionEntity section = requireSection(req.sectionId());
        if (!bookId.equals(section.getBookId())) {
            throw BizException.notFound("SECTION_NOT_FOUND", "小节不存在");
        }
        chapterMapper.lockForStructure(bookId, section.getChapterId());
        section = requireSection(req.sectionId());
        ChapterEntity chapter = requireSectionChapter(section);
        UUID sectionId = section.getId();
        if (!safe(section.getContentHash()).equals(safe(req.contentHash()))) {
            return new RagDtos.InternalApplyDistillResponse(false, sectionId, null, false, false, null);
        }

        SectionSummaryEntity summary = sectionSummaryMapper.selectOne(new LambdaQueryWrapper<SectionSummaryEntity>()
                .eq(SectionSummaryEntity::getSectionId, sectionId));
        if (summary != null && "READY".equals(summary.getStatus())
                && safe(section.getContentHash()).equals(summary.getContentHash())
                && !Boolean.TRUE.equals(section.getSummaryDirty())) {
            return new RagDtos.InternalApplyDistillResponse(true, sectionId, summary.getId(), false, false, null);
        }

        summary = upsertSectionSummary(book, chapter, section, req.summary(), summary);
        chapterSectionMapper.update(null, new LambdaUpdateWrapper<ChapterSectionEntity>()
                .eq(ChapterSectionEntity::getId, sectionId)
                .set(ChapterSectionEntity::getSummaryDirty, false));
        if (Boolean.TRUE.equals(section.getIsDefault())) {
            upsertChapterSummary(book, new RagDtos.InternalSaveChapterSummaryRequest(
                    chapter.getId(), section.getChapterNo(), chapter.getTitle(), summary.getContentHash(),
                    summary.getSummaryText(), summary.getStateChanges(), summary.getInvolvedCharacters(), summary.getEndingHook()
            ));
            chapterMapper.update(null, new LambdaUpdateWrapper<ChapterEntity>()
                    .eq(ChapterEntity::getId, chapter.getId())
                    .eq(ChapterEntity::getContentHash, summary.getContentHash())
                    .set(ChapterEntity::getSummaryDirty, false));
        }

        RagDtos.ApplyExtractedMemoryResponse appliedMemory = null;
        if (req.memory() != null && memoryService != null) {
            RagDtos.ApplyExtractedMemoryRequest memory = req.memory();
            if (!section.getChapterNo().equals(memory.chapterNo())) {
                memory = new RagDtos.ApplyExtractedMemoryRequest(section.getChapterNo(), memory.characterUpdates(),
                        memory.foreshadowingUpdates(), memory.relations(), memory.timelineEvents());
            }
            appliedMemory = memoryService.applyExtractedMemory(book.getOwnerId(), bookId, memory);
        }

        long order = section.getNarrativeOrder();
        markStoryRollupsStale(bookId, order, order);
        ChapterSectionEntity latest = chapterSectionMapper.selectOne(new LambdaQueryWrapper<ChapterSectionEntity>()
                .select(ChapterSectionEntity::getNarrativeOrder)
                .eq(ChapterSectionEntity::getBookId, bookId)
                .orderByDesc(ChapterSectionEntity::getNarrativeOrder).last("LIMIT 1"));
        if (latest != null && latest.getNarrativeOrder() - order > EAGER_ROLLUP_DISTANCE) {
            return new RagDtos.InternalApplyDistillResponse(true, sectionId, summary.getId(), false, false, appliedMemory);
        }

        long totalReady = sectionSummaryMapper.selectCount(new LambdaQueryWrapper<SectionSummaryEntity>()
                .eq(SectionSummaryEntity::getBookId, bookId).eq(SectionSummaryEntity::getStatus, "READY"));
        int lastCharterCount = book.getCharterChapterCount() == null ? 0 : book.getCharterChapterCount();
        boolean refreshCharter = !"AUTHOR_DEFINED".equals(book.getCharterSource())
                && ((totalReady >= 1 && lastCharterCount == 0)
                || (totalReady >= 3 && lastCharterCount < 3)
                || totalReady >= (long) lastCharterCount + CHAPTERS_PER_VOLUME);
        VolumeEntity volume = chapter.getVolumeId() == null ? null : findVolume(bookId, chapter.getVolumeId());
        boolean queueRollup = rollupScheduler().afterDistill(book, order, volume, totalReady, refreshCharter);
        return new RagDtos.InternalApplyDistillResponse(true, sectionId, summary.getId(), queueRollup, refreshCharter, appliedMemory);
    }

    private SectionSummaryEntity upsertSectionSummary(
            BookEntity book, ChapterEntity chapter, ChapterSectionEntity section, RagDtos.DistilledSectionSummary input,
            SectionSummaryEntity summary
    ) {
        boolean insert = summary == null;
        if (insert) {
            summary = new SectionSummaryEntity();
            summary.setId(UUID.randomUUID());
            summary.setSectionId(section.getId());
            summary.setChapterId(chapter.getId());
            summary.setBookId(book.getId());
            summary.setOwnerId(book.getOwnerId());
        }
        summary.setChapterNo(section.getChapterNo());
        summary.setSectionNo(section.getSectionNo());
        summary.setNarrativeOrder(section.getNarrativeOrder());
        summary.setChapterTitle(safe(chapter.getTitle()));
        summary.setSectionTitle(safe(section.getTitle()));
        summary.setContentHash(safe(section.getContentHash()));
        summary.setTimeAndPlace(safe(input.timeAndPlace()));
        summary.setSummaryText(safe(input.summaryText()));
        summary.setStateChanges(safe(input.stateChanges()));
        summary.setClueSignals(safe(input.clueSignals()));
        summary.setPovNotes(safe(input.povNotes()));
        summary.setInvolvedCharacters(safe(input.involvedCharacters()));
        summary.setEndingHook(safe(input.endingHook()));
        summary.setStatus("READY");
        if (insert) {
            sectionSummaryMapper.insert(summary);
        } else {
            summary.setUpdatedAt(OffsetDateTime.now());
            sectionSummaryMapper.updateById(summary);
        }
        return summary;
    }

    private StoryRollupScheduler rollupScheduler() {
        return new StoryRollupScheduler(sectionSummaryMapper, storyRollupSummaryMapper, outboxService);
    }

    @Transactional(readOnly = true)
    public RagDtos.InternalBookRollupsPayload loadBookRollupsForWorker(UUID bookId) {
        requireSectionMemory();
        BookEntity book = bookService.getBookByIdUnchecked(bookId);
        List<RagDtos.SectionSummaryItem> summaries = sectionSummaryMapper.selectList(new LambdaQueryWrapper<SectionSummaryEntity>()
                        .eq(SectionSummaryEntity::getBookId, bookId)
                        .eq(SectionSummaryEntity::getStatus, "READY")
                        .orderByAsc(SectionSummaryEntity::getNarrativeOrder))
                .stream().map(StoryMemoryContextService::toSectionSummaryItem).toList();
        List<RagDtos.StoryRollupItem> rollups = storyRollupSummaryMapper.selectList(
                        new LambdaQueryWrapper<StoryRollupSummaryEntity>().eq(StoryRollupSummaryEntity::getBookId, bookId)
                                .orderByAsc(StoryRollupSummaryEntity::getTierLevel, StoryRollupSummaryEntity::getSeqNo))
                .stream().map(StoryMemoryContextService::toRollupItem).toList();
        List<RagDtos.RollupVolumeItem> volumes = List.of();
        if (volumeMapper != null) {
            Map<UUID, ChapterMapper.VolumeChapterRange> ranges = chapterMapper.findVolumeChapterRanges(bookId)
                    .stream().collect(Collectors.toMap(ChapterMapper.VolumeChapterRange::getVolumeId, range -> range));
            volumes = volumeMapper.selectList(new LambdaQueryWrapper<VolumeEntity>()
                            .eq(VolumeEntity::getBookId, bookId).orderByAsc(VolumeEntity::getVolumeNo))
                    .stream().map(v -> toRollupVolumeItem(v, ranges.get(v.getId()))).toList();
        }
        return new RagDtos.InternalBookRollupsPayload(
                bookId, book.getOwnerId(), book.getTitle(), book.getGenre(), book.getSynopsis(),
                summaries, rollups, volumes, emptyIfNull(chapterSectionMapper.firstSectionSample(bookId)),
                emptyIfNull(chapterSectionMapper.latestSectionSample(bookId)), safe(book.getAutoCharter()), book.getCharterSource()
        );
    }

    @Transactional
    public RagDtos.InternalSaveRollupResponse saveRollup(UUID bookId, RagDtos.InternalSaveRollupRequest req) {
        requireSectionMemory();
        long startChapter = req.startOrder() / 1000;
        long endChapter = req.endOrder() / 1000;
        if (req.tierLevel() < 1 || req.tierLevel() > 3 || req.seqNo() < 1
                || startChapter < 1 || req.endOrder() < req.startOrder() || endChapter > Integer.MAX_VALUE
                || (req.startChapterNo() != null && req.startChapterNo() != startChapter)
                || (req.endChapterNo() != null && req.endChapterNo() != endChapter)) {
            throw BizException.badRequest("INVALID_ROLLUP_RANGE", "归并层级、序号或叙事范围无效");
        }
        bookMapper.lockForStructure(bookId);
        BookEntity book = bookService.getBookByIdUnchecked(bookId);
        if (req.volumeId() != null) {
            VolumeEntity volume = findVolume(bookId, req.volumeId());
            if (volume == null || !bookId.equals(volume.getBookId())) {
                throw BizException.notFound("VOLUME_NOT_FOUND", "卷不存在");
            }
        }
        StoryRollupSummaryEntity row = storyRollupSummaryMapper.selectOne(
                new LambdaQueryWrapper<StoryRollupSummaryEntity>()
                        .eq(StoryRollupSummaryEntity::getBookId, bookId)
                        .eq(StoryRollupSummaryEntity::getTierLevel, req.tierLevel())
                        .eq(StoryRollupSummaryEntity::getSeqNo, req.seqNo()));
        if (row != null && "READY".equals(row.getStatus())
                && row.getStartOrder() == req.startOrder() && row.getEndOrder() == req.endOrder()
                && Objects.equals(row.getVolumeId(), req.volumeId())
                && Objects.equals(row.getTitle(), safe(req.title()))
                && Objects.equals(row.getSummaryText(), safe(req.summaryText()))
                && Objects.equals(row.getOpenThreads(), safe(req.openThreads()))
                && Objects.equals(row.getSourceHash(), safe(req.sourceHash()))) {
            return rollupSavedResponse(row);
        }
        boolean insert = row == null;
        if (insert) {
            row = new StoryRollupSummaryEntity();
            row.setId(UUID.randomUUID());
            row.setBookId(bookId);
            row.setOwnerId(book.getOwnerId());
            row.setTierLevel(req.tierLevel());
            row.setSeqNo(req.seqNo());
        }
        row.setVolumeId(req.volumeId());
        row.setStartOrder(req.startOrder());
        row.setEndOrder(req.endOrder());
        row.setStartChapterNo((int) startChapter);
        row.setEndChapterNo((int) endChapter);
        row.setTitle(safe(req.title()));
        row.setSummaryText(safe(req.summaryText()));
        row.setOpenThreads(safe(req.openThreads()));
        row.setSourceHash(safe(req.sourceHash()));
        row.setStatus("READY");
        if (insert) {
            storyRollupSummaryMapper.insert(row);
        } else {
            row.setUpdatedAt(OffsetDateTime.now());
            storyRollupSummaryMapper.updateById(row);
        }
        if (req.tierLevel() < 3) {
            markStoryRollupsStale(bookId, req.startOrder(), req.endOrder(), req.tierLevel());
            rollupScheduler().afterRollupSaved(book, row);
        }
        return rollupSavedResponse(row);
    }

    private static RagDtos.InternalSaveRollupResponse rollupSavedResponse(StoryRollupSummaryEntity row) {
        return new RagDtos.InternalSaveRollupResponse(row.getId(), row.getTierLevel(), row.getSeqNo(),
                row.getStartOrder(), row.getEndOrder());
    }

    private void requireSectionMemory() {
        if (chapterSectionMapper == null || sectionSummaryMapper == null || storyRollupSummaryMapper == null) {
            throw BizException.serviceUnavailable("SECTION_MEMORY_UNAVAILABLE", "小节记忆暂不可用");
        }
    }

    private ChapterSectionEntity requireSection(UUID sectionId) {
        ChapterSectionEntity section = chapterSectionMapper.selectById(sectionId);
        if (section == null) throw BizException.notFound("SECTION_NOT_FOUND", "小节不存在");
        return section;
    }

    private ChapterEntity requireSectionChapter(ChapterSectionEntity section) {
        ChapterEntity chapter = chapterMapper.selectById(section.getChapterId());
        if (chapter == null || !section.getBookId().equals(chapter.getBookId())) {
            throw BizException.notFound("CHAPTER_NOT_FOUND", "章节不存在");
        }
        return chapter;
    }

    private static RagDtos.SectionSummaryItem toSectionSummaryItem(SectionSummaryEntity s) {
        return new RagDtos.SectionSummaryItem(s.getId(), s.getSectionId(), s.getChapterId(), s.getChapterNo(),
                s.getSectionNo(), s.getNarrativeOrder(), s.getChapterTitle(), s.getSectionTitle(), s.getContentHash(),
                s.getTimeAndPlace(), s.getSummaryText(), s.getStateChanges(), s.getClueSignals(), s.getPovNotes(),
                s.getInvolvedCharacters(), s.getEndingHook(), s.getStatus());
    }

    private static RagDtos.StoryRollupItem toRollupItem(StoryRollupSummaryEntity r) {
        return new RagDtos.StoryRollupItem(r.getId(), r.getTierLevel(), r.getSeqNo(), r.getVolumeId(),
                r.getStartOrder(), r.getEndOrder(), r.getStartChapterNo(), r.getEndChapterNo(), r.getTitle(),
                r.getSummaryText(), r.getOpenThreads(), r.getSourceHash(), r.getStatus());
    }

    private static RagDtos.RollupVolumeItem toRollupVolumeItem(VolumeEntity v, ChapterMapper.VolumeChapterRange range) {
        return new RagDtos.RollupVolumeItem(v.getId(), v.getVolumeNo(), v.getTitle(), v.getArcGoal(), v.getPacingNotes(),
                v.getRealmScope(), range == null ? v.getStartChapterNo() : range.getStartChapterNo(),
                range == null ? v.getEndChapterNo() : range.getEndChapterNo(), v.getStatus());
    }

    private static String sectionSample(String content) {
        if (content == null) return "";
        int end = Math.min(500, content.length());
        if (end < content.length() && Character.isHighSurrogate(content.charAt(end - 1))) end--;
        return content.substring(0, end);
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    @Transactional
    public RagDtos.InternalSaveChapterSummaryResponse saveChapterSummary(
            UUID bookId,
            RagDtos.InternalSaveChapterSummaryRequest req
    ) {
        BookEntity book = bookService.getBookByIdUnchecked(bookId);
        int chapterNo = req.chapterNo() != null ? req.chapterNo() : 1;
        upsertChapterSummary(book, req);

        // Clear chapter dirty flag if hashes match
        ChapterEntity chapter = chapterMapper.selectById(req.chapterId());
        if (chapter != null) {
            if (req.contentHash() == null || req.contentHash().isBlank()
                    || req.contentHash().equals(chapter.getContentHash())) {
                chapter.setSummaryDirty(false);
                chapterMapper.updateById(chapter);
            }
        }

        // Check if parent Volume Summary should be refreshed
        int volNo = volumeNoForChapter(chapterNo);
        int volStart = (volNo - 1) * CHAPTERS_PER_VOLUME + 1;
        int volEnd = volNo * CHAPTERS_PER_VOLUME;
        List<ChapterSummaryEntity> volChapters = chapterSummaryMapper.selectList(
                new LambdaQueryWrapper<ChapterSummaryEntity>()
                        .eq(ChapterSummaryEntity::getBookId, bookId)
                        .ge(ChapterSummaryEntity::getChapterNo, volStart)
                        .le(ChapterSummaryEntity::getChapterNo, volEnd)
                        .orderByAsc(ChapterSummaryEntity::getChapterNo)
        );
        VolumeSummaryEntity existingVol = volumeSummaryMapper.selectOne(
                new LambdaQueryWrapper<VolumeSummaryEntity>()
                        .eq(VolumeSummaryEntity::getBookId, bookId)
                        .eq(VolumeSummaryEntity::getVolumeNo, volNo)
        );
        int volCount = volChapters == null ? 0 : volChapters.size();
        boolean refreshVolume = false;
        if (volCount >= 3) {
            if (existingVol != null && "STALE".equals(existingVol.getStatus())) {
                refreshVolume = true;
            } else if (existingVol == null && (volCount >= CHAPTERS_PER_VOLUME || chapterNo % CHAPTERS_PER_VOLUME == 0)) {
                refreshVolume = true;
            } else if (existingVol != null && volEnd > existingVol.getEndChapterNo() && volCount >= CHAPTERS_PER_VOLUME) {
                refreshVolume = true;
            }
        }

        // Check if low-frequency Auto-Emergent L0 Charter should refresh (Ch 1, Ch 3, every +10 chapters)
        // NEVER overwrites AUTHOR_DEFINED charter!
        boolean refreshAutoCharter = false;
        int totalSummaries = Math.toIntExact(chapterSummaryMapper.selectCount(
                new LambdaQueryWrapper<ChapterSummaryEntity>().eq(ChapterSummaryEntity::getBookId, bookId)
        ));
        if (!"AUTHOR_DEFINED".equals(book.getCharterSource())) {
            int lastCount = book.getCharterChapterCount() == null ? 0 : book.getCharterChapterCount();
            if ((totalSummaries >= 1 && lastCount == 0)
                    || (totalSummaries >= 3 && lastCount < 3)
                    || (totalSummaries >= lastCount + CHAPTERS_PER_VOLUME)) {
                refreshAutoCharter = true;
            }
        }

        int actualEnd = (volChapters != null && !volChapters.isEmpty())
                ? volChapters.getLast().getChapterNo()
                : chapterNo;
        return new RagDtos.InternalSaveChapterSummaryResponse(
                true,
                refreshVolume,
                volNo,
                volStart,
                actualEnd,
                refreshAutoCharter,
                totalSummaries
        );
    }

    private void upsertChapterSummary(BookEntity book, RagDtos.InternalSaveChapterSummaryRequest req) {
        ChapterSummaryEntity summary = chapterSummaryMapper.selectOne(new LambdaQueryWrapper<ChapterSummaryEntity>()
                .eq(ChapterSummaryEntity::getChapterId, req.chapterId()));
        boolean insert = summary == null;
        if (insert) {
            summary = new ChapterSummaryEntity();
            summary.setId(UUID.randomUUID());
            summary.setBookId(book.getId());
            summary.setOwnerId(book.getOwnerId());
            summary.setChapterId(req.chapterId());
        }
        summary.setChapterNo(req.chapterNo() == null ? 1 : req.chapterNo());
        summary.setChapterTitle(safe(req.chapterTitle()));
        summary.setContentHash(safe(req.contentHash()));
        summary.setSummaryText(safe(req.summaryText()));
        summary.setKeyEvents(safe(req.keyEvents()));
        summary.setInvolvedCharacters(safe(req.involvedCharacters()));
        summary.setEndingHook(safe(req.endingHook()));
        summary.setStatus("READY");
        if (insert) {
            chapterSummaryMapper.insert(summary);
        } else {
            summary.setUpdatedAt(OffsetDateTime.now());
            chapterSummaryMapper.updateById(summary);
        }
    }

    @Transactional
    public void saveVolumeSummary(UUID bookId, RagDtos.InternalSaveVolumeSummaryRequest req) {
        BookEntity book = bookService.getBookByIdUnchecked(bookId);
        int volNo = req.volumeNo() != null ? req.volumeNo() : 1;
        VolumeSummaryEntity existing = volumeSummaryMapper.selectOne(
                new LambdaQueryWrapper<VolumeSummaryEntity>()
                        .eq(VolumeSummaryEntity::getBookId, bookId)
                        .eq(VolumeSummaryEntity::getVolumeNo, volNo)
        );
        if (existing == null) {
            VolumeSummaryEntity entity = new VolumeSummaryEntity();
            entity.setId(UUID.randomUUID());
            entity.setBookId(bookId);
            entity.setOwnerId(book.getOwnerId());
            entity.setVolumeNo(volNo);
            entity.setStartChapterNo(req.startChapterNo() != null ? req.startChapterNo() : (volNo - 1) * CHAPTERS_PER_VOLUME + 1);
            entity.setEndChapterNo(req.endChapterNo() != null ? req.endChapterNo() : volNo * CHAPTERS_PER_VOLUME);
            entity.setSummaryText(safe(req.summaryText()));
            entity.setSourceHash(safe(req.sourceHash()));
            entity.setStatus("READY");
            volumeSummaryMapper.insert(entity);
        } else {
            if (req.startChapterNo() != null) existing.setStartChapterNo(req.startChapterNo());
            if (req.endChapterNo() != null) existing.setEndChapterNo(req.endChapterNo());
            existing.setSummaryText(safe(req.summaryText()));
            existing.setSourceHash(safe(req.sourceHash()));
            existing.setStatus("READY");
            volumeSummaryMapper.updateById(existing);
        }
    }

    @Transactional
    public boolean saveAutoCharter(UUID bookId, RagDtos.InternalSaveAutoCharterRequest req) {
        BookEntity book = bookService.getBookByIdUnchecked(bookId);
        // Hard invariant: AI auto-inferred charter NEVER overwrites author-defined charter!
        if ("AUTHOR_DEFINED".equals(book.getCharterSource())) {
            return false;
        }
        String text = safe(req.autoCharter());
        if (text.isEmpty()) {
            return false;
        }
        book.setAutoCharter(text);
        book.setCharterSource("AUTO_INFERRED");
        book.setCharterChapterCount(req.chapterCount() != null ? req.chapterCount() : 1);
        bookMapper.updateById(book);
        return true;
    }

    public RagDtos.InternalBookSummariesPayload loadBookSummariesForWorker(UUID bookId) {
        BookEntity book = bookService.getBookByIdUnchecked(bookId);
        List<ChapterSummaryEntity> chSums = chapterSummaryMapper.selectList(
                new LambdaQueryWrapper<ChapterSummaryEntity>()
                        .eq(ChapterSummaryEntity::getBookId, bookId)
                        .orderByAsc(ChapterSummaryEntity::getChapterNo)
        );
        List<VolumeSummaryEntity> volSums = volumeSummaryMapper.selectList(
                new LambdaQueryWrapper<VolumeSummaryEntity>()
                        .eq(VolumeSummaryEntity::getBookId, bookId)
                        .orderByAsc(VolumeSummaryEntity::getVolumeNo)
        );
        return new RagDtos.InternalBookSummariesPayload(
                book.getId(),
                book.getOwnerId(),
                book.getTitle(),
                book.getGenre(),
                book.getSynopsis(),
                book.getCharterSource(),
                (chSums == null ? List.<ChapterSummaryEntity>of() : chSums).stream()
                        .map(s -> new RagDtos.ChapterSummaryItem(
                                s.getChapterId(),
                                s.getChapterNo(),
                                s.getChapterTitle(),
                                s.getSummaryText(),
                                s.getKeyEvents(),
                                s.getInvolvedCharacters(),
                                s.getEndingHook(),
                                s.getStatus()
                        )).toList(),
                (volSums == null ? List.<VolumeSummaryEntity>of() : volSums).stream()
                        .map(v -> new RagDtos.VolumeSummaryItem(
                                v.getVolumeNo(),
                                v.getStartChapterNo(),
                                v.getEndChapterNo(),
                                v.getSummaryText(),
                                v.getStatus()
                        )).toList()
        );
    }

    private static NovelDtos.BookCharterResponse toCharterResponse(BookEntity book) {
        return new NovelDtos.BookCharterResponse(
                book.getId(),
                book.getAuthorCharter(),
                book.getAutoCharter(),
                book.getCharterSource() == null ? "NONE" : book.getCharterSource(),
                book.getCharterChapterCount() == null ? 0 : book.getCharterChapterCount(),
                resolveEffectiveCharter(book)
        );
    }

    private static String briefFallbackExcerpt(String content, int maxChars) {
        String clean = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (clean.length() <= maxChars) {
            return clean;
        }
        int half = maxChars / 2;
        return clean.substring(0, half) + "……" + clean.substring(clean.length() - half);
    }

    private static String safe(String v) {
        return v == null ? "" : v.trim();
    }
}
