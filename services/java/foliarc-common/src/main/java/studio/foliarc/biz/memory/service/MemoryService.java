package studio.foliarc.biz.memory.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.lore.CharacterState;
import studio.foliarc.biz.lore.LoreNames;
import studio.foliarc.biz.lore.dto.LoreDtos;
import studio.foliarc.biz.lore.entity.CharacterEntity;
import studio.foliarc.biz.lore.service.LoreService;
import studio.foliarc.biz.memory.entity.CharacterRelationEntity;
import studio.foliarc.biz.memory.entity.CharacterStateSnapshotEntity;
import studio.foliarc.biz.memory.entity.TimelineEventEntity;
import studio.foliarc.biz.memory.mapper.CharacterRelationMapper;
import studio.foliarc.biz.memory.mapper.CharacterStateSnapshotMapper;
import studio.foliarc.biz.memory.mapper.TimelineEventMapper;
import studio.foliarc.biz.novel.entity.BookEntity;
import studio.foliarc.biz.novel.entity.ChapterSectionEntity;
import studio.foliarc.biz.novel.mapper.ChapterSectionMapper;
import studio.foliarc.biz.novel.service.BookService;
import studio.foliarc.biz.rag.dto.RagDtos;

@Service
public class MemoryService {

    private static final String AUTHOR = "AUTHOR";
    private static final String AI_EXTRACTED = "AI_EXTRACTED";

    private final BookService bookService;
    private final LoreService loreService;
    private final CharacterRelationMapper relationMapper;
    private final TimelineEventMapper timelineMapper;
    private CharacterStateSnapshotMapper snapshotMapper;
    private ChapterSectionMapper sectionMapper;
    private MemoryProposalService proposalService;

    public MemoryService(
            BookService bookService,
            LoreService loreService,
            CharacterRelationMapper relationMapper,
            TimelineEventMapper timelineMapper
    ) {
        this.bookService = bookService;
        this.loreService = loreService;
        this.relationMapper = relationMapper;
        this.timelineMapper = timelineMapper;
    }

    @Autowired(required = false)
    public void setCharacterStateSnapshotMapper(CharacterStateSnapshotMapper snapshotMapper) {
        this.snapshotMapper = snapshotMapper;
    }

    @Autowired(required = false)
    public void setChapterSectionMapper(ChapterSectionMapper sectionMapper) {
        this.sectionMapper = sectionMapper;
    }

    @Autowired(required = false)
    public void setMemoryProposalService(MemoryProposalService proposalService) {
        this.proposalService = proposalService;
    }

    boolean supportsSectionMemory() {
        return snapshotMapper != null;
    }

    public RagDtos.StructuredMemoryResponse getStructuredMemory(UUID ownerId, UUID bookId, Integer maxChapterNo) {
        bookService.requireOwnedBook(ownerId, bookId);
        List<LoreDtos.CharacterResponse> chars = loreService.listCharacters(ownerId, bookId, null);
        List<LoreDtos.ForeshadowingResponse> foreshadows = loreService.listForeshadowings(ownerId, bookId, null);

        var relQuery = new LambdaQueryWrapper<CharacterRelationEntity>()
                .eq(CharacterRelationEntity::getBookId, bookId)
                .orderByAsc(CharacterRelationEntity::getSinceChapterNo)
                .orderByDesc(CharacterRelationEntity::getUpdatedAt);
        if (maxChapterNo != null && maxChapterNo > 0) {
            relQuery.and(w -> w.isNull(CharacterRelationEntity::getSinceChapterNo)
                    .or().le(CharacterRelationEntity::getSinceChapterNo, maxChapterNo));
        }
        List<RagDtos.CharacterRelationResponse> relations = relationMapper.selectList(relQuery)
                .stream().map(MemoryService::toRelation).toList();

        var timeQuery = new LambdaQueryWrapper<TimelineEventEntity>()
                .eq(TimelineEventEntity::getBookId, bookId)
                .orderByAsc(TimelineEventEntity::getChapterNo)
                .orderByAsc(TimelineEventEntity::getCreatedAt);
        if (maxChapterNo != null && maxChapterNo > 0) {
            timeQuery.le(TimelineEventEntity::getChapterNo, maxChapterNo);
        }
        List<RagDtos.TimelineEventResponse> timeline = timelineMapper.selectList(timeQuery)
                .stream().map(MemoryService::toTimeline).toList();

        return new RagDtos.StructuredMemoryResponse(bookId, chars, foreshadows, relations, timeline);
    }

    public RagDtos.StructuredMemoryResponse getAgentStructuredMemory(UUID ownerId, UUID bookId, Integer maxChapterNo) {
        RagDtos.StructuredMemoryResponse memory = getStructuredMemory(ownerId, bookId, maxChapterNo);
        return new RagDtos.StructuredMemoryResponse(bookId, memory.characters(),
                memory.foreshadowings().stream().map(LoreDtos.ForeshadowingResponse::forAgent).toList(),
                memory.relations().stream().filter(row -> "PUBLIC".equals(row.knownBy())).toList(),
                memory.timelineEvents().stream().filter(row -> "PUBLIC".equals(row.knownBy())).toList());
    }

    @Transactional
    public RagDtos.CharacterRelationResponse upsertRelation(
            UUID ownerId,
            UUID bookId,
            RagDtos.UpsertRelationRequest req
    ) {
        bookService.requireOwnedBook(ownerId, bookId);
        bookService.lockStructure(bookId);
        Map<String, CharacterEntity> characters = loreService.characterIndex(bookId);
        Map<String, CharacterEntity> safeCharacters = characters == null ? Map.of() : characters;
        String src = canonicalName(req.sourceCharacter(), safeCharacters);
        String dst = canonicalName(req.targetCharacter(), safeCharacters);
        if (src.equals(dst)) {
            throw BizException.badRequest("SELF_RELATION_FORBIDDEN", "人物关系的起点与终点不能指向同一角色");
        }
        FactOrigin origin = authorOrigin(bookId, req.sourceSectionId(), req.narrativeOrder());
        Integer sinceChapterNo = req.sinceChapterNo() != null ? req.sinceChapterNo() : origin.chapterNo();
        return toRelation(saveRelation(ownerId, bookId, req, sinceChapterNo, origin, safeCharacters));
    }

    private CharacterRelationEntity saveRelation(
            UUID ownerId, UUID bookId, RagDtos.UpsertRelationRequest req, Integer sinceChapterNo,
            FactOrigin origin, Map<String, CharacterEntity> characters
    ) {
        String src = canonicalName(req.sourceCharacter(), characters);
        String dst = canonicalName(req.targetCharacter(), characters);
        String relType = req.relationType().trim();
        CharacterRelationEntity row = relationMapper.selectOne(new LambdaQueryWrapper<CharacterRelationEntity>()
                .eq(CharacterRelationEntity::getBookId, bookId)
                .eq(CharacterRelationEntity::getSourceCharacter, src)
                .eq(CharacterRelationEntity::getTargetCharacter, dst)
                .eq(CharacterRelationEntity::getRelationType, relType)
                .eq(CharacterRelationEntity::getSource, origin.source())
                .eq(origin.isSectionExtraction(), CharacterRelationEntity::getSourceSectionId, origin.sectionId())
                .isNull(origin.isUnscopedExtraction(), CharacterRelationEntity::getSourceSectionId)
                .orderByDesc(CharacterRelationEntity::getUpdatedAt).last("LIMIT 1"));
        boolean insert = row == null;
        if (insert) {
            row = new CharacterRelationEntity();
            row.setId(UUID.randomUUID());
            row.setBookId(bookId);
            row.setOwnerId(ownerId);
            row.setSourceCharacter(src);
            row.setTargetCharacter(dst);
            row.setRelationType(relType);
            row.setDescription("");
            row.setConfidence(1.0);
            row.setNarrativeOrder(0L);
            row.setSource(origin.source());
        }
        if (req.description() != null && !req.description().isBlank()) row.setDescription(req.description().trim());
        if (sinceChapterNo != null && row.getSinceChapterNo() == null) row.setSinceChapterNo(sinceChapterNo);
        if (req.confidence() != null) row.setConfidence(req.confidence());
        if (origin.sectionId() != null) row.setSourceSectionId(origin.sectionId());
        if (origin.order() != null) row.setNarrativeOrder(origin.order());
        if (req.knownBy() != null || insert) row.setKnownBy(LoreNames.normalizeKnownBy(req.knownBy()));
        if (insert) relationMapper.insert(row);
        else relationMapper.updateById(row);
        return row;
    }

    @Transactional
    public void deleteRelation(UUID ownerId, UUID bookId, UUID relationId) {
        bookService.requireOwnedBook(ownerId, bookId);
        bookService.lockStructure(bookId);
        CharacterRelationEntity row = relationMapper.selectById(relationId);
        if (row == null || !bookId.equals(row.getBookId())) {
            throw BizException.notFound("RELATION_NOT_FOUND", "人物关系记录不存在");
        }
        relationMapper.deleteById(relationId);
    }

    @Transactional
    public RagDtos.TimelineEventResponse upsertTimelineEvent(
            UUID ownerId,
            UUID bookId,
            RagDtos.UpsertTimelineEventRequest req
    ) {
        bookService.requireOwnedBook(ownerId, bookId);
        bookService.lockStructure(bookId);
        Map<String, CharacterEntity> characters = loreService.characterIndex(bookId);
        Map<String, CharacterEntity> safeCharacters = characters == null ? Map.of() : characters;
        return toTimeline(saveTimelineEvent(ownerId, bookId, req, req.chapterNo(),
                authorOrigin(bookId, req.sourceSectionId(), req.narrativeOrder()), safeCharacters));
    }

    private TimelineEventEntity saveTimelineEvent(
            UUID ownerId, UUID bookId, RagDtos.UpsertTimelineEventRequest req, Integer chapter,
            FactOrigin origin, Map<String, CharacterEntity> characters
    ) {
        int chapterNo = chapter == null ? 0 : chapter;
        String title = req.eventTitle().trim();

        TimelineEventEntity row = timelineMapper.selectOne(new LambdaQueryWrapper<TimelineEventEntity>()
                .eq(TimelineEventEntity::getBookId, bookId)
                .eq(TimelineEventEntity::getChapterNo, chapterNo)
                .eq(TimelineEventEntity::getEventTitle, title)
                .eq(TimelineEventEntity::getSource, origin.source())
                .eq(origin.isSectionExtraction(), TimelineEventEntity::getSourceSectionId, origin.sectionId())
                .isNull(origin.isUnscopedExtraction(), TimelineEventEntity::getSourceSectionId)
                .orderByDesc(TimelineEventEntity::getUpdatedAt).last("LIMIT 1"));
        boolean insert = row == null;
        if (insert) {
            row = new TimelineEventEntity();
            row.setId(UUID.randomUUID());
            row.setBookId(bookId);
            row.setOwnerId(ownerId);
            row.setChapterNo(chapterNo);
            row.setEventTitle(title);
            row.setSummary("");
            row.setInvolvedCharacters("");
            row.setLocation("");
            row.setNarrativeOrder(0L);
            row.setSource(origin.source());
        }
        if (req.summary() != null && !req.summary().isBlank()) row.setSummary(req.summary().trim());
        if (req.involvedCharacters() != null) {
            row.setInvolvedCharacters(characters.isEmpty() ? safe(req.involvedCharacters())
                    : String.join(",", LoreNames.split(req.involvedCharacters()).stream()
                            .map(name -> canonicalName(name, characters)).distinct().toList()));
        }
        if (req.location() != null) row.setLocation(req.location().trim());
        if (origin.sectionId() != null) row.setSourceSectionId(origin.sectionId());
        if (origin.order() != null) row.setNarrativeOrder(origin.order());
        if (req.knownBy() != null || insert) row.setKnownBy(LoreNames.normalizeKnownBy(req.knownBy()));
        if (insert) timelineMapper.insert(row);
        else timelineMapper.updateById(row);
        return row;
    }

    @Transactional
    public void deleteTimelineEvent(UUID ownerId, UUID bookId, UUID eventId) {
        bookService.requireOwnedBook(ownerId, bookId);
        bookService.lockStructure(bookId);
        TimelineEventEntity row = timelineMapper.selectById(eventId);
        if (row == null || !bookId.equals(row.getBookId())) {
            throw BizException.notFound("TIMELINE_EVENT_NOT_FOUND", "时间线事件不存在");
        }
        timelineMapper.deleteById(eventId);
    }

    @Transactional
    public RagDtos.ApplyExtractedMemoryResponse applyExtractedMemory(
            UUID ownerId,
            UUID bookId,
            RagDtos.ApplyExtractedMemoryRequest req
    ) {
        BookEntity book = bookService.requireOwnedBook(ownerId, bookId);
        bookService.lockStructure(bookId);
        return applyExtraction(ownerId, bookId, req, null, book == null ? null : book.getGenreCode());
    }

    // StoryMemoryContextService already validates the content hash and holds the book lock.
    RagDtos.ApplyExtractedMemoryResponse applySectionMemory(
            UUID ownerId, UUID bookId, ChapterSectionEntity section, RagDtos.ApplyExtractedMemoryRequest req,
            long maxOrder, String precisionMode, String genreCode
    ) {
        UUID sectionId = section.getId();
        relationMapper.delete(new LambdaQueryWrapper<CharacterRelationEntity>()
                .eq(CharacterRelationEntity::getBookId, bookId)
                .eq(CharacterRelationEntity::getSourceSectionId, sectionId)
                .eq(CharacterRelationEntity::getSource, AI_EXTRACTED));
        timelineMapper.delete(new LambdaQueryWrapper<TimelineEventEntity>()
                .eq(TimelineEventEntity::getBookId, bookId)
                .eq(TimelineEventEntity::getSourceSectionId, sectionId)
                .eq(TimelineEventEntity::getSource, AI_EXTRACTED));
        snapshotMapper.delete(new LambdaQueryWrapper<CharacterStateSnapshotEntity>()
                .eq(CharacterStateSnapshotEntity::getBookId, bookId)
                .eq(CharacterStateSnapshotEntity::getSectionId, sectionId)
                .eq(CharacterStateSnapshotEntity::getSource, AI_EXTRACTED));
        if (proposalService != null) proposalService.clearPendingForSection(bookId, sectionId);

        RagDtos.ApplyExtractedMemoryResponse applied = req == null ? null
                : applyExtraction(ownerId, bookId, req, section, genreCode);
        if ("EXPEDITION".equals(precisionMode) || "EPIC".equals(precisionMode)) {
            loreService.shelveDormantEntities(bookId, maxOrder);
        }
        return applied;
    }

    private RagDtos.ApplyExtractedMemoryResponse applyExtraction(
            UUID ownerId, UUID bookId, RagDtos.ApplyExtractedMemoryRequest req, ChapterSectionEntity section, String genreCode
    ) {
        Integer chapterNo = section == null ? req.chapterNo() : section.getChapterNo();
        FactOrigin origin = section == null ? new FactOrigin(null, 0L, AI_EXTRACTED, chapterNo)
                : new FactOrigin(section.getId(), section.getNarrativeOrder(), AI_EXTRACTED, section.getChapterNo());
        Map<String, CharacterEntity> characterIndex = loreService.characterIndex(bookId);
        int characters = applyCharacterUpdates(ownerId, bookId, req.characterUpdates(), section, characterIndex, genreCode);
        int clues = 0;
        int relations = 0;
        int events = 0;
        for (LoreDtos.UpsertForeshadowingRequest signal : orEmpty(req.foreshadowingUpdates())) {
            if (signal == null || !(hasText(signal.title()) || hasText(signal.code()))) continue;
            if (loreService.applyExtractedForeshadowing(ownerId, bookId, signal, section) != null) clues++;
        }
        for (RagDtos.UpsertRelationRequest relation : orEmpty(req.relations())) {
            if (relation != null && hasText(relation.sourceCharacter()) && hasText(relation.targetCharacter())
                    && hasText(relation.relationType())) {
                saveRelation(ownerId, bookId, relation, relation.sinceChapterNo() == null ? chapterNo : relation.sinceChapterNo(),
                        origin, characterIndex);
                relations++;
            }
        }
        for (RagDtos.UpsertTimelineEventRequest event : orEmpty(req.timelineEvents())) {
            if (event != null && hasText(event.eventTitle())) {
                saveTimelineEvent(ownerId, bookId, event, event.chapterNo() == null ? chapterNo : event.chapterNo(),
                        origin, characterIndex);
                events++;
            }
        }
        return new RagDtos.ApplyExtractedMemoryResponse(characters, clues, relations, events);
    }

    private int applyCharacterUpdates(UUID ownerId, UUID bookId, List<LoreDtos.UpsertCharacterRequest> updates,
                                      ChapterSectionEntity section, Map<String, CharacterEntity> characterIndex,
                                      String genreCode) {
        int applied = 0;
        Map<UUID, CharacterEntity> states = new LinkedHashMap<>();
        for (LoreDtos.UpsertCharacterRequest update : orEmpty(updates)) {
            if (update == null || !hasText(update.name())) continue;
            CharacterEntity existing = LoreService.findExtractedCharacter(characterIndex, update);
            boolean historical = existing != null && section != null && LoreService.lastSeen(existing) > section.getNarrativeOrder();
            CharacterState priorState = null;
            if (historical) {
                CharacterEntity previouslyApplied = states.get(existing.getId());
                priorState = previouslyApplied == null
                        ? findPriorState(bookId, existing.getId(), section.getNarrativeOrder())
                        : CharacterState.of(previouslyApplied);
            }
            CharacterEntity updated = loreService.applyExtractedCharacter(
                    ownerId, bookId, existing, update, section, priorState, genreCode);
            if (updated == null) continue;
            if (!historical) {
                characterIndex.put(updated.getName(), updated);
                LoreNames.split(updated.getAliases()).forEach(alias -> characterIndex.putIfAbsent(alias, updated));
            }
            if (section != null) states.put(updated.getId(), updated);
            applied++;
        }
        if (section != null) states.values().forEach(state -> saveSnapshot(bookId, section, state));
        return applied;
    }

    private CharacterState findPriorState(UUID bookId, UUID characterId, long order) {
        CharacterStateSnapshotEntity prior = snapshotMapper.selectOne(new LambdaQueryWrapper<CharacterStateSnapshotEntity>()
                .eq(CharacterStateSnapshotEntity::getBookId, bookId)
                .eq(CharacterStateSnapshotEntity::getCharacterId, characterId)
                .le(CharacterStateSnapshotEntity::getNarrativeOrder, order)
                .orderByDesc(CharacterStateSnapshotEntity::getNarrativeOrder, CharacterStateSnapshotEntity::getCreatedAt)
                .last("LIMIT 1"));
        return prior == null ? null : CharacterState.readSnapshot(prior.getSnapshotJson());
    }

    private void saveSnapshot(UUID bookId, ChapterSectionEntity section, CharacterEntity character) {
        CharacterStateSnapshotEntity snapshot = new CharacterStateSnapshotEntity();
        snapshot.setId(UUID.randomUUID());
        snapshot.setBookId(bookId);
        snapshot.setCharacterId(character.getId());
        snapshot.setSectionId(section.getId());
        snapshot.setNarrativeOrder(section.getNarrativeOrder());
        snapshot.setSnapshotJson(CharacterState.captureSnapshot(character));
        snapshot.setSource(AI_EXTRACTED);
        snapshotMapper.insert(snapshot);
    }

    private FactOrigin authorOrigin(UUID bookId, UUID sectionId, Long order) {
        if (sectionId == null) return new FactOrigin(null, order, AUTHOR, null);
        ChapterSectionEntity section = sectionMapper == null ? null : sectionMapper.selectById(sectionId);
        if (section == null || !bookId.equals(section.getBookId())) {
            throw BizException.notFound("SECTION_NOT_FOUND", "小节不存在");
        }
        return new FactOrigin(sectionId, section.getNarrativeOrder(), AUTHOR, section.getChapterNo());
    }

    private record FactOrigin(UUID sectionId, Long order, String source, Integer chapterNo) {
        boolean isSectionExtraction() {
            return AI_EXTRACTED.equals(source) && sectionId != null;
        }

        boolean isUnscopedExtraction() {
            return AI_EXTRACTED.equals(source) && sectionId == null;
        }
    }

    private static String canonicalName(String name, Map<String, CharacterEntity> characters) {
        String cleanName = name.strip();
        CharacterEntity character = characters.get(cleanName);
        return character == null ? cleanName : character.getName();
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static RagDtos.CharacterRelationResponse toRelation(CharacterRelationEntity e) {
        return new RagDtos.CharacterRelationResponse(
                e.getId(), e.getBookId(), e.getSourceCharacter(), e.getTargetCharacter(),
                e.getRelationType(), e.getDescription(), e.getSinceChapterNo(),
                e.getConfidence(), e.getUpdatedAt(), e.getSourceSectionId(), e.getNarrativeOrder(),
                e.getSource(), LoreNames.normalizeKnownBy(e.getKnownBy())
        );
    }

    private static RagDtos.TimelineEventResponse toTimeline(TimelineEventEntity e) {
        return new RagDtos.TimelineEventResponse(
                e.getId(), e.getBookId(), e.getChapterNo(), e.getEventTitle(),
                e.getSummary(), e.getInvolvedCharacters(), e.getLocation(), e.getUpdatedAt(), e.getSourceSectionId(),
                e.getNarrativeOrder(), e.getSource(), LoreNames.normalizeKnownBy(e.getKnownBy())
        );
    }
}
