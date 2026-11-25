package studio.foliarc.biz.memory.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import studio.foliarc.biz.lore.dto.LoreDtos;
import studio.foliarc.biz.lore.service.LoreService;
import studio.foliarc.biz.memory.entity.ChapterSummaryEntity;
import studio.foliarc.biz.memory.entity.CharacterRelationEntity;
import studio.foliarc.biz.memory.entity.VolumeSummaryEntity;
import studio.foliarc.biz.memory.mapper.ChapterSummaryMapper;
import studio.foliarc.biz.memory.mapper.CharacterRelationMapper;
import studio.foliarc.biz.memory.mapper.VolumeSummaryMapper;
import studio.foliarc.biz.novel.dto.NovelDtos;
import studio.foliarc.biz.novel.entity.BookEntity;
import studio.foliarc.biz.novel.entity.ChapterEntity;
import studio.foliarc.biz.novel.mapper.BookMapper;
import studio.foliarc.biz.novel.mapper.ChapterMapper;
import studio.foliarc.biz.novel.service.BookService;
import studio.foliarc.biz.outbox.RabbitMqTopologyConfig;
import studio.foliarc.biz.outbox.service.OutboxService;
import studio.foliarc.biz.rag.dto.RagDtos;

/**
 * Phase 5.5 Hierarchical Long-Term Memory & Five-Layer Context Pyramid Engine:
 * - L0 Static Charter (author_defined > auto_inferred, low-frequency refresh to preserve KV Cache)
 * - L1 Hot Window (immediate previous chapter verbatim tail)
 * - L2 Hierarchical Rolling Summaries (10-chapter Volume Milestones + recent 10 Chapter Summaries)
 * - L3 Entity-Scoped Dynamic State (protagonists + active mentioned characters + relations + OPEN foreshadowings)
 * - Cascade dirty invalidation when historical chapters are edited
 */
@Service
public class StoryMemoryContextService {

    private static final Logger log = LoggerFactory.getLogger(StoryMemoryContextService.class);
    public static final int CHAPTERS_PER_VOLUME = 10;
    public static final int RECENT_SUMMARY_WINDOW = 10;

    private final BookService bookService;
    private final BookMapper bookMapper;
    private final ChapterMapper chapterMapper;
    private final ChapterSummaryMapper chapterSummaryMapper;
    private final VolumeSummaryMapper volumeSummaryMapper;
    private final LoreService loreService;
    private final CharacterRelationMapper relationMapper;
    private final OutboxService outboxService;

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

    public record FiveLayerContext(
            String storyCharter,
            String hotWindowContext,
            String rollingSummaryContext,
            String activeStateBrief,
            String previousChaptersCombined
    ) {}

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
     * Marks a chapter's summary and its parent volume summary as STALE on content edit,
     * and enqueues an async CHAPTER_SUMMARY event on the dedicated high-priority summary queue.
     */
    public void invalidateAndEnqueueSummary(UUID ownerId, UUID bookId, ChapterEntity chapter) {
        if (chapter == null || chapter.getContent() == null || chapter.getContent().trim().length() < 60) {
            return;
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

            String shortHash = contentHash.length() >= 12 ? contentHash.substring(0, 12) : contentHash;
            String idempotencyKey = "CHAPTER_SUMMARY:" + chapter.getId() + ":" + shortHash;
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

    /**
     * Assembles the fixed-budget 5-Layer Context Pyramid (L0 Charter, L1 Hot Window,
     * L2 Hierarchical Volume + Chapter Summaries, L3 Entity-Scoped Active State).
     * Scales to Chapter 100+ with O(1) prompt size.
     */
    public FiveLayerContext buildFiveLayerContext(
            UUID ownerId,
            BookEntity book,
            ChapterEntity currentChapter,
            String currentDraft,
            String instruction
    ) {
        // L0: Author-defined Charter > Auto-inferred Charter (stable prefix for KV Cache)
        String storyCharter = resolveEffectiveCharter(book);

        if (currentChapter == null || currentChapter.getChapterNo() == null || currentChapter.getChapterNo() <= 1) {
            String l3 = buildEntityScopedState(ownerId, book.getId(), 1, "", currentDraft, instruction, "");
            return new FiveLayerContext(storyCharter, "", "", l3, "");
        }

        int currentNo = currentChapter.getChapterNo();

        // Fetch earlier chapters metadata + content only for immediate prev & unsummarized fallbacks
        List<ChapterEntity> earlierChapters = chapterMapper.selectList(
                new LambdaQueryWrapper<ChapterEntity>()
                        .eq(ChapterEntity::getBookId, book.getId())
                        .lt(ChapterEntity::getChapterNo, currentNo)
                        .orderByAsc(ChapterEntity::getChapterNo)
        );
        List<ChapterEntity> nonEmptyEarlier = (earlierChapters == null ? List.<ChapterEntity>of() : earlierChapters)
                .stream()
                .filter(c -> c.getContent() != null && !c.getContent().isBlank())
                .toList();

        if (nonEmptyEarlier.isEmpty()) {
            String l3 = buildEntityScopedState(ownerId, book.getId(), currentNo - 1, "", currentDraft, instruction, "");
            return new FiveLayerContext(storyCharter, "", "", l3, "");
        }

        // L1 Hot Window: verbatim tail of immediately preceding non-empty chapter (N-1)
        ChapterEntity immediatePrev = nonEmptyEarlier.getLast();
        String prevBody = immediatePrev.getContent().trim();
        String hotTail = prevBody.length() <= 2400
                ? prevBody
                : "……\n" + prevBody.substring(prevBody.length() - 2200);
        String hotWindow = "【上一章（第 " + immediatePrev.getChapterNo() + " 章《" + immediatePrev.getTitle() + "》）原文衔接】\n" + hotTail;

        // L2 Hierarchical Rolling Summaries:
        // - Recent window: up to RECENT_SUMMARY_WINDOW (10) chapters before currentNo
        // - Older chapters before recent window: Volume Milestones (10 chapters per volume)
        int recentStartNo = Math.max(1, currentNo - RECENT_SUMMARY_WINDOW);

        List<ChapterSummaryEntity> storedSummaries = chapterSummaryMapper.selectList(
                new LambdaQueryWrapper<ChapterSummaryEntity>()
                        .eq(ChapterSummaryEntity::getBookId, book.getId())
                        .lt(ChapterSummaryEntity::getChapterNo, currentNo)
                        .orderByAsc(ChapterSummaryEntity::getChapterNo)
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
                        .lt(VolumeSummaryEntity::getStartChapterNo, recentStartNo)
                        .orderByAsc(VolumeSummaryEntity::getVolumeNo)
        );

        StringBuilder l2 = new StringBuilder();
        if (storedVolumes != null && !storedVolumes.isEmpty()) {
            l2.append("【前卷主线里程碑】\n");
            int coveredUntil = 0;
            for (VolumeSummaryEntity v : storedVolumes) {
                if (v.getEndChapterNo() >= recentStartNo && storedVolumes.size() > 1) {
                    // Volume overlaps recent chapter window; still include concisely for arc framing
                }
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
        if (!recentLines.isEmpty()) {
            l2.append("【近期章节摘要】\n").append(String.join("\n", recentLines));
        }

        String rollingSummary = l2.toString().trim();

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

        String combined = rollingSummary.isBlank()
                ? hotWindow
                : rollingSummary + "\n\n" + hotWindow;

        return new FiveLayerContext(storyCharter, hotWindow, rollingSummary, activeState, combined);
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

    @Transactional
    public RagDtos.InternalSaveChapterSummaryResponse saveChapterSummary(
            UUID bookId,
            RagDtos.InternalSaveChapterSummaryRequest req
    ) {
        BookEntity book = bookService.getBookByIdUnchecked(bookId);
        ChapterSummaryEntity existing = chapterSummaryMapper.selectOne(
                new LambdaQueryWrapper<ChapterSummaryEntity>()
                        .eq(ChapterSummaryEntity::getChapterId, req.chapterId())
        );

        int chapterNo = req.chapterNo() != null ? req.chapterNo() : 1;
        if (existing == null) {
            ChapterSummaryEntity entity = new ChapterSummaryEntity();
            entity.setId(UUID.randomUUID());
            entity.setBookId(bookId);
            entity.setOwnerId(book.getOwnerId());
            entity.setChapterId(req.chapterId());
            entity.setChapterNo(chapterNo);
            entity.setChapterTitle(safe(req.chapterTitle()));
            entity.setContentHash(safe(req.contentHash()));
            entity.setSummaryText(safe(req.summaryText()));
            entity.setKeyEvents(safe(req.keyEvents()));
            entity.setInvolvedCharacters(safe(req.involvedCharacters()));
            entity.setEndingHook(safe(req.endingHook()));
            entity.setStatus("READY");
            chapterSummaryMapper.insert(entity);
        } else {
            existing.setChapterNo(chapterNo);
            existing.setChapterTitle(safe(req.chapterTitle()));
            existing.setContentHash(safe(req.contentHash()));
            existing.setSummaryText(safe(req.summaryText()));
            existing.setKeyEvents(safe(req.keyEvents()));
            existing.setInvolvedCharacters(safe(req.involvedCharacters()));
            existing.setEndingHook(safe(req.endingHook()));
            existing.setStatus("READY");
            chapterSummaryMapper.updateById(existing);
        }

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
