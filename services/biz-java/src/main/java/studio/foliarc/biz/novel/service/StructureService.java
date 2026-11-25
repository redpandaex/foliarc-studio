package studio.foliarc.biz.novel.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.memory.service.StoryMemoryContextService;
import studio.foliarc.biz.novel.dto.StructureDtos;
import studio.foliarc.biz.novel.entity.ChapterEntity;
import studio.foliarc.biz.novel.entity.ChapterSectionEntity;
import studio.foliarc.biz.novel.entity.SectionVersionEntity;
import studio.foliarc.biz.novel.entity.VolumeEntity;
import studio.foliarc.biz.novel.mapper.ChapterMapper;
import studio.foliarc.biz.novel.mapper.ChapterSectionMapper;
import studio.foliarc.biz.novel.mapper.SectionVersionMapper;
import studio.foliarc.biz.novel.mapper.VolumeMapper;
import studio.foliarc.biz.rag.service.KnowledgeDocumentService;
import studio.foliarc.biz.vip.service.VipEntitlementService;

@Service
public class StructureService {

    public static final String SINGLE = "SINGLE";
    public static final String MULTI_SECTION = "MULTI_SECTION";
    private static final int MAX_SECTIONS_PER_CHAPTER = 999;
    private static final int MAX_SECTION_CHARS = 100_000;
    private static final int MAX_CHAPTER_CHARS = 200_000;
    private static final String DEFAULT_VOLUME_TITLE = "第一卷";
    private static final Set<String> VOLUME_STATES = Set.of("PLANNING", "WRITING", "COMPLETED");

    private final BookService bookService;
    private final VolumeMapper volumeMapper;
    private final ChapterMapper chapterMapper;
    private final ChapterSectionMapper chapterSectionMapper;
    private final SectionVersionMapper sectionVersionMapper;
    private final StoryMemoryContextService storyMemoryContextService;
    private final KnowledgeDocumentService knowledgeDocumentService;
    private VipEntitlementService vipEntitlementService;

    public StructureService(
            BookService bookService, VolumeMapper volumeMapper, ChapterMapper chapterMapper,
            ChapterSectionMapper chapterSectionMapper, SectionVersionMapper sectionVersionMapper,
            StoryMemoryContextService storyMemoryContextService, KnowledgeDocumentService knowledgeDocumentService
    ) {
        this.bookService = bookService;
        this.volumeMapper = volumeMapper;
        this.chapterMapper = chapterMapper;
        this.chapterSectionMapper = chapterSectionMapper;
        this.sectionVersionMapper = sectionVersionMapper;
        this.storyMemoryContextService = storyMemoryContextService;
        this.knowledgeDocumentService = knowledgeDocumentService;
    }

    @Autowired(required = false)
    public void setVipEntitlementService(VipEntitlementService vipEntitlementService) {
        this.vipEntitlementService = vipEntitlementService;
    }

    public static long narrativeOrder(int chapterNo, int sectionNo) {
        return (long) chapterNo * 1000L + sectionNo;
    }

    public StructureDtos.BookTreeResponse getTree(UUID ownerId, UUID bookId) {
        var book = bookService.requireOwnedBook(ownerId, bookId);
        List<VolumeEntity> volumes = volumeMapper.selectList(new LambdaQueryWrapper<VolumeEntity>()
                .eq(VolumeEntity::getBookId, bookId).orderByAsc(VolumeEntity::getVolumeNo));
        List<ChapterEntity> chapters = chapterMapper.selectList(new LambdaQueryWrapper<ChapterEntity>()
                .select(ChapterEntity::getId, ChapterEntity::getChapterNo, ChapterEntity::getVolumeId,
                        ChapterEntity::getTitle, ChapterEntity::getStructureMode, ChapterEntity::getWordCount,
                        ChapterEntity::getStatus, ChapterEntity::getVersion)
                .eq(ChapterEntity::getBookId, bookId).orderByAsc(ChapterEntity::getChapterNo));
        List<ChapterSectionEntity> sections = chapterSectionMapper.selectList(new LambdaQueryWrapper<ChapterSectionEntity>()
                .select(ChapterSectionEntity::getId, ChapterSectionEntity::getChapterId, ChapterSectionEntity::getChapterNo,
                        ChapterSectionEntity::getSectionNo, ChapterSectionEntity::getNarrativeOrder,
                        ChapterSectionEntity::getTitle, ChapterSectionEntity::getWordCount,
                        ChapterSectionEntity::getIsDefault, ChapterSectionEntity::getSummaryDirty, ChapterSectionEntity::getVersion)
                .eq(ChapterSectionEntity::getBookId, bookId).orderByAsc(ChapterSectionEntity::getNarrativeOrder));

        Map<UUID, List<ChapterSectionEntity>> sectionsByChapter = sections.stream()
                .collect(Collectors.groupingBy(ChapterSectionEntity::getChapterId));
        Map<UUID, VolumeEntity> volumesById = volumes.stream().collect(Collectors.toMap(VolumeEntity::getId, v -> v));
        VolumeEntity firstVolume = volumes.stream().filter(v -> v.getVolumeNo() == 1).findFirst().orElse(null);
        Map<UUID, List<StructureDtos.ChapterTreeNode>> chaptersByVolume = new HashMap<>();
        List<StructureDtos.ChapterTreeNode> unassigned = new ArrayList<>();
        long totalUnits = 0;
        for (ChapterEntity chapter : chapters) {
            List<ChapterSectionEntity> chapterSections = sectionsByChapter.getOrDefault(chapter.getId(), List.of());
            totalUnits += Math.max(1, chapterSections.size());
            var node = toChapterNode(chapter, chapterSections);
            VolumeEntity volume = volumesById.getOrDefault(chapter.getVolumeId(), firstVolume);
            if (volume == null) {
                unassigned.add(node);
            } else {
                chaptersByVolume.computeIfAbsent(volume.getId(), id -> new ArrayList<>()).add(node);
            }
        }

        List<StructureDtos.VolumeTreeNode> nodes = new ArrayList<>();
        if (!unassigned.isEmpty() || volumes.isEmpty()) {
            nodes.add(new StructureDtos.VolumeTreeNode(null, 1, DEFAULT_VOLUME_TITLE, "", "", "", "WRITING", unassigned));
        }
        for (VolumeEntity volume : volumes) {
            nodes.add(new StructureDtos.VolumeTreeNode(
                    volume.getId(), volume.getVolumeNo(), volume.getTitle(), volume.getArcGoal(), volume.getPacingNotes(),
                    volume.getRealmScope(), volume.getStatus(), chaptersByVolume.getOrDefault(volume.getId(), List.of())
            ));
        }
        return new StructureDtos.BookTreeResponse(
                bookId, StoryMemoryContextService.configuredPrecisionMode(book),
                StoryMemoryContextService.determinePrecisionMode(book, totalUnits), totalUnits, nodes
        );
    }

    @Transactional
    public StructureDtos.VolumeResponse createVolume(UUID ownerId, UUID bookId, StructureDtos.CreateVolumeRequest input) {
        bookService.requireOwnedBook(ownerId, bookId);
        bookService.lockStructure(bookId);
        VolumeEntity volume = newVolume(ownerId, bookId, volumeMapper.findMaxVolumeNo(bookId) + 1, clean(input.title()));
        volume.setArcGoal(clean(input.arcGoal()));
        volume.setPacingNotes(clean(input.pacingNotes()));
        volume.setRealmScope(clean(input.realmScope()));
        volumeMapper.insert(volume);
        return toVolumeResponse(volume);
    }

    @Transactional
    public StructureDtos.VolumeResponse updateVolume(
            UUID ownerId, UUID bookId, UUID volumeId, StructureDtos.UpdateVolumeRequest input
    ) {
        bookService.requireOwnedBook(ownerId, bookId);
        bookService.lockStructure(bookId);
        VolumeEntity volume = requireVolume(bookId, volumeId);
        if (input.title() != null) volume.setTitle(clean(input.title()));
        if (input.arcGoal() != null) volume.setArcGoal(clean(input.arcGoal()));
        if (input.pacingNotes() != null) volume.setPacingNotes(clean(input.pacingNotes()));
        if (input.realmScope() != null) volume.setRealmScope(clean(input.realmScope()));
        if (input.status() != null) {
            String status = clean(input.status()).toUpperCase(Locale.ROOT);
            if (!VOLUME_STATES.contains(status)) {
                throw BizException.badRequest("INVALID_VOLUME_STATUS", "不支持的卷状态: " + status);
            }
            volume.setStatus(status);
        }
        volume.setUpdatedAt(OffsetDateTime.now());
        volumeMapper.updateById(volume);
        return toVolumeResponse(volume);
    }

    @Transactional
    public void deleteVolume(UUID ownerId, UUID bookId, UUID volumeId) {
        bookService.requireOwnedBook(ownerId, bookId);
        bookService.lockStructure(bookId);
        requireVolume(bookId, volumeId);
        volumeMapper.deleteById(volumeId); // The volume FK detaches chapters.
    }

    @Transactional
    public StructureDtos.ChapterTreeNode moveChapter(
            UUID ownerId, UUID bookId, UUID chapterId, StructureDtos.MoveChapterRequest input
    ) {
        bookService.requireOwnedBook(ownerId, bookId);
        bookService.lockStructure(bookId);
        if (input.volumeId() != null) requireVolume(bookId, input.volumeId());
        chapterMapper.lockForStructure(bookId, chapterId);
        ChapterEntity chapter = requireChapter(bookId, chapterId);
        chapterMapper.update(null, new LambdaUpdateWrapper<ChapterEntity>()
                .eq(ChapterEntity::getId, chapterId)
                .set(ChapterEntity::getVolumeId, input.volumeId())
                .set(ChapterEntity::getUpdatedAt, OffsetDateTime.now()));
        chapter.setVolumeId(input.volumeId());
        return toChapterNode(chapter, listSections(chapterId));
    }

    @Transactional
    public StructureDtos.SectionDetailResponse createSection(
            UUID ownerId, UUID bookId, UUID chapterId, StructureDtos.CreateSectionRequest input
    ) {
        ChapterEntity chapter = lockOwnedChapter(ownerId, bookId, chapterId);
        List<ChapterSectionEntity> sections = new ArrayList<>(listSections(chapterId));
        boolean needsDefault = sections.isEmpty();
        if (needsDefault) {
            sections.add(defaultSection(chapter));
        }
        int sectionNo = needsDefault ? 2 : chapterSectionMapper.findMaxSectionNo(chapterId) + 1;
        if (sectionNo > MAX_SECTIONS_PER_CHAPTER) {
            throw BizException.badRequest("SECTION_LIMIT_EXCEEDED", "每章最多支持 999 个小节");
        }
        String content = Objects.requireNonNullElse(input.content(), "");
        checkSectionSize(vipEntitlementService, ownerId, content);
        checkChapterSize(sections, null, content);

        if (needsDefault) {
            chapterSectionMapper.insert(sections.getFirst());
        }
        ChapterSectionEntity section = newSection(chapter, sectionNo, clean(input.title()), content, false);
        chapterSectionMapper.insert(section);
        sections.add(section);
        updateChapterAfterSectionChange(chapter, sections);

        if (!content.isBlank()) {
            saveSnapshot(section, ownerId, "初始草稿");
            if (content.length() >= 60) {
                storyMemoryContextService.invalidateAndEnqueueSectionDistill(ownerId, bookId, section);
            }
            syncChapterIndex(ownerId, chapter, sections);
        }
        return toSectionDetail(section);
    }

    public StructureDtos.SectionDetailResponse getSection(UUID ownerId, UUID bookId, UUID chapterId, UUID sectionId) {
        bookService.requireOwnedBook(ownerId, bookId);
        requireChapter(bookId, chapterId);
        ChapterSectionEntity section = chapterSectionMapper.selectById(sectionId);
        if (section == null || !chapterId.equals(section.getChapterId()) || !bookId.equals(section.getBookId())) {
            throw sectionNotFound();
        }
        return toSectionDetail(section);
    }

    @Transactional
    public StructureDtos.SectionDetailResponse updateSection(
            UUID ownerId, UUID bookId, UUID chapterId, UUID sectionId, StructureDtos.UpdateSectionRequest input
    ) {
        ChapterEntity chapter = lockOwnedChapter(ownerId, bookId, chapterId);
        List<ChapterSectionEntity> sections = listSections(chapterId);
        ChapterSectionEntity section = findSection(sections, sectionId);
        checkSectionSize(vipEntitlementService, ownerId, input.content());
        checkChapterSize(sections, sectionId, input.content());

        boolean rawContentChanged = !input.content().equals(section.getContent());
        String hash = StoryMemoryContextService.sha256Hex(input.content());
        boolean contentChanged = !hash.equals(section.getContentHash());
        if (input.title() != null) section.setTitle(clean(input.title()));
        section.setContent(input.content());
        section.setWordCount(WordCounter.count(input.content()));
        section.setContentHash(hash);
        if (contentChanged) section.setSummaryDirty(true);
        section.setUpdatedAt(OffsetDateTime.now());
        section.setVersion(input.version());
        if (chapterSectionMapper.updateById(section) == 0) {
            throw BizException.conflict("VERSION_CONFLICT", "小节已被其他窗口更新，请刷新最新版本后重试");
        }
        updateChapterAfterSectionChange(chapter, sections);

        if (Boolean.TRUE.equals(input.createSnapshot())) {
            String note = clean(input.changeSummary());
            saveSnapshot(section, ownerId, note.isEmpty() ? "保存并创建快照" : note);
        }
        if (contentChanged) {
            storyMemoryContextService.invalidateAndEnqueueSectionDistill(ownerId, bookId, section);
        }
        if (rawContentChanged) {
            syncChapterIndex(ownerId, chapter, sections);
        }
        return toSectionDetail(section);
    }

    @Transactional
    public void deleteSection(UUID ownerId, UUID bookId, UUID chapterId, UUID sectionId) {
        ChapterEntity chapter = lockOwnedChapter(ownerId, bookId, chapterId);
        List<ChapterSectionEntity> sections = new ArrayList<>(listSections(chapterId));
        ChapterSectionEntity removed = findSection(sections, sectionId);
        if (sections.size() == 1) {
            throw BizException.badRequest("CANNOT_DELETE_LAST_SECTION", "不能删除本章唯一的小节");
        }

        chapterSectionMapper.deleteById(sectionId);
        sections.remove(removed);
        if ((sections.size() == 1 || Boolean.TRUE.equals(removed.getIsDefault()))
                && sections.stream().noneMatch(s -> Boolean.TRUE.equals(s.getIsDefault()))) {
            ChapterSectionEntity first = sections.getFirst();
            first.setIsDefault(true);
            first.setUpdatedAt(OffsetDateTime.now());
            chapterSectionMapper.updateById(first);
            chapter.setContent(first.getContent());
            chapter.setContentHash(first.getContentHash());
            chapter.setSummaryDirty(first.getSummaryDirty());
        }
        updateChapterAfterSectionChange(chapter, sections);
        storyMemoryContextService.markRollupsStaleForSection(bookId, removed);
        syncChapterIndex(ownerId, chapter, sections);
    }

    public StructureDtos.FullChapterPreviewResponse fullPreview(UUID ownerId, UUID bookId, UUID chapterId) {
        bookService.requireOwnedBook(ownerId, bookId);
        ChapterEntity chapter = requireChapter(bookId, chapterId);
        List<ChapterSectionEntity> sections = listSections(chapterId);
        if (sections.isEmpty()) {
            return new StructureDtos.FullChapterPreviewResponse(chapterId, chapter.getChapterNo(), chapter.getTitle(),
                    SINGLE, chapter.getWordCount(), 1, chapter.getContent());
        }
        return new StructureDtos.FullChapterPreviewResponse(chapterId, chapter.getChapterNo(), chapter.getTitle(),
                structureMode(chapter), totalWordCount(sections), sections.size(), combineSections(sections));
    }

    static VolumeEntity ensureDefaultVolume(VolumeMapper mapper, UUID ownerId, UUID bookId) {
        VolumeEntity volume = mapper.selectOne(new LambdaQueryWrapper<VolumeEntity>()
                .eq(VolumeEntity::getBookId, bookId).eq(VolumeEntity::getVolumeNo, 1));
        if (volume == null) {
            volume = newVolume(ownerId, bookId, 1, DEFAULT_VOLUME_TITLE);
            mapper.insert(volume);
        }
        return volume;
    }

    static ChapterSectionEntity defaultSection(ChapterEntity chapter) {
        ChapterSectionEntity section = newSection(chapter, 1, chapter.getTitle(), chapter.getContent(), true);
        section.setSummaryDirty(chapter.getSummaryDirty() == null ? !section.getContent().isBlank() : chapter.getSummaryDirty());
        return section;
    }

    static void checkSectionSize(VipEntitlementService vip, UUID ownerId, String content) {
        var resolved = vip == null ? null : vip.resolveForUser(ownerId);
        int maxChars = resolved == null ? MAX_SECTION_CHARS : Math.min(MAX_SECTION_CHARS, resolved.entitlements().maxSectionChars());
        if (content.length() > maxChars) {
            String message = "小节正文最多 " + maxChars + " 个字符";
            if (resolved != null) {
                throw vip.quotaExceeded(resolved, "SECTION_CHARS", maxChars, content.length(), message);
            }
            throw BizException.badRequest("SECTION_SIZE_EXCEEDED", message);
        }
    }

    static void checkChapterSize(List<ChapterSectionEntity> sections, UUID replacedId, String content) {
        long totalChars = content.length() + sections.stream()
                .filter(s -> !Objects.equals(s.getId(), replacedId))
                .mapToLong(s -> s.getContent() == null ? 0 : s.getContent().length())
                .sum();
        if (totalChars > MAX_CHAPTER_CHARS) {
            throw BizException.badRequest("CHAPTER_SIZE_EXCEEDED", "整章正文累计最多 200000 个字符");
        }
    }

    public static String combineSections(List<ChapterSectionEntity> sections) {
        return sections.stream().map(s -> Objects.requireNonNullElse(s.getContent(), "")).collect(Collectors.joining("\n\n"));
    }

    private List<ChapterSectionEntity> listSections(UUID chapterId) {
        return chapterSectionMapper.selectList(ChapterSectionMapper.inChapter(chapterId)
                .orderByAsc(ChapterSectionEntity::getSectionNo));
    }

    private ChapterEntity lockOwnedChapter(UUID ownerId, UUID bookId, UUID chapterId) {
        bookService.requireOwnedBook(ownerId, bookId);
        chapterMapper.lockForStructure(bookId, chapterId);
        return requireChapter(bookId, chapterId);
    }

    private ChapterEntity requireChapter(UUID bookId, UUID chapterId) {
        ChapterEntity chapter = chapterMapper.selectById(chapterId);
        if (chapter == null || !bookId.equals(chapter.getBookId())) {
            throw BizException.notFound("CHAPTER_NOT_FOUND", "章节不存在");
        }
        return chapter;
    }

    private VolumeEntity requireVolume(UUID bookId, UUID volumeId) {
        VolumeEntity volume = volumeMapper.selectById(volumeId);
        if (volume == null || !bookId.equals(volume.getBookId())) {
            throw BizException.notFound("VOLUME_NOT_FOUND", "卷不存在");
        }
        return volume;
    }

    private static ChapterSectionEntity findSection(List<ChapterSectionEntity> sections, UUID sectionId) {
        return sections.stream().filter(s -> sectionId.equals(s.getId())).findFirst()
                .orElseThrow(StructureService::sectionNotFound);
    }

    private void updateChapterAfterSectionChange(ChapterEntity chapter, List<ChapterSectionEntity> sections) {
        boolean single = sections.size() == 1;
        chapter.setStructureMode(single ? SINGLE : MULTI_SECTION);
        chapter.setWordCount(totalWordCount(sections));
        if (single) {
            ChapterSectionEntity only = sections.getFirst();
            chapter.setContent(only.getContent());
            chapter.setContentHash(only.getContentHash());
            chapter.setSummaryDirty(only.getSummaryDirty());
        }
        chapter.setUpdatedAt(OffsetDateTime.now());
        chapterMapper.updateById(chapter);
    }

    private void syncChapterIndex(UUID ownerId, ChapterEntity chapter, List<ChapterSectionEntity> sections) {
        if (sections.stream().anyMatch(s -> !s.getContent().isBlank())) {
            knowledgeDocumentService.syncChapter(ownerId, chapter.getBookId(), chapter.getId(), false);
        } else {
            knowledgeDocumentService.deleteBySourceChapter(ownerId, chapter.getBookId(), chapter.getId());
        }
    }

    private void saveSnapshot(ChapterSectionEntity section, UUID ownerId, String note) {
        SectionVersionEntity snapshot = new SectionVersionEntity();
        snapshot.setId(UUID.randomUUID());
        snapshot.setSectionId(section.getId());
        snapshot.setVersionNo(sectionVersionMapper.findMaxVersionNo(section.getId()) + 1);
        snapshot.setContent(section.getContent());
        snapshot.setChangeSummary(note);
        snapshot.setCreatedBy(ownerId);
        sectionVersionMapper.insert(snapshot);
    }

    private static ChapterSectionEntity newSection(ChapterEntity chapter, int sectionNo, String title, String content, boolean isDefault) {
        ChapterSectionEntity section = new ChapterSectionEntity();
        section.setId(UUID.randomUUID());
        section.setBookId(chapter.getBookId());
        section.setChapterId(chapter.getId());
        section.setChapterNo(chapter.getChapterNo());
        section.setSectionNo(sectionNo);
        section.setNarrativeOrder(narrativeOrder(chapter.getChapterNo(), sectionNo));
        section.setTitle(title);
        section.setContent(Objects.requireNonNullElse(content, ""));
        section.setWordCount(WordCounter.count(content));
        section.setContentHash(StoryMemoryContextService.sha256Hex(content));
        section.setSummaryDirty(!section.getContent().isBlank());
        section.setIsDefault(isDefault);
        section.setVersion(0L);
        return section;
    }

    private static VolumeEntity newVolume(UUID ownerId, UUID bookId, int volumeNo, String title) {
        VolumeEntity volume = new VolumeEntity();
        volume.setId(UUID.randomUUID());
        volume.setBookId(bookId);
        volume.setOwnerId(ownerId);
        volume.setVolumeNo(volumeNo);
        volume.setTitle(title);
        volume.setArcGoal("");
        volume.setPacingNotes("");
        volume.setRealmScope("");
        volume.setStatus("WRITING");
        return volume;
    }

    private static int totalWordCount(List<ChapterSectionEntity> sections) {
        return sections.stream().mapToInt(ChapterSectionEntity::getWordCount).sum();
    }

    private static String structureMode(ChapterEntity chapter) {
        return Objects.requireNonNullElse(chapter.getStructureMode(), SINGLE);
    }

    private static StructureDtos.ChapterTreeNode toChapterNode(ChapterEntity chapter, List<ChapterSectionEntity> sections) {
        return new StructureDtos.ChapterTreeNode(
                chapter.getId(), chapter.getChapterNo(), chapter.getVolumeId(), chapter.getTitle(), structureMode(chapter),
                chapter.getWordCount(), chapter.getStatus(), chapter.getVersion(), sections.stream()
                        .map(s -> new StructureDtos.SectionTreeNode(s.getId(), s.getChapterId(), s.getChapterNo(),
                                s.getSectionNo(), s.getNarrativeOrder(), s.getTitle(), s.getWordCount(),
                                Boolean.TRUE.equals(s.getIsDefault()), Boolean.TRUE.equals(s.getSummaryDirty()), s.getVersion()))
                        .toList()
        );
    }

    private static StructureDtos.SectionDetailResponse toSectionDetail(ChapterSectionEntity s) {
        return new StructureDtos.SectionDetailResponse(
                s.getId(), s.getBookId(), s.getChapterId(), s.getChapterNo(), s.getSectionNo(), s.getNarrativeOrder(),
                s.getTitle(), s.getContent(), s.getWordCount(), s.getContentHash(), Boolean.TRUE.equals(s.getIsDefault()),
                Boolean.TRUE.equals(s.getSummaryDirty()), s.getVersion(), s.getCreatedAt(), s.getUpdatedAt()
        );
    }

    private static StructureDtos.VolumeResponse toVolumeResponse(VolumeEntity volume) {
        return new StructureDtos.VolumeResponse(volume.getId(), volume.getBookId(), volume.getVolumeNo(),
                volume.getTitle(), volume.getArcGoal(), volume.getPacingNotes(), volume.getRealmScope(), volume.getStatus());
    }

    private static BizException sectionNotFound() {
        return BizException.notFound("SECTION_NOT_FOUND", "小节不存在");
    }

    private static String clean(String text) {
        return text == null ? "" : text.trim();
    }
}
