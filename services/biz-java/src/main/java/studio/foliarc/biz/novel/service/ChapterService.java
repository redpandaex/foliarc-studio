package studio.foliarc.biz.novel.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.memory.service.StoryMemoryContextService;
import studio.foliarc.biz.novel.dto.NovelDtos;
import studio.foliarc.biz.novel.entity.ChapterEntity;
import studio.foliarc.biz.novel.entity.ChapterSectionEntity;
import studio.foliarc.biz.novel.entity.ChapterVersionEntity;
import studio.foliarc.biz.novel.mapper.ChapterMapper;
import studio.foliarc.biz.novel.mapper.ChapterSectionMapper;
import studio.foliarc.biz.novel.mapper.ChapterVersionMapper;
import studio.foliarc.biz.novel.mapper.VolumeMapper;
import studio.foliarc.biz.rag.service.KnowledgeDocumentService;
import studio.foliarc.biz.vip.service.VipEntitlementService;

@Service
public class ChapterService {

    private static final Set<String> CHAPTER_STATES = Set.of("DRAFT", "REVIEWING", "PUBLISHED");

    private final BookService bookService;
    private final ChapterMapper chapterMapper;
    private final ChapterVersionMapper versionMapper;
    private final KnowledgeDocumentService knowledgeDocumentService;
    private final StoryMemoryContextService storyMemoryContextService;
    private ChapterSectionMapper chapterSectionMapper;
    private VolumeMapper volumeMapper;
    private VipEntitlementService vipEntitlementService;

    public ChapterService(
            BookService bookService,
            ChapterMapper chapterMapper,
            ChapterVersionMapper versionMapper,
            KnowledgeDocumentService knowledgeDocumentService
    ) {
        this(bookService, chapterMapper, versionMapper, knowledgeDocumentService, null);
    }

    @Autowired
    public ChapterService(
            BookService bookService,
            ChapterMapper chapterMapper,
            ChapterVersionMapper versionMapper,
            KnowledgeDocumentService knowledgeDocumentService,
            StoryMemoryContextService storyMemoryContextService
    ) {
        this.bookService = bookService;
        this.chapterMapper = chapterMapper;
        this.versionMapper = versionMapper;
        this.knowledgeDocumentService = knowledgeDocumentService;
        this.storyMemoryContextService = storyMemoryContextService;
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
    public void setVipEntitlementService(VipEntitlementService vipEntitlementService) {
        this.vipEntitlementService = vipEntitlementService;
    }

    public List<NovelDtos.ChapterSummaryResponse> listChapters(UUID ownerId, UUID bookId) {
        bookService.requireOwnedBook(ownerId, bookId);
        var query = new LambdaQueryWrapper<ChapterEntity>()
                .select(
                        ChapterEntity::getId,
                        ChapterEntity::getBookId,
                        ChapterEntity::getChapterNo,
                        ChapterEntity::getTitle,
                        ChapterEntity::getWordCount,
                        ChapterEntity::getStatus,
                        ChapterEntity::getVersion,
                        ChapterEntity::getCreatedAt,
                        ChapterEntity::getUpdatedAt
                )
                .eq(ChapterEntity::getBookId, bookId)
                .orderByAsc(ChapterEntity::getChapterNo);
        return chapterMapper.selectList(query).stream().map(this::mapSummary).toList();
    }

    @Transactional
    public NovelDtos.ChapterDetailResponse createChapter(
            UUID ownerId,
            UUID bookId,
            NovelDtos.CreateChapterRequest input
    ) {
        bookService.requireOwnedBook(ownerId, bookId);
        String text = input.content() == null ? "" : input.content();
        if (chapterSectionMapper != null || vipEntitlementService != null) {
            StructureService.checkSectionSize(vipEntitlementService, ownerId, text);
        }
        if (chapterSectionMapper != null) {
            bookService.lockStructure(bookId);
        }
        int seq = input.chapterNo() != null ? input.chapterNo() : chapterMapper.findMaxChapterNo(bookId) + 1;

        ChapterEntity entity = new ChapterEntity();
        entity.setId(UUID.randomUUID());
        entity.setBookId(bookId);
        entity.setChapterNo(seq);
        entity.setTitle(input.title().trim());
        entity.setContent(text);
        entity.setWordCount(WordCounter.count(text));
        entity.setStatus("DRAFT");
        entity.setContentHash(StoryMemoryContextService.sha256Hex(text));
        entity.setSummaryDirty(!text.isBlank());
        entity.setVersion(0L);
        entity.setStructureMode(StructureService.SINGLE);
        if (chapterSectionMapper != null && volumeMapper != null) {
            entity.setVolumeId(StructureService.ensureDefaultVolume(volumeMapper, ownerId, bookId).getId());
        }
        chapterMapper.insert(entity);
        if (chapterSectionMapper != null) {
            chapterSectionMapper.insert(StructureService.defaultSection(entity));
        }

        if (!text.isBlank()) {
            saveSnapshotRow(entity.getId(), text, "初始草稿", ownerId);
        }

        // Background auto-sync: when creating a new chapter (e.g. Chapter 2), ensure the immediately
        // preceding chapter (e.g. Chapter 1) is indexed into RAG + structured memory + hierarchical summary.
        if (seq > 1) {
            ChapterEntity prev = chapterMapper.selectOne(
                    new LambdaQueryWrapper<ChapterEntity>()
                            .eq(ChapterEntity::getBookId, bookId)
                            .lt(ChapterEntity::getChapterNo, seq)
                            .orderByDesc(ChapterEntity::getChapterNo)
                            .last("LIMIT 1")
            );
            if (prev != null) {
                knowledgeDocumentService.syncChapterInBackgroundIfNeeded(ownerId, bookId, prev);
                if (storyMemoryContextService != null) {
                    storyMemoryContextService.invalidateAndEnqueueSummary(ownerId, bookId, prev);
                }
            }
        }
        return mapDetail(entity);
    }

    public NovelDtos.ChapterDetailResponse getChapter(UUID ownerId, UUID bookId, UUID chapterId) {
        ChapterEntity chapter = loadOwnedChapter(ownerId, bookId, chapterId);
        return mapDetail(chapter, legacyContent(chapter));
    }

    @Transactional
    public NovelDtos.ChapterDetailResponse updateChapter(
            UUID ownerId,
            UUID bookId,
            UUID chapterId,
            NovelDtos.UpdateChapterRequest input
    ) {
        ChapterEntity row = lockOwnedChapter(ownerId, bookId, chapterId);
        String targetState = (input.status() == null || input.status().isBlank())
                ? row.getStatus()
                : input.status().trim().toUpperCase(Locale.ROOT);
        if (!CHAPTER_STATES.contains(targetState)) {
            throw BizException.badRequest("INVALID_CHAPTER_STATUS", "不支持的章节状态: " + targetState);
        }

        if (chapterSectionMapper != null || vipEntitlementService != null) {
            StructureService.checkSectionSize(vipEntitlementService, ownerId, input.content());
        }
        List<ChapterSectionEntity> sections = loadSections(chapterId);
        ChapterSectionEntity primary = primarySection(sections);
        StructureService.checkChapterSize(sections, primary == null ? null : primary.getId(), input.content());

        int newWordCount = WordCounter.count(input.content());
        String oldHash = primary == null ? row.getContentHash() : primary.getContentHash();
        String newHash = StoryMemoryContextService.sha256Hex(input.content());
        boolean contentChanged = !newHash.equals(oldHash);

        row.setTitle(input.title().trim());
        row.setContent(input.content());
        row.setWordCount(newWordCount + otherSectionWords(sections, primary));
        row.setStatus(targetState);
        row.setContentHash(newHash);
        if (contentChanged) {
            row.setSummaryDirty(true);
        }
        row.setVersion(input.version());

        if (chapterMapper.updateById(row) == 0) {
            throw BizException.conflict("VERSION_CONFLICT", "章节已被其他窗口更新，请刷新最新版本后重试");
        }
        syncDefaultSection(row, primary, contentChanged);

        if (Boolean.TRUE.equals(input.createSnapshot())) {
            String note = (input.changeSummary() == null || input.changeSummary().isBlank())
                    ? "保存并创建快照"
                    : input.changeSummary().trim();
            saveSnapshotRow(chapterId, input.content(), note, ownerId);
        }

        boolean summarize = contentChanged || "REVIEWING".equals(targetState) || "PUBLISHED".equals(targetState);
        if (storyMemoryContextService != null && summarize) {
            storyMemoryContextService.invalidateAndEnqueueSummary(ownerId, bookId, row);
        }
        if ("PUBLISHED".equals(targetState) && input.content() != null && !input.content().isBlank()) {
            knowledgeDocumentService.syncChapter(ownerId, bookId, chapterId, chapterSectionMapper == null);
        } else if ("REVIEWING".equals(targetState) || contentChanged) {
            knowledgeDocumentService.syncChapterInBackgroundIfNeeded(ownerId, bookId, row);
        }

        ChapterEntity latest = chapterMapper.selectById(chapterId);
        return mapDetail(latest != null ? latest : row);
    }

    @Transactional
    public void deleteChapter(UUID ownerId, UUID bookId, UUID chapterId) {
        ChapterEntity row = lockOwnedChapter(ownerId, bookId, chapterId);
        knowledgeDocumentService.deleteBySourceChapter(ownerId, bookId, chapterId);
        if (storyMemoryContextService != null) {
            storyMemoryContextService.markRollupsStaleForChapter(bookId, row.getChapterNo());
        }
        chapterMapper.deleteById(chapterId);
    }

    public List<NovelDtos.ChapterVersionResponse> listVersions(UUID ownerId, UUID bookId, UUID chapterId) {
        loadOwnedChapter(ownerId, bookId, chapterId);
        var query = new LambdaQueryWrapper<ChapterVersionEntity>()
                .eq(ChapterVersionEntity::getChapterId, chapterId)
                .orderByDesc(ChapterVersionEntity::getVersionNo);
        return versionMapper.selectList(query).stream().map(this::mapVersion).toList();
    }

    @Transactional
    public NovelDtos.ChapterVersionResponse createSnapshot(
            UUID ownerId,
            UUID bookId,
            UUID chapterId,
            NovelDtos.CreateSnapshotRequest input
    ) {
        ChapterEntity row = lockOwnedChapter(ownerId, bookId, chapterId);
        String note = (input == null || input.changeSummary() == null || input.changeSummary().isBlank())
                ? "手动快照"
                : input.changeSummary().trim();
        return mapVersion(saveSnapshotRow(chapterId, legacyContent(row), note, ownerId));
    }

    @Transactional
    public NovelDtos.ChapterDetailResponse rollbackToVersion(
            UUID ownerId,
            UUID bookId,
            UUID chapterId,
            UUID versionId,
            NovelDtos.RollbackChapterRequest input
    ) {
        ChapterEntity row = lockOwnedChapter(ownerId, bookId, chapterId);
        ChapterVersionEntity target = versionMapper.selectById(versionId);
        if (target == null || !chapterId.equals(target.getChapterId())) {
            throw BizException.notFound("CHAPTER_VERSION_NOT_FOUND", "历史快照不存在");
        }

        List<ChapterSectionEntity> sections = loadSections(chapterId);
        ChapterSectionEntity primary = primarySection(sections);
        StructureService.checkChapterSize(sections, primary == null ? null : primary.getId(), target.getContent());
        row.setContent(target.getContent());
        row.setWordCount(WordCounter.count(target.getContent()) + otherSectionWords(sections, primary));
        row.setContentHash(StoryMemoryContextService.sha256Hex(target.getContent()));
        row.setSummaryDirty(true);
        row.setVersion(input.version());
        if (chapterMapper.updateById(row) == 0) {
            throw BizException.conflict("VERSION_CONFLICT", "章节版本已变更，请刷新后再回滚");
        }

        syncDefaultSection(row, primary, true);
        saveSnapshotRow(chapterId, target.getContent(), "回滚至 v" + target.getVersionNo(), ownerId);
        if (storyMemoryContextService != null) {
            storyMemoryContextService.invalidateAndEnqueueSummary(ownerId, bookId, row);
        }
        knowledgeDocumentService.syncChapterInBackgroundIfNeeded(ownerId, bookId, row);
        ChapterEntity latest = chapterMapper.selectById(chapterId);
        return mapDetail(latest != null ? latest : row);
    }

    private ChapterEntity loadOwnedChapter(UUID ownerId, UUID bookId, UUID chapterId) {
        bookService.requireOwnedBook(ownerId, bookId);
        return requireChapter(bookId, chapterId);
    }

    private ChapterEntity lockOwnedChapter(UUID ownerId, UUID bookId, UUID chapterId) {
        bookService.requireOwnedBook(ownerId, bookId);
        if (chapterSectionMapper != null) {
            chapterMapper.lockForStructure(bookId, chapterId);
        }
        return requireChapter(bookId, chapterId);
    }

    private ChapterEntity requireChapter(UUID bookId, UUID chapterId) {
        ChapterEntity row = chapterMapper.selectById(chapterId);
        if (row == null || !bookId.equals(row.getBookId())) {
            throw BizException.notFound("CHAPTER_NOT_FOUND", "章节不存在");
        }
        return row;
    }

    private List<ChapterSectionEntity> loadSections(UUID chapterId) {
        return chapterSectionMapper == null ? List.of() : chapterSectionMapper.selectList(
                ChapterSectionMapper.inChapter(chapterId).orderByAsc(ChapterSectionEntity::getSectionNo));
    }

    private static ChapterSectionEntity primarySection(List<ChapterSectionEntity> sections) {
        return sections.stream().filter(s -> Boolean.TRUE.equals(s.getIsDefault())).findFirst()
                .orElse(sections.isEmpty() ? null : sections.getFirst());
    }

    private String legacyContent(ChapterEntity chapter) {
        if (chapterSectionMapper == null || !StructureService.MULTI_SECTION.equals(chapter.getStructureMode())) {
            return chapter.getContent();
        }
        ChapterSectionEntity primary = chapterSectionMapper.selectOne(ChapterSectionMapper.inChapter(chapter.getId())
                .select(ChapterSectionEntity::getContent)
                .orderByDesc(ChapterSectionEntity::getIsDefault)
                .orderByAsc(ChapterSectionEntity::getSectionNo)
                .last("LIMIT 1"));
        return primary == null ? chapter.getContent() : primary.getContent();
    }

    private static int otherSectionWords(List<ChapterSectionEntity> sections, ChapterSectionEntity primary) {
        return sections.stream().filter(s -> s != primary).mapToInt(ChapterSectionEntity::getWordCount).sum();
    }

    private void syncDefaultSection(ChapterEntity chapter, ChapterSectionEntity primary, boolean dirty) {
        if (chapterSectionMapper == null) {
            return;
        }
        if (primary == null) {
            chapterSectionMapper.insert(StructureService.defaultSection(chapter));
            return;
        }
        if (!dirty && chapter.getContent().equals(primary.getContent())) {
            return;
        }
        primary.setContent(chapter.getContent());
        primary.setContentHash(chapter.getContentHash());
        primary.setWordCount(WordCounter.count(chapter.getContent()));
        if (dirty) {
            primary.setSummaryDirty(true);
        }
        primary.setUpdatedAt(OffsetDateTime.now());
        if (chapterSectionMapper.updateById(primary) == 0) {
            throw BizException.conflict("VERSION_CONFLICT", "默认小节已被更新，请刷新后重试");
        }
    }

    private ChapterVersionEntity saveSnapshotRow(UUID chapterId, String content, String note, UUID actorId) {
        ChapterVersionEntity ver = new ChapterVersionEntity();
        ver.setId(UUID.randomUUID());
        ver.setChapterId(chapterId);
        ver.setVersionNo(versionMapper.findMaxVersionNo(chapterId) + 1);
        ver.setContent(content);
        ver.setChangeSummary(note);
        ver.setCreatedBy(actorId);
        versionMapper.insert(ver);
        return ver;
    }

    private NovelDtos.ChapterSummaryResponse mapSummary(ChapterEntity c) {
        return new NovelDtos.ChapterSummaryResponse(
                c.getId(), c.getBookId(), c.getChapterNo(), c.getTitle(),
                c.getWordCount(), c.getStatus(), c.getVersion(), c.getCreatedAt(), c.getUpdatedAt()
        );
    }

    private NovelDtos.ChapterDetailResponse mapDetail(ChapterEntity c) {
        return mapDetail(c, c.getContent());
    }

    private NovelDtos.ChapterDetailResponse mapDetail(ChapterEntity c, String content) {
        return new NovelDtos.ChapterDetailResponse(
                c.getId(), c.getBookId(), c.getChapterNo(), c.getTitle(), content,
                c.getWordCount(), c.getStatus(), c.getVersion(), c.getCreatedAt(), c.getUpdatedAt()
        );
    }

    private NovelDtos.ChapterVersionResponse mapVersion(ChapterVersionEntity v) {
        return new NovelDtos.ChapterVersionResponse(
                v.getId(), v.getChapterId(), v.getVersionNo(),
                v.getContent(), v.getChangeSummary(), v.getCreatedBy(), v.getCreatedAt()
        );
    }
}
