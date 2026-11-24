package studio.foliarc.biz.memory.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.lore.dto.LoreDtos;
import studio.foliarc.biz.lore.service.LoreService;
import studio.foliarc.biz.memory.entity.CharacterRelationEntity;
import studio.foliarc.biz.memory.entity.TimelineEventEntity;
import studio.foliarc.biz.memory.mapper.CharacterRelationMapper;
import studio.foliarc.biz.memory.mapper.TimelineEventMapper;
import studio.foliarc.biz.novel.service.BookService;
import studio.foliarc.biz.rag.dto.RagDtos;

@Service
public class MemoryService {

    private final BookService bookService;
    private final LoreService loreService;
    private final CharacterRelationMapper relationMapper;
    private final TimelineEventMapper timelineMapper;

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

    @Transactional
    public RagDtos.CharacterRelationResponse upsertRelation(
            UUID ownerId,
            UUID bookId,
            RagDtos.UpsertRelationRequest req
    ) {
        bookService.requireOwnedBook(ownerId, bookId);
        String src = req.sourceCharacter().trim();
        String dst = req.targetCharacter().trim();
        String relType = req.relationType().trim();

        CharacterRelationEntity existing = relationMapper.selectOne(
                new LambdaQueryWrapper<CharacterRelationEntity>()
                        .eq(CharacterRelationEntity::getBookId, bookId)
                        .eq(CharacterRelationEntity::getSourceCharacter, src)
                        .eq(CharacterRelationEntity::getTargetCharacter, dst)
                        .eq(CharacterRelationEntity::getRelationType, relType)
        );
        if (existing == null) {
            CharacterRelationEntity entity = new CharacterRelationEntity();
            entity.setId(UUID.randomUUID());
            entity.setBookId(bookId);
            entity.setOwnerId(ownerId);
            entity.setSourceCharacter(src);
            entity.setTargetCharacter(dst);
            entity.setRelationType(relType);
            entity.setDescription(safe(req.description()));
            entity.setSinceChapterNo(req.sinceChapterNo());
            entity.setConfidence(req.confidence() != null ? req.confidence() : 1.0);
            relationMapper.insert(entity);
            return toRelation(entity);
        }
        if (req.description() != null && !req.description().isBlank()) {
            existing.setDescription(req.description().trim());
        }
        if (req.sinceChapterNo() != null && existing.getSinceChapterNo() == null) {
            existing.setSinceChapterNo(req.sinceChapterNo());
        }
        if (req.confidence() != null) {
            existing.setConfidence(req.confidence());
        }
        relationMapper.updateById(existing);
        return toRelation(existing);
    }

    @Transactional
    public void deleteRelation(UUID ownerId, UUID bookId, UUID relationId) {
        bookService.requireOwnedBook(ownerId, bookId);
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
        int chapterNo = req.chapterNo() != null ? req.chapterNo() : 0;
        String title = req.eventTitle().trim();

        TimelineEventEntity existing = timelineMapper.selectOne(
                new LambdaQueryWrapper<TimelineEventEntity>()
                        .eq(TimelineEventEntity::getBookId, bookId)
                        .eq(TimelineEventEntity::getChapterNo, chapterNo)
                        .eq(TimelineEventEntity::getEventTitle, title)
        );
        if (existing == null) {
            TimelineEventEntity entity = new TimelineEventEntity();
            entity.setId(UUID.randomUUID());
            entity.setBookId(bookId);
            entity.setOwnerId(ownerId);
            entity.setChapterNo(chapterNo);
            entity.setEventTitle(title);
            entity.setSummary(safe(req.summary()));
            entity.setInvolvedCharacters(safe(req.involvedCharacters()));
            entity.setLocation(safe(req.location()));
            timelineMapper.insert(entity);
            return toTimeline(entity);
        }
        if (req.summary() != null && !req.summary().isBlank()) existing.setSummary(req.summary().trim());
        if (req.involvedCharacters() != null) existing.setInvolvedCharacters(req.involvedCharacters().trim());
        if (req.location() != null) existing.setLocation(req.location().trim());
        timelineMapper.updateById(existing);
        return toTimeline(existing);
    }

    @Transactional
    public void deleteTimelineEvent(UUID ownerId, UUID bookId, UUID eventId) {
        bookService.requireOwnedBook(ownerId, bookId);
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
        bookService.requireOwnedBook(ownerId, bookId);
        int cCount = 0;
        int fCount = 0;
        int rCount = 0;
        int tCount = 0;

        if (req.characterUpdates() != null) {
            for (LoreDtos.UpsertCharacterRequest c : req.characterUpdates()) {
                if (c != null && c.name() != null && !c.name().isBlank()) {
                    loreService.upsertCharacter(ownerId, bookId, c);
                    cCount++;
                }
            }
        }
        if (req.foreshadowingUpdates() != null) {
            for (LoreDtos.UpsertForeshadowingRequest f : req.foreshadowingUpdates()) {
                if (f != null && f.title() != null && !f.title().isBlank()) {
                    loreService.upsertForeshadowing(ownerId, bookId, f);
                    fCount++;
                }
            }
        }
        if (req.relations() != null) {
            for (RagDtos.UpsertRelationRequest r : req.relations()) {
                if (r != null
                        && r.sourceCharacter() != null && !r.sourceCharacter().isBlank()
                        && r.targetCharacter() != null && !r.targetCharacter().isBlank()
                        && r.relationType() != null && !r.relationType().isBlank()) {
                    RagDtos.UpsertRelationRequest normalized = new RagDtos.UpsertRelationRequest(
                            r.sourceCharacter(),
                            r.targetCharacter(),
                            r.relationType(),
                            r.description(),
                            r.sinceChapterNo() != null ? r.sinceChapterNo() : req.chapterNo(),
                            r.confidence()
                    );
                    upsertRelation(ownerId, bookId, normalized);
                    rCount++;
                }
            }
        }
        if (req.timelineEvents() != null) {
            for (RagDtos.UpsertTimelineEventRequest t : req.timelineEvents()) {
                if (t != null && t.eventTitle() != null && !t.eventTitle().isBlank()) {
                    RagDtos.UpsertTimelineEventRequest normalized = new RagDtos.UpsertTimelineEventRequest(
                            t.chapterNo() != null ? t.chapterNo() : req.chapterNo(),
                            t.eventTitle(),
                            t.summary(),
                            t.involvedCharacters(),
                            t.location()
                    );
                    upsertTimelineEvent(ownerId, bookId, normalized);
                    tCount++;
                }
            }
        }
        return new RagDtos.ApplyExtractedMemoryResponse(cCount, fCount, rCount, tCount);
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static RagDtos.CharacterRelationResponse toRelation(CharacterRelationEntity e) {
        return new RagDtos.CharacterRelationResponse(
                e.getId(), e.getBookId(), e.getSourceCharacter(), e.getTargetCharacter(),
                e.getRelationType(), e.getDescription(), e.getSinceChapterNo(),
                e.getConfidence(), e.getUpdatedAt()
        );
    }

    private static RagDtos.TimelineEventResponse toTimeline(TimelineEventEntity e) {
        return new RagDtos.TimelineEventResponse(
                e.getId(), e.getBookId(), e.getChapterNo(), e.getEventTitle(),
                e.getSummary(), e.getInvolvedCharacters(), e.getLocation(), e.getUpdatedAt()
        );
    }
}
