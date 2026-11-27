package studio.foliarc.biz.visual.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.lore.CharacterState;
import studio.foliarc.biz.lore.LoreNames;
import studio.foliarc.biz.lore.entity.CharacterEntity;
import studio.foliarc.biz.lore.entity.ForeshadowingEntity;
import studio.foliarc.biz.lore.entity.WorldEntryEntity;
import studio.foliarc.biz.lore.mapper.CharacterMapper;
import studio.foliarc.biz.lore.mapper.ForeshadowingMapper;
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
import studio.foliarc.biz.novel.entity.ChapterEntity;
import studio.foliarc.biz.novel.entity.ChapterSectionEntity;
import studio.foliarc.biz.novel.entity.VolumeEntity;
import studio.foliarc.biz.novel.mapper.ChapterMapper;
import studio.foliarc.biz.novel.mapper.ChapterSectionMapper;
import studio.foliarc.biz.novel.mapper.VolumeMapper;
import studio.foliarc.biz.novel.service.BookService;
import studio.foliarc.biz.novel.service.StructureService;
import studio.foliarc.biz.rag.dto.RagDtos;
import studio.foliarc.biz.visual.dto.VisualDtos;
import studio.foliarc.biz.visual.dto.VisualDtos.DirectorCharacterOption;
import studio.foliarc.biz.visual.dto.VisualDtos.UpdateVisualEventRequest;
import studio.foliarc.biz.visual.dto.VisualDtos.UpdateVisualRelationRequest;
import studio.foliarc.biz.visual.dto.VisualDtos.VisualCapabilities;
import studio.foliarc.biz.visual.dto.VisualDtos.VisualChapterAxisItem;
import studio.foliarc.biz.visual.dto.VisualDtos.VisualCutoffInfo;
import studio.foliarc.biz.visual.dto.VisualDtos.VisualDirectorOptionsResponse;
import studio.foliarc.biz.visual.dto.VisualDtos.VisualEntityDetailResponse;
import studio.foliarc.biz.visual.dto.VisualDtos.VisualEntityLane;
import studio.foliarc.biz.visual.dto.VisualDtos.VisualEvidenceItem;
import studio.foliarc.biz.visual.dto.VisualDtos.VisualForeshadowingArc;
import studio.foliarc.biz.visual.dto.VisualDtos.VisualGraphEdge;
import studio.foliarc.biz.visual.dto.VisualDtos.VisualGraphNode;
import studio.foliarc.biz.visual.dto.VisualDtos.VisualHeatCell;
import studio.foliarc.biz.visual.dto.VisualDtos.VisualProjectionResponse;
import studio.foliarc.biz.visual.dto.VisualDtos.VisualTimelineLaneEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class VisualProjectionService {

    private static final String VIEW_GRAPH = "graph";
    private static final String VIEW_LANES = "lanes";
    private static final Set<String> ALLOWED_VIEWS = Set.of(VIEW_GRAPH, VIEW_LANES);
    private static final String VISIBILITY_AS_OF = "AS_OF";
    private static final String VISIBILITY_PLAN = "PLAN";
    private static final Set<String> ALLOWED_VISIBILITIES = Set.of(VISIBILITY_AS_OF, VISIBILITY_PLAN);
    private static final Set<String> ALLOWED_ENTITY_KINDS = Set.of("character", "foreshadowing", "event", "relation");
    private static final List<String> FACTION_ATTRIBUTE_KEYS = List.of("faction", "sect", "organization", "camp", "势力", "宗门", "阵营");
    private static final String DEFAULT_VOLUME_TITLE = "第一卷";
    private static final String AUTHOR = "AUTHOR";
    private static final String AI_EXTRACTED = "AI_EXTRACTED";
    private static final String UNRESOLVED_PREFIX = "unresolved:";
    private static final String UNKNOWN_STATUS_SUMMARY = "当时状态未记录";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final BookService bookService;
    private final LoreService loreService;
    private final VolumeMapper volumeMapper;
    private final ChapterMapper chapterMapper;
    private final ChapterSectionMapper sectionMapper;
    private final CharacterMapper characterMapper;
    private final WorldEntryMapper worldEntryMapper;
    private final ForeshadowingMapper foreshadowingMapper;
    private final CharacterRelationMapper relationMapper;
    private final CharacterStateSnapshotMapper snapshotMapper;
    private final TimelineEventMapper timelineMapper;
    private final SectionSummaryMapper summaryMapper;

    public VisualProjectionService(
            BookService bookService,
            LoreService loreService,
            VolumeMapper volumeMapper,
            ChapterMapper chapterMapper,
            ChapterSectionMapper sectionMapper,
            CharacterMapper characterMapper,
            WorldEntryMapper worldEntryMapper,
            ForeshadowingMapper foreshadowingMapper,
            CharacterRelationMapper relationMapper,
            CharacterStateSnapshotMapper snapshotMapper,
            TimelineEventMapper timelineMapper,
            SectionSummaryMapper summaryMapper
    ) {
        this.bookService = bookService;
        this.loreService = loreService;
        this.volumeMapper = volumeMapper;
        this.chapterMapper = chapterMapper;
        this.sectionMapper = sectionMapper;
        this.characterMapper = characterMapper;
        this.worldEntryMapper = worldEntryMapper;
        this.foreshadowingMapper = foreshadowingMapper;
        this.relationMapper = relationMapper;
        this.snapshotMapper = snapshotMapper;
        this.timelineMapper = timelineMapper;
        this.summaryMapper = summaryMapper;
    }

    public VisualProjectionResponse getProjection(
            UUID ownerId,
            UUID bookId,
            String rawView,
            String rawVisibility,
            UUID asOfChapterId,
            UUID asOfSectionId,
            UUID volumeId,
            Integer fromChapter,
            Integer toChapter,
            UUID povId,
            String focusId
    ) {
        bookService.requireOwnedBook(ownerId, bookId);
        String view = normalizeView(rawView);
        String visibility = normalizeVisibility(rawVisibility);
        if (fromChapter != null && toChapter != null && fromChapter > toChapter) {
            throw BizException.badRequest("INVALID_CHAPTER_RANGE", "起始章不能晚于截止章");
        }
        ProjectionBundle bundle = buildProjection(
                bookId, view, visibility, asOfChapterId, asOfSectionId, volumeId, fromChapter, toChapter, povId,
                focusId, VIEW_LANES.equals(view), true);
        return bundle.response();
    }

    public VisualEntityDetailResponse getEntityDetail(
            UUID ownerId,
            UUID bookId,
            String rawKind,
            String entityId,
            String rawVisibility,
            UUID asOfChapterId,
            UUID asOfSectionId,
            UUID povId
    ) {
        bookService.requireOwnedBook(ownerId, bookId);
        String kind = normalizeEntityKind(rawKind);
        String visibility = normalizeVisibility(rawVisibility);
        if (entityId == null || entityId.isBlank()) {
            throw entityNotFound();
        }
        ProjectionBundle bundle = buildProjection(
                bookId, VIEW_GRAPH, visibility, asOfChapterId, asOfSectionId, null, null, null, povId,
                null, false, false);
        return switch (kind) {
            case "character" -> buildCharacterDetail(bundle, entityId.strip());
            case "foreshadowing" -> buildForeshadowingDetail(bundle, entityId.strip());
            case "event" -> buildEventDetail(bundle, entityId.strip());
            case "relation" -> buildRelationDetail(bundle, entityId.strip());
            default -> throw BizException.badRequest("INVALID_ENTITY_KIND", "不支持的实体类型: " + rawKind);
        };
    }

    public VisualDirectorOptionsResponse getDirectorOptions(
            UUID ownerId,
            UUID bookId,
            String rawVisibility,
            UUID asOfChapterId,
            UUID asOfSectionId
    ) {
        bookService.requireOwnedBook(ownerId, bookId);
        String visibility = normalizeVisibility(rawVisibility);
        ProjectionBundle bundle = buildProjection(
                bookId, VIEW_GRAPH, visibility, asOfChapterId, asOfSectionId, null, null, null, null,
                null, false, false);
        List<DirectorCharacterOption> characters = bundle.response().nodes().stream()
                .filter(node -> node.characterId() != null && !node.unresolved())
                .map(node -> new DirectorCharacterOption(node.characterId(), node.name(), node.roleType()))
                .toList();
        List<VisualForeshadowingArc> foreshadowings = bundle.response().arcs().stream()
                .filter(arc -> arc.allowedDirectorModes() != null && !arc.allowedDirectorModes().isEmpty())
                .toList();
        return new VisualDirectorOptionsResponse(
                visibility,
                bundle.response().cutoff().chapterNo(),
                characters,
                foreshadowings
        );
    }

    @Transactional
    public RagDtos.CharacterRelationResponse updateRelation(
            UUID ownerId,
            UUID bookId,
            UUID relationId,
            UpdateVisualRelationRequest req
    ) {
        bookService.requireOwnedBook(ownerId, bookId);
        bookService.lockStructure(bookId);
        if (req == null) {
            throw BizException.badRequest("INVALID_REQUEST_BODY", "请求体不能为空");
        }
        CharacterRelationEntity row = relationMapper.selectById(relationId);
        if (row == null || !bookId.equals(row.getBookId())) {
            throw BizException.notFound("RELATION_NOT_FOUND", "人物关系记录不存在");
        }
        if (AI_EXTRACTED.equalsIgnoreCase(safe(row.getSource()))) {
            throw BizException.conflict("AI_RELATION_READONLY", "AI 抽取的人物关系为只读证据，请通过新增作者关系进行确认或覆盖");
        }
        if (req.expectedUpdatedAt() != null) {
            OffsetDateTime currentUpdatedAt = row.getUpdatedAt();
            if (currentUpdatedAt == null
                    || !currentUpdatedAt.toInstant().truncatedTo(ChronoUnit.MICROS)
                            .equals(req.expectedUpdatedAt().toInstant().truncatedTo(ChronoUnit.MICROS))) {
                throw BizException.conflict("RELATION_CONFLICT", "人物关系已被其他操作更新，请刷新后重试");
            }
        }

        Map<String, CharacterEntity> characters = orEmptyMap(loreService.characterIndex(bookId));
        String src = resolveUpdatedEndpoint(req.sourceCharacter(), row.getSourceCharacter(), characters);
        String dst = resolveUpdatedEndpoint(req.targetCharacter(), row.getTargetCharacter(), characters);
        String relType;
        if (req.relationType() != null) {
            relType = req.relationType().trim();
            if (relType.isEmpty()) {
                throw BizException.badRequest("INVALID_RELATION_TYPE", "关系类型不能为空");
            }
        } else {
            relType = safe(row.getRelationType());
        }

        if (src.equals(dst)) {
            throw BizException.badRequest("SELF_RELATION_FORBIDDEN", "人物关系的起点与终点不能指向同一角色");
        }

        CharacterRelationEntity duplicate = relationMapper.selectOne(new LambdaQueryWrapper<CharacterRelationEntity>()
                .eq(CharacterRelationEntity::getBookId, bookId)
                .eq(CharacterRelationEntity::getSourceCharacter, src)
                .eq(CharacterRelationEntity::getTargetCharacter, dst)
                .eq(CharacterRelationEntity::getRelationType, relType)
                .eq(CharacterRelationEntity::getSource, AUTHOR)
                .ne(CharacterRelationEntity::getId, relationId)
                .last("LIMIT 1"));
        if (duplicate != null) {
            throw BizException.conflict("RELATION_DUPLICATE", "已存在相同自然键的作者人物关系");
        }

        if (req.sourceSectionId() != null) {
            ChapterSectionEntity section = sectionMapper.selectById(req.sourceSectionId());
            if (section == null || !bookId.equals(section.getBookId())) {
                throw BizException.notFound("SECTION_NOT_FOUND", "小节不存在");
            }
            row.setSourceSectionId(section.getId());
            row.setNarrativeOrder(section.getNarrativeOrder());
            if (req.sinceChapterNo() != null) {
                if (req.sinceChapterNo() <= 0) {
                    throw BizException.badRequest("INVALID_CHAPTER_NO", "起始章节编号必须大于零");
                }
                row.setSinceChapterNo(req.sinceChapterNo());
            } else {
                row.setSinceChapterNo(section.getChapterNo());
            }
        } else if (req.sinceChapterNo() != null) {
            if (req.sinceChapterNo() <= 0) {
                throw BizException.badRequest("INVALID_CHAPTER_NO", "起始章节编号必须大于零");
            }
            row.setSinceChapterNo(req.sinceChapterNo());
            if (row.getSourceSectionId() != null) {
                ChapterSectionEntity existingSection = sectionMapper.selectById(row.getSourceSectionId());
                if (existingSection == null || !Objects.equals(existingSection.getChapterNo(), req.sinceChapterNo())) {
                    row.setSourceSectionId(null);
                    row.setNarrativeOrder(StructureService.narrativeOrder(req.sinceChapterNo(), 1));
                }
            } else {
                row.setNarrativeOrder(StructureService.narrativeOrder(req.sinceChapterNo(), 1));
            }
        }

        row.setSourceCharacter(src);
        row.setTargetCharacter(dst);
        row.setRelationType(relType);
        if (req.description() != null) {
            row.setDescription(req.description().trim());
        }
        if (req.knownBy() != null) {
            row.setKnownBy(LoreNames.normalizeKnownBy(req.knownBy()));
        }
        if (row.getSource() == null || row.getSource().isBlank()) {
            row.setSource(AUTHOR);
        }
        row.setUpdatedAt(OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS));
        relationMapper.updateById(row);

        return toRelationResponse(row);
    }

    @Transactional
    public RagDtos.CharacterRelationResponse confirmAiRelation(
            UUID ownerId,
            UUID bookId,
            UUID relationId
    ) {
        bookService.requireOwnedBook(ownerId, bookId);
        bookService.lockStructure(bookId);
        CharacterRelationEntity row = relationMapper.selectById(relationId);
        if (row == null || !bookId.equals(row.getBookId())) {
            throw BizException.notFound("RELATION_NOT_FOUND", "人物关系记录不存在");
        }
        if (!AI_EXTRACTED.equalsIgnoreCase(safe(row.getSource()))) {
            return toRelationResponse(row);
        }

        Map<String, CharacterEntity> characters = orEmptyMap(loreService.characterIndex(bookId));
        String src = canonicalName(row.getSourceCharacter(), characters);
        String dst = canonicalName(row.getTargetCharacter(), characters);
        String relType = safe(row.getRelationType());
        if (src.isEmpty() || dst.isEmpty() || src.equals(dst)) {
            throw BizException.badRequest("SELF_RELATION_FORBIDDEN", "人物关系的起点与终点不能指向同一角色");
        }

        CharacterRelationEntity existingAuthor = relationMapper.selectOne(new LambdaQueryWrapper<CharacterRelationEntity>()
                .eq(CharacterRelationEntity::getBookId, bookId)
                .eq(CharacterRelationEntity::getSourceCharacter, src)
                .eq(CharacterRelationEntity::getTargetCharacter, dst)
                .eq(CharacterRelationEntity::getRelationType, relType)
                .eq(CharacterRelationEntity::getSource, AUTHOR)
                .ne(CharacterRelationEntity::getId, relationId)
                .orderByDesc(CharacterRelationEntity::getUpdatedAt)
                .last("LIMIT 1"));

        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        if (existingAuthor != null) {
            if (!hasText(existingAuthor.getDescription()) && hasText(row.getDescription())) {
                existingAuthor.setDescription(row.getDescription().trim());
            }
            if (existingAuthor.getSinceChapterNo() == null && row.getSinceChapterNo() != null) {
                existingAuthor.setSinceChapterNo(row.getSinceChapterNo());
            }
            if (existingAuthor.getSourceSectionId() == null && row.getSourceSectionId() != null) {
                existingAuthor.setSourceSectionId(row.getSourceSectionId());
            }
            if ((existingAuthor.getNarrativeOrder() == null || existingAuthor.getNarrativeOrder() <= 0L)
                    && row.getNarrativeOrder() != null && row.getNarrativeOrder() > 0L) {
                existingAuthor.setNarrativeOrder(row.getNarrativeOrder());
            }
            existingAuthor.setUpdatedAt(now);
            relationMapper.updateById(existingAuthor);
            relationMapper.deleteById(relationId);
            return toRelationResponse(existingAuthor);
        }

        row.setSourceCharacter(src);
        row.setTargetCharacter(dst);
        row.setRelationType(relType);
        row.setSource(AUTHOR);
        row.setConfidence(1.0);
        row.setKnownBy(LoreNames.normalizeKnownBy(row.getKnownBy()));
        row.setUpdatedAt(now);
        relationMapper.updateById(row);
        return toRelationResponse(row);
    }

    @Transactional
    public RagDtos.TimelineEventResponse updateEvent(
            UUID ownerId,
            UUID bookId,
            UUID eventId,
            UpdateVisualEventRequest req
    ) {
        bookService.requireOwnedBook(ownerId, bookId);
        bookService.lockStructure(bookId);
        if (req == null) {
            throw BizException.badRequest("INVALID_REQUEST_BODY", "请求体不能为空");
        }
        TimelineEventEntity row = timelineMapper.selectById(eventId);
        if (row == null || !bookId.equals(row.getBookId())) {
            throw BizException.notFound("TIMELINE_EVENT_NOT_FOUND", "时间线事件不存在");
        }

        if (req.eventTitle() != null) {
            String title = req.eventTitle().trim();
            if (title.isEmpty()) {
                throw BizException.badRequest("INVALID_EVENT_TITLE", "事件标题不能为空");
            }
            row.setEventTitle(title);
        }
        if (req.summary() != null) {
            row.setSummary(req.summary().trim());
        }
        if (req.location() != null) {
            row.setLocation(req.location().trim());
        }
        if (req.involvedCharacters() != null) {
            Map<String, CharacterEntity> characters = orEmptyMap(loreService.characterIndex(bookId));
            List<String> normalized = canonicalList(req.involvedCharacters(), characters);
            row.setInvolvedCharacters(String.join(",", normalized));
        }
        if (req.knownBy() != null) {
            row.setKnownBy(LoreNames.normalizeKnownBy(req.knownBy()));
        }

        if (req.sourceSectionId() != null) {
            ChapterSectionEntity section = sectionMapper.selectById(req.sourceSectionId());
            if (section == null || !bookId.equals(section.getBookId())) {
                throw BizException.notFound("SECTION_NOT_FOUND", "小节不存在");
            }
            row.setSourceSectionId(section.getId());
            row.setNarrativeOrder(section.getNarrativeOrder());
            row.setChapterNo(req.chapterNo() != null && req.chapterNo() > 0 ? req.chapterNo() : section.getChapterNo());
        } else if (req.chapterNo() != null) {
            if (req.chapterNo() <= 0) {
                throw BizException.badRequest("INVALID_CHAPTER_NO", "章节编号必须大于零");
            }
            if (!Objects.equals(row.getChapterNo(), req.chapterNo())) {
                row.setSourceSectionId(null);
            }
            row.setChapterNo(req.chapterNo());
            row.setNarrativeOrder(StructureService.narrativeOrder(req.chapterNo(), 1));
        }

        row.setSource(AUTHOR);
        row.setUpdatedAt(OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS));
        timelineMapper.updateById(row);

        return new RagDtos.TimelineEventResponse(
                row.getId(),
                row.getBookId(),
                row.getChapterNo(),
                row.getEventTitle(),
                row.getSummary(),
                row.getInvolvedCharacters(),
                row.getLocation(),
                row.getUpdatedAt(),
                row.getSourceSectionId(),
                row.getNarrativeOrder(),
                row.getSource(),
                LoreNames.normalizeKnownBy(row.getKnownBy())
        );
    }

    private static RagDtos.CharacterRelationResponse toRelationResponse(CharacterRelationEntity row) {
        return new RagDtos.CharacterRelationResponse(
                row.getId(),
                row.getBookId(),
                row.getSourceCharacter(),
                row.getTargetCharacter(),
                row.getRelationType(),
                row.getDescription(),
                row.getSinceChapterNo(),
                row.getConfidence(),
                row.getUpdatedAt(),
                row.getSourceSectionId(),
                row.getNarrativeOrder(),
                row.getSource(),
                LoreNames.normalizeKnownBy(row.getKnownBy())
        );
    }

    private ProjectionBundle buildProjection(
            UUID bookId,
            String view,
            String visibility,
            UUID asOfChapterId,
            UUID asOfSectionId,
            UUID volumeId,
            Integer fromChapter,
            Integer toChapter,
            UUID povId,
            String focusId,
            boolean includeLanes,
            boolean computeEtag
    ) {
        boolean asOf = VISIBILITY_AS_OF.equals(visibility);

        List<VolumeEntity> allVolumes = filterBook(volumeMapper.selectList(
                new LambdaQueryWrapper<VolumeEntity>()
                        .eq(VolumeEntity::getBookId, bookId)
                        .orderByAsc(VolumeEntity::getVolumeNo)), bookId, VolumeEntity::getBookId);
        List<ChapterEntity> allChapters = filterBook(chapterMapper.selectList(
                new LambdaQueryWrapper<ChapterEntity>()
                        .eq(ChapterEntity::getBookId, bookId)
                        .orderByAsc(ChapterEntity::getChapterNo)), bookId, ChapterEntity::getBookId);
        List<ChapterSectionEntity> allSections = filterBook(sectionMapper.selectList(
                new LambdaQueryWrapper<ChapterSectionEntity>()
                        .eq(ChapterSectionEntity::getBookId, bookId)
                        .orderByAsc(ChapterSectionEntity::getNarrativeOrder)), bookId, ChapterSectionEntity::getBookId);
        List<CharacterEntity> allCharacters = filterBook(characterMapper.selectList(
                new LambdaQueryWrapper<CharacterEntity>()
                        .eq(CharacterEntity::getBookId, bookId)
                        .orderByAsc(CharacterEntity::getName)), bookId, CharacterEntity::getBookId);

        Map<UUID, VolumeEntity> volumesById = new LinkedHashMap<>();
        allVolumes.forEach(v -> volumesById.put(v.getId(), v));
        VolumeEntity firstVolume = allVolumes.stream()
                .filter(v -> Objects.equals(v.getVolumeNo(), 1)).findFirst().orElse(null);

        Map<UUID, ChapterEntity> chaptersById = new LinkedHashMap<>();
        Map<Integer, ChapterEntity> chaptersByNo = new LinkedHashMap<>();
        allChapters.forEach(c -> {
            chaptersById.put(c.getId(), c);
            if (c.getChapterNo() != null) {
                chaptersByNo.put(c.getChapterNo(), c);
            }
        });

        Map<UUID, ChapterSectionEntity> sectionsById = new LinkedHashMap<>();
        Map<UUID, List<ChapterSectionEntity>> sectionsByChapterId = new HashMap<>();
        allSections.forEach(s -> {
            sectionsById.put(s.getId(), s);
            if (s.getChapterId() != null) {
                sectionsByChapterId.computeIfAbsent(s.getChapterId(), k -> new ArrayList<>()).add(s);
            }
        });
        sectionsByChapterId.values().forEach(list -> list.sort(
                Comparator.comparing(ChapterSectionEntity::getSectionNo, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ChapterSectionEntity::getNarrativeOrder, Comparator.nullsLast(Comparator.naturalOrder()))));

        Map<UUID, CharacterEntity> charactersById = new LinkedHashMap<>();
        Map<String, CharacterEntity> characterIndex = new LinkedHashMap<>();
        allCharacters.forEach(c -> {
            charactersById.put(c.getId(), c);
            if (hasText(c.getName())) {
                characterIndex.put(c.getName().strip(), c);
            }
        });
        allCharacters.forEach(c -> LoreNames.split(c.getAliases())
                .forEach(alias -> characterIndex.putIfAbsent(alias, c)));

        // Cross-book ownership checks for optional filter IDs (all fail with 404).
        if (volumeId != null && !volumesById.containsKey(volumeId)) {
            throw BizException.notFound("VOLUME_NOT_FOUND", "卷不存在");
        }
        Set<String> povNames = null;
        if (povId != null) {
            CharacterEntity povChar = charactersById.get(povId);
            if (povChar == null) {
                throw BizException.notFound("CHARACTER_NOT_FOUND", "视角人物不存在");
            }
            povNames = new LinkedHashSet<>();
            if (hasText(povChar.getName())) povNames.add(povChar.getName().strip());
            povNames.addAll(LoreNames.split(povChar.getAliases()));
        }

        // Resolve cutoff (section takes precedence over chapter; both validated against bookId).
        if (asOfChapterId != null && !chaptersById.containsKey(asOfChapterId)) {
            throw BizException.notFound("CHAPTER_NOT_FOUND", "章节不存在");
        }
        ChapterSectionEntity cutoffSection = null;
        if (asOfSectionId != null) {
            cutoffSection = sectionsById.get(asOfSectionId);
            if (cutoffSection == null) {
                throw BizException.notFound("SECTION_NOT_FOUND", "小节不存在");
            }
        }

        VisualCutoffInfo cutoff = computeCutoff(visibility, allChapters, chaptersById, asOfChapterId, cutoffSection);
        int cutoffChapterNo = cutoff.chapterNo();
        long cutoffOrder = cutoff.narrativeOrder();

        // Empty book in AS_OF mode returns strictly empty projection.
        if (asOf && cutoffChapterNo == 0 && cutoffOrder == 0L) {
            VisualProjectionResponse empty = withEtag(new VisualProjectionResponse(
                    bookId,
                    view,
                    VisualCapabilities.phase7A(),
                    cutoff,
                    "",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    0,
                    0,
                    0,
                    0.0
            ));
            return new ProjectionBundle(
                    empty, asOf, cutoff, allCharacters, charactersById, characterIndex,
                    chaptersByNo, sectionsById, sectionsByChapterId,
                    Map.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(), Map.of()
            );
        }

        // Load remaining book data.
        List<WorldEntryEntity> worldEntries = asOf ? List.of() : filterBook(worldEntryMapper.selectList(
                new LambdaQueryWrapper<WorldEntryEntity>().eq(WorldEntryEntity::getBookId, bookId)),
                bookId, WorldEntryEntity::getBookId);
        List<String> worldFactions = worldEntries.stream()
                .filter(w -> "FACTION".equalsIgnoreCase(safe(w.getCategory())) && hasText(w.getTitle()))
                .map(w -> w.getTitle().trim())
                .distinct()
                .toList();

        List<CharacterStateSnapshotEntity> allSnapshots = filterBook(snapshotMapper.selectList(
                new LambdaQueryWrapper<CharacterStateSnapshotEntity>().eq(CharacterStateSnapshotEntity::getBookId, bookId)),
                bookId, CharacterStateSnapshotEntity::getBookId);
        List<SectionSummaryEntity> allSummaries = filterBook(summaryMapper.selectList(
                new LambdaQueryWrapper<SectionSummaryEntity>().eq(SectionSummaryEntity::getBookId, bookId)),
                bookId, SectionSummaryEntity::getBookId);
        List<CharacterRelationEntity> allRelations = filterBook(relationMapper.selectList(
                new LambdaQueryWrapper<CharacterRelationEntity>().eq(CharacterRelationEntity::getBookId, bookId)),
                bookId, CharacterRelationEntity::getBookId);
        List<TimelineEventEntity> allEvents = filterBook(timelineMapper.selectList(
                new LambdaQueryWrapper<TimelineEventEntity>().eq(TimelineEventEntity::getBookId, bookId)),
                bookId, TimelineEventEntity::getBookId);
        List<ForeshadowingEntity> allForeshadowings = filterBook(foreshadowingMapper.selectList(
                new LambdaQueryWrapper<ForeshadowingEntity>().eq(ForeshadowingEntity::getBookId, bookId)),
                bookId, ForeshadowingEntity::getBookId);

        // 1. Build chapter axis (AS_OF strictly trims to chapterNo <= cutoffChapterNo; window filters narrow display axis only).
        boolean windowActive = volumeId != null || fromChapter != null || toChapter != null;
        List<ChapterEntity> visibleChapters = allChapters.stream()
                .filter(c -> !asOf || (c.getChapterNo() != null && c.getChapterNo() <= cutoffChapterNo))
                .toList();
        List<ChapterEntity> axisChapterEntities = visibleChapters.stream()
                .filter(c -> {
                    if (volumeId != null) {
                        UUID effectiveVolId = c.getVolumeId() != null ? c.getVolumeId()
                                : (firstVolume != null ? firstVolume.getId() : null);
                        if (!volumeId.equals(effectiveVolId)) return false;
                    }
                    int chNo = c.getChapterNo() == null ? 0 : c.getChapterNo();
                    if (fromChapter != null && chNo < fromChapter) return false;
                    if (toChapter != null && chNo > toChapter) return false;
                    return true;
                })
                .toList();

        List<VisualChapterAxisItem> axisChapters = axisChapterEntities.stream()
                .map(c -> toChapterAxisItem(c, volumesById, firstVolume, sectionsByChapterId))
                .toList();
        Set<Integer> axisChapterNos = axisChapters.stream()
                .map(VisualChapterAxisItem::chapterNo)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Integer windowStart = axisChapters.isEmpty() ? fromChapter : axisChapters.getFirst().chapterNo();
        Integer windowEnd = axisChapters.isEmpty() ? toChapter : axisChapters.getLast().chapterNo();

        // 2. Filter snapshots & summaries strictly against cutoff.
        List<ResolvedSnapshot> visibleSnapshots = new ArrayList<>();
        for (CharacterStateSnapshotEntity snap : allSnapshots) {
            if (snap.getCharacterId() == null) continue;
            ChapterSectionEntity sec = snap.getSectionId() != null ? sectionsById.get(snap.getSectionId()) : null;
            long order = sec != null && sec.getNarrativeOrder() != null ? sec.getNarrativeOrder()
                    : (snap.getNarrativeOrder() == null ? 0L : snap.getNarrativeOrder());
            int chNo = sec != null && sec.getChapterNo() != null ? sec.getChapterNo()
                    : (order > 0 ? (int) (order / 1000L) : 0);
            if (asOf && (order <= 0L || order > cutoffOrder || chNo <= 0 || chNo > cutoffChapterNo)) {
                continue;
            }
            CharacterState parsed = parseSnapshotState(snap.getSnapshotJson());
            visibleSnapshots.add(new ResolvedSnapshot(snap, sec, chNo, order, parsed));
        }

        Map<UUID, List<ResolvedSnapshot>> snapshotsByCharId = new HashMap<>();
        for (ResolvedSnapshot rs : visibleSnapshots) {
            snapshotsByCharId.computeIfAbsent(rs.entity().getCharacterId(), k -> new ArrayList<>()).add(rs);
        }
        Comparator<ResolvedSnapshot> latestSnapshotOrder = Comparator
                .comparingLong(ResolvedSnapshot::order).reversed()
                .thenComparing(s -> s.entity().getCreatedAt(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(s -> s.entity().getId(), Comparator.nullsLast(Comparator.reverseOrder()));
        Map<UUID, CharacterState> latestSnapshotByCharId = new HashMap<>();
        for (var entry : snapshotsByCharId.entrySet()) {
            entry.getValue().sort(latestSnapshotOrder);
            entry.getValue().stream()
                    .map(ResolvedSnapshot::state)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .ifPresent(state -> latestSnapshotByCharId.put(entry.getKey(), state));
        }

        List<SectionSummaryEntity> visibleSummaries = new ArrayList<>();
        for (SectionSummaryEntity summary : allSummaries) {
            if ("STALE".equalsIgnoreCase(safe(summary.getStatus()))) continue;
            ChapterSectionEntity sec = summary.getSectionId() != null ? sectionsById.get(summary.getSectionId()) : null;
            if (sec != null && hasText(sec.getContentHash()) && hasText(summary.getContentHash())
                    && !sec.getContentHash().equals(summary.getContentHash())) {
                continue;
            }
            long order = sec != null && sec.getNarrativeOrder() != null ? sec.getNarrativeOrder()
                    : (summary.getNarrativeOrder() == null ? 0L : summary.getNarrativeOrder());
            int chNo = sec != null && sec.getChapterNo() != null ? sec.getChapterNo()
                    : (summary.getChapterNo() == null ? 0 : summary.getChapterNo());
            if (asOf && (order <= 0L || order > cutoffOrder || chNo <= 0 || chNo > cutoffChapterNo)) {
                continue;
            }
            visibleSummaries.add(summary);
        }

        // 3. Filter relations against cutoff & knownBy.
        List<ResolvedRelation> visibleRelations = new ArrayList<>();
        int undatedRelationCount = 0;
        for (CharacterRelationEntity rel : allRelations) {
            String knownBy = LoreNames.normalizeKnownBy(rel.getKnownBy());
            if (!isKnownVisible(knownBy, asOf, povNames)) {
                continue;
            }
            ChapterSectionEntity sec = rel.getSourceSectionId() != null ? sectionsById.get(rel.getSourceSectionId()) : null;
            Integer sinceCh = rel.getSinceChapterNo();
            long rawOrder = rel.getNarrativeOrder() == null ? 0L : rel.getNarrativeOrder();
            boolean undated;
            long effectiveOrder;
            Integer effectiveChapterNo;

            if (sec != null) {
                undated = false;
                effectiveOrder = sec.getNarrativeOrder() != null
                        ? sec.getNarrativeOrder()
                        : StructureService.narrativeOrder(sec.getChapterNo(), sec.getSectionNo() == null ? 1 : sec.getSectionNo());
                effectiveChapterNo = sinceCh != null && sinceCh > 0 ? sinceCh : sec.getChapterNo();
                if (asOf) {
                    if (effectiveOrder > cutoffOrder) continue;
                    if (sinceCh != null && sinceCh > cutoffChapterNo) continue;
                    if (sec.getChapterNo() != null && sec.getChapterNo() > cutoffChapterNo) continue;
                }
            } else if (sinceCh != null && sinceCh > 0) {
                undated = false;
                effectiveOrder = rawOrder > 0L ? rawOrder : StructureService.narrativeOrder(sinceCh, 1);
                effectiveChapterNo = sinceCh;
                if (asOf) {
                    if (sinceCh > cutoffChapterNo) continue;
                    if (rawOrder > 0L && rawOrder > cutoffOrder) continue;
                }
            } else if (rawOrder > 0L) {
                undated = false;
                effectiveOrder = rawOrder;
                effectiveChapterNo = (int) (rawOrder / 1000L);
                if (asOf && (rawOrder > cutoffOrder || effectiveChapterNo > cutoffChapterNo)) {
                    continue;
                }
            } else {
                undated = true;
                effectiveOrder = 0L;
                effectiveChapterNo = null;
                undatedRelationCount++;
                if (asOf) {
                    continue;
                }
            }

            String srcName = canonicalName(rel.getSourceCharacter(), characterIndex);
            String dstName = canonicalName(rel.getTargetCharacter(), characterIndex);
            if (srcName.isEmpty() || dstName.isEmpty()) continue;

            visibleRelations.add(new ResolvedRelation(
                    rel, sec, srcName, dstName, effectiveChapterNo, effectiveOrder, knownBy, undated));
        }

        // Deduplicate AI_EXTRACTED relations superseded by an AUTHOR relation with the exact same canonical triple.
        Set<String> authorRelationTriples = new LinkedHashSet<>();
        for (ResolvedRelation rel : visibleRelations) {
            if (!AI_EXTRACTED.equalsIgnoreCase(safe(rel.entity().getSource()))) {
                authorRelationTriples.add(rel.sourceName() + "\u0000" + rel.targetName() + "\u0000" + safe(rel.entity().getRelationType()));
            }
        }
        if (!authorRelationTriples.isEmpty()) {
            visibleRelations = visibleRelations.stream()
                    .filter(rel -> !AI_EXTRACTED.equalsIgnoreCase(safe(rel.entity().getSource()))
                            || !authorRelationTriples.contains(
                                    rel.sourceName() + "\u0000" + rel.targetName() + "\u0000" + safe(rel.entity().getRelationType())))
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        visibleRelations.sort(Comparator
                .comparingLong(ResolvedRelation::effectiveOrder)
                .thenComparing(r -> r.entity().getUpdatedAt(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(r -> r.entity().getId(), Comparator.nullsLast(Comparator.naturalOrder())));

        // 4. Filter timeline events against cutoff & knownBy.
        List<VisualTimelineLaneEvent> allVisibleEvents = new ArrayList<>();
        for (TimelineEventEntity ev : allEvents) {
            String knownBy = LoreNames.normalizeKnownBy(ev.getKnownBy());
            if (!isKnownVisible(knownBy, asOf, povNames)) {
                continue;
            }
            ChapterSectionEntity sec = ev.getSourceSectionId() != null ? sectionsById.get(ev.getSourceSectionId()) : null;
            int chNo = sec != null && sec.getChapterNo() != null ? sec.getChapterNo()
                    : (ev.getChapterNo() == null ? 0 : ev.getChapterNo());
            long rawOrder = ev.getNarrativeOrder() == null ? 0L : ev.getNarrativeOrder();
            long effectiveOrder;
            if (sec != null) {
                effectiveOrder = sec.getNarrativeOrder() != null
                        ? sec.getNarrativeOrder()
                        : StructureService.narrativeOrder(chNo, sec.getSectionNo() == null ? 1 : sec.getSectionNo());
                if (asOf) {
                    if (effectiveOrder > cutoffOrder) continue;
                    if (ev.getChapterNo() != null && ev.getChapterNo() > cutoffChapterNo) continue;
                    if (chNo > cutoffChapterNo) continue;
                }
            } else if (rawOrder > 0L) {
                effectiveOrder = rawOrder;
                if (asOf && (rawOrder > cutoffOrder || (chNo > 0 && chNo > cutoffChapterNo))) {
                    continue;
                }
            } else if (chNo > 0) {
                effectiveOrder = StructureService.narrativeOrder(chNo, 1);
                if (asOf && chNo > cutoffChapterNo) continue;
            } else {
                if (asOf) continue;
                effectiveOrder = 0L;
            }

            UUID chapterId = sec != null && sec.getChapterId() != null
                    ? sec.getChapterId()
                    : (chaptersByNo.containsKey(chNo) ? chaptersByNo.get(chNo).getId() : null);
            List<String> involved = canonicalList(ev.getInvolvedCharacters(), characterIndex);
            allVisibleEvents.add(new VisualTimelineLaneEvent(
                    ev.getId(),
                    chNo,
                    chapterId,
                    ev.getSourceSectionId(),
                    effectiveOrder,
                    safe(ev.getEventTitle()),
                    safe(ev.getSummary()),
                    involved,
                    safe(ev.getLocation()),
                    nonBlankOr(ev.getSource(), AUTHOR),
                    knownBy
            ));
        }

        allVisibleEvents.sort(Comparator
                .comparingInt(VisualTimelineLaneEvent::chapterNo)
                .thenComparingLong(VisualTimelineLaneEvent::narrativeOrder)
                .thenComparing(VisualTimelineLaneEvent::id, Comparator.nullsLast(Comparator.naturalOrder())));

        List<VisualTimelineLaneEvent> windowedEvents = windowActive
                ? allVisibleEvents.stream().filter(ev -> axisChapterNos.contains(ev.chapterNo())).toList()
                : allVisibleEvents;

        // 5. Filter & project foreshadowing arcs.
        Map<UUID, ResolvedForeshadowing> visibleForeshadowingById = new LinkedHashMap<>();
        List<VisualForeshadowingArc> allVisibleArcs = new ArrayList<>();
        int maxPlanChapterNo = allChapters.isEmpty() ? cutoffChapterNo
                : Math.max(cutoffChapterNo, Objects.requireNonNullElse(allChapters.getLast().getChapterNo(), 0));

        for (ForeshadowingEntity clue : allForeshadowings) {
            ResolvedForeshadowing projected = projectForeshadowing(
                    clue, asOf, cutoffChapterNo, cutoffOrder, maxPlanChapterNo,
                    windowStart, windowEnd, sectionsById, characterIndex);
            if (projected == null) continue;
            visibleForeshadowingById.put(clue.getId(), projected);
            allVisibleArcs.add(projected.arc());
        }

        allVisibleArcs.sort(Comparator
                .comparing(VisualForeshadowingArc::plantedChapterNo, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(VisualForeshadowingArc::code, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(VisualForeshadowingArc::id, Comparator.nullsLast(Comparator.naturalOrder())));

        List<VisualForeshadowingArc> windowedArcs = windowActive
                ? allVisibleArcs.stream().filter(arc -> arcOverlapsWindow(arc, windowStart, windowEnd,
                        asOf ? cutoffChapterNo : maxPlanChapterNo)).toList()
                : allVisibleArcs;

        // 6. Aggregate credible evidence per character and per chapter.
        Map<String, CharacterEvidenceTracker> evidenceByNodeKey = new LinkedHashMap<>();
        Set<Integer> chaptersWithEvidence = new LinkedHashSet<>();

        for (ResolvedSnapshot snap : visibleSnapshots) {
            CharacterEntity ch = charactersById.get(snap.entity().getCharacterId());
            if (ch == null) continue;
            String key = nodeKeyFor(ch.getName(), characterIndex);
            CharacterEvidenceTracker tracker = evidenceByNodeKey.computeIfAbsent(key, k -> new CharacterEvidenceTracker());
            if (snap.chapterNo() > 0) {
                tracker.recordEvidence(snap.chapterNo(), snap.entity().getSectionId());
                chaptersWithEvidence.add(snap.chapterNo());
            } else {
                tracker.recordEvidence(null, snap.entity().getSectionId());
            }
        }

        for (SectionSummaryEntity summary : visibleSummaries) {
            Integer chNo = summary.getChapterNo();
            if (chNo != null && chNo > 0) chaptersWithEvidence.add(chNo);
            for (String name : canonicalList(summary.getInvolvedCharacters(), characterIndex)) {
                String key = nodeKeyFor(name, characterIndex);
                evidenceByNodeKey.computeIfAbsent(key, k -> new CharacterEvidenceTracker())
                        .recordEvidence(chNo, summary.getSectionId());
            }
        }

        for (ResolvedRelation rel : visibleRelations) {
            if (rel.effectiveChapterNo() != null && rel.effectiveChapterNo() > 0) {
                chaptersWithEvidence.add(rel.effectiveChapterNo());
            }
            String srcKey = nodeKeyFor(rel.sourceName(), characterIndex);
            String dstKey = nodeKeyFor(rel.targetName(), characterIndex);
            evidenceByNodeKey.computeIfAbsent(srcKey, k -> new CharacterEvidenceTracker())
                    .recordRelation(rel.effectiveChapterNo(), rel.entity().getSourceSectionId());
            evidenceByNodeKey.computeIfAbsent(dstKey, k -> new CharacterEvidenceTracker())
                    .recordRelation(rel.effectiveChapterNo(), rel.entity().getSourceSectionId());
        }

        for (VisualTimelineLaneEvent ev : allVisibleEvents) {
            if (ev.chapterNo() > 0) chaptersWithEvidence.add(ev.chapterNo());
            for (String name : ev.involvedCharacters()) {
                String key = nodeKeyFor(name, characterIndex);
                evidenceByNodeKey.computeIfAbsent(key, k -> new CharacterEvidenceTracker())
                        .recordEvidence(ev.chapterNo() > 0 ? ev.chapterNo() : null, ev.sourceSectionId());
            }
        }

        for (VisualForeshadowingArc arc : allVisibleArcs) {
            if (arc.plantedChapterNo() != null && arc.plantedChapterNo() > 0) {
                chaptersWithEvidence.add(arc.plantedChapterNo());
            }
            arc.hintChapters().forEach(chaptersWithEvidence::add);
            if (arc.resolvedChapterNo() != null && arc.resolvedChapterNo() > 0) {
                chaptersWithEvidence.add(arc.resolvedChapterNo());
            }
            for (String name : arc.involvedCharacters()) {
                String key = nodeKeyFor(name, characterIndex);
                CharacterEvidenceTracker tracker = evidenceByNodeKey.computeIfAbsent(key, k -> new CharacterEvidenceTracker());
                if (arc.plantedChapterNo() != null) {
                    tracker.recordEvidence(arc.plantedChapterNo(), arc.plantedSectionId());
                } else {
                    tracker.recordEvidence(null, null);
                }
                for (Integer hintCh : arc.hintChapters()) {
                    tracker.recordEvidence(hintCh, null);
                }
                if (arc.resolvedChapterNo() != null) {
                    tracker.recordEvidence(arc.resolvedChapterNo(), arc.resolvedSectionId());
                }
            }
        }

        // 7. Build VisualGraphNodes (strict anti-spoiler in AS_OF; full cast in PLAN).
        Map<String, VisualGraphNode> nodesById = new LinkedHashMap<>();
        for (CharacterEntity c : allCharacters) {
            String nodeId = c.getId().toString();
            CharacterEvidenceTracker tracker = evidenceByNodeKey.get(nodeId);
            boolean hasEvidence = tracker != null && tracker.hasEvidence();
            if (asOf && !hasEvidence) {
                continue;
            }
            VisualGraphNode node = buildResolvedCharacterNode(
                    c, asOf, latestSnapshotByCharId.get(c.getId()), worldFactions, tracker);
            nodesById.put(nodeId, node);
        }

        // Ensure every visible relation endpoint has a corresponding graph node (unresolved if not in characters).
        for (ResolvedRelation rel : visibleRelations) {
            ensureUnresolvedNodeIfMissing(nodesById, rel.sourceName(), characterIndex, evidenceByNodeKey);
            ensureUnresolvedNodeIfMissing(nodesById, rel.targetName(), characterIndex, evidenceByNodeKey);
        }

        List<VisualGraphNode> nodes = new ArrayList<>(nodesById.values());
        nodes.sort(Comparator
                .comparing(VisualGraphNode::unresolved)
                .thenComparing(Comparator.comparingInt(VisualGraphNode::relationCount).reversed())
                .thenComparing(Comparator.comparingInt(VisualGraphNode::appearanceCount).reversed())
                .thenComparing(VisualGraphNode::name, Comparator.nullsLast(Comparator.naturalOrder())));

        // 8. Build VisualGraphEdges referencing exact node IDs.
        List<VisualGraphEdge> edges = visibleRelations.stream()
                .map(rel -> new VisualGraphEdge(
                        rel.entity().getId(),
                        nodeKeyFor(rel.sourceName(), characterIndex),
                        nodeKeyFor(rel.targetName(), characterIndex),
                        rel.sourceName(),
                        rel.targetName(),
                        safe(rel.entity().getRelationType()),
                        safe(rel.entity().getDescription()),
                        rel.effectiveChapterNo(),
                        rel.entity().getSourceSectionId(),
                        rel.effectiveOrder(),
                        nonBlankOr(rel.entity().getSource(), AUTHOR),
                        rel.knownBy(),
                        rel.entity().getConfidence() == null ? 1.0 : rel.entity().getConfidence(),
                        rel.undated(),
                        rel.entity().getUpdatedAt()
                ))
                .toList();

        // 8b. Optional 1-hop subgraph focus filtering (when focusId is supplied for graph view).
        if (hasText(focusId) && VIEW_GRAPH.equals(view)) {
            String resolvedFocusKey = resolveFocusNodeKey(focusId.strip(), nodesById);
            if (resolvedFocusKey != null) {
                List<VisualGraphEdge> focusEdges = edges.stream()
                        .filter(e -> resolvedFocusKey.equals(e.sourceId()) || resolvedFocusKey.equals(e.targetId()))
                        .toList();
                Set<String> keptNodeIds = new LinkedHashSet<>();
                keptNodeIds.add(resolvedFocusKey);
                for (VisualGraphEdge e : focusEdges) {
                    keptNodeIds.add(e.sourceId());
                    keptNodeIds.add(e.targetId());
                }
                edges = focusEdges;
                nodes = nodes.stream().filter(n -> keptNodeIds.contains(n.id())).toList();
            }
        }

        // 9. Factions (AS_OF strictly from visible snapshot-backed nodes; PLAN may also include world FACTION entries).
        TreeSet<String> factionSet = new TreeSet<>();
        for (VisualGraphNode node : nodes) {
            if (hasText(node.faction())) factionSet.add(node.faction());
        }
        if (!asOf) {
            factionSet.addAll(worldFactions);
        }
        List<String> factions = List.copyOf(factionSet);

        // 10. Entity lanes (CHARACTER + LOCATION) computed on demand for lanes view only.
        List<VisualEntityLane> entityLanes = includeLanes
                ? buildEntityLanes(
                        nodes, windowedEvents, visibleSnapshots, visibleSummaries, windowedArcs, visibleRelations,
                        characterIndex, windowActive ? axisChapterNos : null, cutoffChapterNo)
                : List.of();

        // 11. Anti-spoiler counts & evidence coverage ratio.
        int visibleCharacters = nodes.size();
        int totalCharacters = asOf ? visibleCharacters : Math.max(allCharacters.size(), visibleCharacters);
        double evidenceCoverageRatio = computeCoverageRatio(axisChapters, chaptersWithEvidence);

        VisualProjectionResponse rawResponse = new VisualProjectionResponse(
                bookId,
                view,
                VisualCapabilities.phase7A(),
                cutoff,
                "",
                axisChapters,
                nodes,
                edges,
                factions,
                windowedEvents,
                windowedArcs,
                entityLanes,
                totalCharacters,
                visibleCharacters,
                undatedRelationCount,
                evidenceCoverageRatio
        );
        VisualProjectionResponse response = computeEtag ? withEtag(rawResponse) : rawResponse;

        return new ProjectionBundle(
                response,
                asOf,
                cutoff,
                allCharacters,
                charactersById,
                characterIndex,
                chaptersByNo,
                sectionsById,
                sectionsByChapterId,
                snapshotsByCharId,
                visibleSummaries,
                visibleRelations,
                allVisibleEvents,
                visibleForeshadowingById,
                latestSnapshotByCharId,
                nodesById
        );
    }

    private VisualEntityDetailResponse buildCharacterDetail(ProjectionBundle bundle, String entityId) {
        VisualGraphNode node = findCharacterNode(bundle, entityId);
        if (node == null) {
            throw entityNotFound();
        }
        CharacterEntity rawChar = node.characterId() == null ? null : bundle.charactersById().get(node.characterId());
        CharacterState snapshotState = node.characterId() == null ? null : bundle.latestSnapshotByCharId().get(node.characterId());

        Map<String, Object> visibleFields = new LinkedHashMap<>();
        visibleFields.put("name", node.name());
        visibleFields.put("roleType", node.roleType());
        visibleFields.put("lifecycleStatus", node.lifecycleStatus());
        visibleFields.put("faction", node.faction());
        visibleFields.put("realmOrLevel", node.realmOrLevel());
        visibleFields.put("currentLocation", node.currentLocation());
        visibleFields.put("statusSummary", node.statusSummary());
        visibleFields.put("appearanceCount", node.appearanceCount());
        visibleFields.put("relationCount", node.relationCount());
        visibleFields.put("firstSeenChapterNo", node.firstSeenChapterNo());
        visibleFields.put("lastSeenChapterNo", node.lastSeenChapterNo());
        visibleFields.put("unresolved", node.unresolved());

        if (!bundle.asOf() && rawChar != null) {
            visibleFields.put("aliases", safe(rawChar.getAliases()));
            visibleFields.put("personality", safe(rawChar.getPersonality()));
            visibleFields.put("inventory", safe(rawChar.getInventory()));
            visibleFields.put("arcScope", safe(rawChar.getArcScope()));
            visibleFields.put("customAttributes", CharacterState.readCustomAttributes(rawChar.getCustomAttributes()));
        } else if (bundle.asOf() && snapshotState != null) {
            visibleFields.put("aliases", safe(snapshotState.aliases()));
            visibleFields.put("personality", safe(snapshotState.personality()));
            visibleFields.put("inventory", safe(snapshotState.inventory()));
            visibleFields.put("arcScope", safe(snapshotState.arcScope()));
            visibleFields.put("customAttributes", snapshotState.customAttributes());
        }

        Map<Integer, Integer> heatCounts = new TreeMap<>();
        List<VisualEvidenceItem> evidence = new ArrayList<>();

        if (node.characterId() != null) {
            List<ResolvedSnapshot> charSnaps = bundle.snapshotsByCharId().getOrDefault(node.characterId(), List.of());
            for (ResolvedSnapshot snap : charSnaps) {
                if (snap.chapterNo() > 0) {
                    heatCounts.merge(snap.chapterNo(), 1, Integer::sum);
                }
                ChapterEntity ch = bundle.chaptersByNo().get(snap.chapterNo());
                String snippet = snap.state() == null ? "" : formatStateSnippet(snap.state());
                evidence.add(new VisualEvidenceItem(
                        "SNAPSHOT",
                        snap.chapterNo() > 0 ? snap.chapterNo() : null,
                        ch == null ? null : ch.getId(),
                        snap.entity().getSectionId(),
                        "角色状态快照",
                        snippet
                ));
            }
        }

        for (SectionSummaryEntity summary : bundle.visibleSummaries()) {
            List<String> chars = canonicalList(summary.getInvolvedCharacters(), bundle.characterIndex());
            if (!chars.contains(node.name())) continue;
            if (summary.getChapterNo() != null && summary.getChapterNo() > 0) {
                heatCounts.merge(summary.getChapterNo(), 1, Integer::sum);
            }
            evidence.add(new VisualEvidenceItem(
                    "SUMMARY",
                    summary.getChapterNo(),
                    summary.getChapterId(),
                    summary.getSectionId(),
                    nonBlankOr(summary.getSectionTitle(), nonBlankOr(summary.getChapterTitle(), "小节摘要")),
                    safe(summary.getSummaryText())
            ));
        }

        for (VisualTimelineLaneEvent ev : bundle.allVisibleEvents()) {
            if (!ev.involvedCharacters().contains(node.name())) continue;
            if (ev.chapterNo() > 0) {
                heatCounts.merge(ev.chapterNo(), 1, Integer::sum);
            }
            evidence.add(new VisualEvidenceItem(
                    "EVENT",
                    ev.chapterNo() > 0 ? ev.chapterNo() : null,
                    ev.chapterId(),
                    ev.sourceSectionId(),
                    ev.eventTitle(),
                    ev.summary()
            ));
        }

        List<VisualGraphEdge> relatedEdges = bundle.response().edges().stream()
                .filter(edge -> edge.sourceId().equals(node.id()) || edge.targetId().equals(node.id()))
                .toList();
        for (VisualGraphEdge edge : relatedEdges) {
            if (edge.sinceChapterNo() != null && edge.sinceChapterNo() > 0) {
                heatCounts.merge(edge.sinceChapterNo(), 1, Integer::sum);
            }
            ChapterEntity ch = edge.sinceChapterNo() == null ? null : bundle.chaptersByNo().get(edge.sinceChapterNo());
            evidence.add(new VisualEvidenceItem(
                    "RELATION",
                    edge.sinceChapterNo(),
                    ch == null ? null : ch.getId(),
                    edge.sourceSectionId(),
                    edge.sourceName() + " -[" + edge.relationType() + "]-> " + edge.targetName(),
                    edge.description()
            ));
        }

        List<VisualForeshadowingArc> relatedArcs = bundle.response().arcs().stream()
                .filter(arc -> arc.involvedCharacters().contains(node.name()))
                .toList();
        for (VisualForeshadowingArc arc : relatedArcs) {
            if (arc.plantedChapterNo() != null && arc.plantedChapterNo() > 0) {
                heatCounts.merge(arc.plantedChapterNo(), 1, Integer::sum);
            }
            ChapterEntity ch = arc.plantedChapterNo() == null ? null : bundle.chaptersByNo().get(arc.plantedChapterNo());
            evidence.add(new VisualEvidenceItem(
                    "FORESHADOWING",
                    arc.plantedChapterNo(),
                    ch == null ? null : ch.getId(),
                    arc.plantedSectionId(),
                    (hasText(arc.code()) ? arc.code() + " " : "") + arc.title(),
                    arc.surfaceClue()
            ));
        }

        evidence.sort(Comparator.comparing(VisualEvidenceItem::chapterNo, Comparator.nullsLast(Comparator.naturalOrder())));

        return new VisualEntityDetailResponse(
                "character",
                node.id(),
                node.name(),
                nonBlankOr(node.faction(), node.roleType()),
                bundle.cutoff().visibility(),
                visibleFields,
                buildHeatCells(heatCounts, bundle),
                evidence,
                relatedEdges,
                relatedArcs,
                !node.unresolved()
        );
    }

    private VisualEntityDetailResponse buildForeshadowingDetail(ProjectionBundle bundle, String entityId) {
        UUID clueId = parseUuidOrNull(entityId);
        if (clueId == null) throw entityNotFound();
        ResolvedForeshadowing resolved = bundle.visibleForeshadowingById().get(clueId);
        if (resolved == null) throw entityNotFound();

        VisualForeshadowingArc arc = resolved.arc();
        ForeshadowingEntity raw = resolved.entity();

        Map<String, Object> visibleFields = new LinkedHashMap<>();
        visibleFields.put("code", arc.code());
        visibleFields.put("title", arc.title());
        visibleFields.put("surfaceClue", arc.surfaceClue());
        visibleFields.put("importance", arc.importance());
        visibleFields.put("status", arc.status());
        visibleFields.put("alertState", arc.alertState());
        visibleFields.put("plantedChapterNo", arc.plantedChapterNo());
        visibleFields.put("plantedSectionId", arc.plantedSectionId());
        visibleFields.put("resolvedChapterNo", arc.resolvedChapterNo());
        visibleFields.put("resolvedSectionId", arc.resolvedSectionId());
        visibleFields.put("hintChapters", arc.hintChapters());
        visibleFields.put("involvedCharacters", arc.involvedCharacters());
        visibleFields.put("openEnded", arc.openEnded());
        visibleFields.put("allowedDirectorModes", arc.allowedDirectorModes());
        if (!bundle.asOf()) {
            visibleFields.put("hiddenTruth", arc.hiddenTruth());
            visibleFields.put("targetChapterMin", arc.targetChapterMin());
            visibleFields.put("targetChapterMax", arc.targetChapterMax());
            visibleFields.put("revealPolicy", nonBlankOr(raw.getRevealPolicy(), "HIDE_TRUTH"));
            visibleFields.put("authorLocked", Boolean.TRUE.equals(raw.getAuthorLocked()));
        }

        Map<Integer, Integer> heatCounts = new TreeMap<>();
        List<VisualEvidenceItem> evidence = new ArrayList<>();
        if (arc.plantedChapterNo() != null && arc.plantedChapterNo() > 0) {
            heatCounts.merge(arc.plantedChapterNo(), 1, Integer::sum);
            ChapterEntity ch = bundle.chaptersByNo().get(arc.plantedChapterNo());
            String quote = bundle.asOf()
                    ? sanitizeSurface(safe(raw.getPlantedQuote()), raw.getHiddenTruth())
                    : safe(raw.getPlantedQuote());
            evidence.add(new VisualEvidenceItem(
                    "PLANTED",
                    arc.plantedChapterNo(),
                    ch == null ? null : ch.getId(),
                    arc.plantedSectionId(),
                    "埋设锚点",
                    nonBlankOr(quote, arc.surfaceClue())
            ));
        }
        for (ParsedHint hint : resolved.visibleHints()) {
            if (hint.chapterNo() > 0) {
                heatCounts.merge(hint.chapterNo(), 1, Integer::sum);
            }
            ChapterEntity ch = bundle.chaptersByNo().get(hint.chapterNo());
            String snippet = nonBlankOr(hint.quote(), hint.note());
            if (bundle.asOf()) {
                snippet = sanitizeSurface(snippet, raw.getHiddenTruth());
            }
            evidence.add(new VisualEvidenceItem(
                    "HINT",
                    hint.chapterNo(),
                    ch == null ? null : ch.getId(),
                    null,
                    "伏笔照应",
                    snippet
            ));
        }
        if (arc.resolvedChapterNo() != null && arc.resolvedChapterNo() > 0) {
            heatCounts.merge(arc.resolvedChapterNo(), 1, Integer::sum);
            ChapterEntity ch = bundle.chaptersByNo().get(arc.resolvedChapterNo());
            evidence.add(new VisualEvidenceItem(
                    "RESOLVED",
                    arc.resolvedChapterNo(),
                    ch == null ? null : ch.getId(),
                    arc.resolvedSectionId(),
                    "实际回收",
                    arc.surfaceClue()
            ));
        }

        Set<String> involved = Set.copyOf(arc.involvedCharacters());
        List<VisualGraphEdge> relatedEdges = bundle.response().edges().stream()
                .filter(edge -> involved.contains(edge.sourceName()) || involved.contains(edge.targetName()))
                .toList();

        return new VisualEntityDetailResponse(
                "foreshadowing",
                arc.id().toString(),
                arc.title(),
                (hasText(arc.code()) ? arc.code() + " · " : "") + arc.importance(),
                bundle.cutoff().visibility(),
                visibleFields,
                buildHeatCells(heatCounts, bundle),
                evidence,
                relatedEdges,
                List.of(arc),
                true
        );
    }

    private VisualEntityDetailResponse buildEventDetail(ProjectionBundle bundle, String entityId) {
        UUID eventId = parseUuidOrNull(entityId);
        if (eventId == null) throw entityNotFound();
        VisualTimelineLaneEvent ev = bundle.allVisibleEvents().stream()
                .filter(item -> eventId.equals(item.id()))
                .findFirst()
                .orElseThrow(VisualProjectionService::entityNotFound);

        Map<String, Object> visibleFields = new LinkedHashMap<>();
        visibleFields.put("chapterNo", ev.chapterNo());
        visibleFields.put("chapterId", ev.chapterId());
        visibleFields.put("sourceSectionId", ev.sourceSectionId());
        visibleFields.put("narrativeOrder", ev.narrativeOrder());
        visibleFields.put("summary", ev.summary());
        visibleFields.put("involvedCharacters", ev.involvedCharacters());
        visibleFields.put("location", ev.location());
        visibleFields.put("source", ev.source());
        visibleFields.put("knownBy", ev.knownBy());

        Map<Integer, Integer> heatCounts = new TreeMap<>();
        if (ev.chapterNo() > 0) {
            heatCounts.put(ev.chapterNo(), 1);
        }
        List<VisualEvidenceItem> evidence = List.of(new VisualEvidenceItem(
                "EVENT",
                ev.chapterNo() > 0 ? ev.chapterNo() : null,
                ev.chapterId(),
                ev.sourceSectionId(),
                ev.eventTitle(),
                ev.summary()
        ));

        Set<String> involved = Set.copyOf(ev.involvedCharacters());
        List<VisualGraphEdge> relatedEdges = bundle.response().edges().stream()
                .filter(edge -> involved.contains(edge.sourceName()) || involved.contains(edge.targetName()))
                .toList();
        List<VisualForeshadowingArc> relatedArcs = bundle.response().arcs().stream()
                .filter(arc -> arc.involvedCharacters().stream().anyMatch(involved::contains))
                .toList();

        String subtitle = (ev.chapterNo() > 0 ? "第 " + ev.chapterNo() + " 章" : "时间线事件")
                + (hasText(ev.location()) ? " · " + ev.location() : "");
        return new VisualEntityDetailResponse(
                "event",
                ev.id().toString(),
                ev.eventTitle(),
                subtitle,
                bundle.cutoff().visibility(),
                visibleFields,
                buildHeatCells(heatCounts, bundle),
                evidence,
                relatedEdges,
                relatedArcs,
                !AI_EXTRACTED.equalsIgnoreCase(ev.source())
        );
    }

    private VisualEntityDetailResponse buildRelationDetail(ProjectionBundle bundle, String entityId) {
        UUID relationId = parseUuidOrNull(entityId);
        if (relationId == null) throw entityNotFound();
        VisualGraphEdge edge = bundle.response().edges().stream()
                .filter(item -> relationId.equals(item.id()))
                .findFirst()
                .orElseThrow(VisualProjectionService::entityNotFound);

        Map<String, Object> visibleFields = new LinkedHashMap<>();
        visibleFields.put("sourceId", edge.sourceId());
        visibleFields.put("targetId", edge.targetId());
        visibleFields.put("sourceName", edge.sourceName());
        visibleFields.put("targetName", edge.targetName());
        visibleFields.put("relationType", edge.relationType());
        visibleFields.put("description", edge.description());
        visibleFields.put("sinceChapterNo", edge.sinceChapterNo());
        visibleFields.put("sourceSectionId", edge.sourceSectionId());
        visibleFields.put("narrativeOrder", edge.narrativeOrder());
        visibleFields.put("source", edge.source());
        visibleFields.put("knownBy", edge.knownBy());
        visibleFields.put("confidence", edge.confidence());
        visibleFields.put("undated", edge.undated());
        visibleFields.put("updatedAt", edge.updatedAt());

        Map<Integer, Integer> heatCounts = new TreeMap<>();
        ChapterEntity ch = null;
        if (edge.sinceChapterNo() != null && edge.sinceChapterNo() > 0) {
            heatCounts.put(edge.sinceChapterNo(), 1);
            ch = bundle.chaptersByNo().get(edge.sinceChapterNo());
        }
        String title = edge.sourceName() + " → " + edge.targetName();
        List<VisualEvidenceItem> evidence = List.of(new VisualEvidenceItem(
                "RELATION",
                edge.sinceChapterNo(),
                ch == null ? null : ch.getId(),
                edge.sourceSectionId(),
                title + "（" + edge.relationType() + "）",
                edge.description()
        ));

        Set<String> endpoints = Set.of(edge.sourceName(), edge.targetName());
        List<VisualForeshadowingArc> relatedArcs = bundle.response().arcs().stream()
                .filter(arc -> arc.involvedCharacters().stream().anyMatch(endpoints::contains))
                .toList();

        return new VisualEntityDetailResponse(
                "relation",
                edge.id().toString(),
                title,
                edge.relationType(),
                bundle.cutoff().visibility(),
                visibleFields,
                buildHeatCells(heatCounts, bundle),
                evidence,
                List.of(edge),
                relatedArcs,
                !AI_EXTRACTED.equalsIgnoreCase(edge.source())
        );
    }

    private static String resolveFocusNodeKey(String rawFocusId, Map<String, VisualGraphNode> nodesById) {
        if (nodesById.containsKey(rawFocusId)) return rawFocusId;
        String stripped = rawFocusId;
        if (stripped.startsWith("char:")) stripped = stripped.substring("char:".length());
        else if (stripped.startsWith("character:")) stripped = stripped.substring("character:".length());
        if (nodesById.containsKey(stripped)) return stripped;
        UUID uuid = parseUuidOrNull(stripped);
        if (uuid != null && nodesById.containsKey(uuid.toString())) {
            return uuid.toString();
        }
        return null;
    }

    private static VisualGraphNode findCharacterNode(ProjectionBundle bundle, String entityId) {
        VisualGraphNode direct = bundle.nodesById().get(entityId);
        if (direct != null) return direct;
        String stripped = entityId;
        if (stripped.startsWith("char:")) stripped = stripped.substring("char:".length());
        else if (stripped.startsWith("character:")) stripped = stripped.substring("character:".length());
        direct = bundle.nodesById().get(stripped);
        if (direct != null) return direct;
        UUID uuid = parseUuidOrNull(stripped);
        if (uuid != null) {
            return bundle.nodesById().get(uuid.toString());
        }
        return null;
    }

    private static List<VisualHeatCell> buildHeatCells(Map<Integer, Integer> heatCounts, ProjectionBundle bundle) {
        List<VisualHeatCell> cells = new ArrayList<>();
        for (var entry : heatCounts.entrySet()) {
            int chNo = entry.getKey();
            if (bundle.asOf() && chNo > bundle.cutoff().chapterNo()) continue;
            ChapterEntity ch = bundle.chaptersByNo().get(chNo);
            UUID chapterId = ch == null ? null : ch.getId();
            UUID firstSectionId = null;
            if (chapterId != null) {
                List<ChapterSectionEntity> secs = bundle.sectionsByChapterId().getOrDefault(chapterId, List.of());
                if (!secs.isEmpty()) firstSectionId = secs.getFirst().getId();
            }
            cells.add(new VisualHeatCell(
                    chNo,
                    chapterId,
                    firstSectionId,
                    ch == null ? "第" + chNo + "章" : safe(ch.getTitle()),
                    entry.getValue()
            ));
        }
        return cells;
    }

    private static VisualCutoffInfo computeCutoff(
            String visibility,
            List<ChapterEntity> allChapters,
            Map<UUID, ChapterEntity> chaptersById,
            UUID asOfChapterId,
            ChapterSectionEntity cutoffSection
    ) {
        int totalChapters = allChapters.size();
        if (cutoffSection != null) {
            int chNo = cutoffSection.getChapterNo() == null ? 0 : cutoffSection.getChapterNo();
            Integer secNo = cutoffSection.getSectionNo();
            long order = cutoffSection.getNarrativeOrder() != null
                    ? cutoffSection.getNarrativeOrder()
                    : StructureService.narrativeOrder(chNo, secNo == null ? 1 : secNo);
            return new VisualCutoffInfo(
                    visibility,
                    cutoffSection.getChapterId(),
                    chNo,
                    cutoffSection.getId(),
                    secNo,
                    order,
                    totalChapters
            );
        }
        if (asOfChapterId != null) {
            ChapterEntity chapter = chaptersById.get(asOfChapterId);
            int chNo = chapter.getChapterNo() == null ? 0 : chapter.getChapterNo();
            return new VisualCutoffInfo(
                    visibility,
                    chapter.getId(),
                    chNo,
                    null,
                    null,
                    (long) chNo * 1000L + 999L,
                    totalChapters
            );
        }
        if (!allChapters.isEmpty()) {
            ChapterEntity latest = allChapters.getLast();
            int chNo = latest.getChapterNo() == null ? 0 : latest.getChapterNo();
            return new VisualCutoffInfo(
                    visibility,
                    latest.getId(),
                    chNo,
                    null,
                    null,
                    (long) chNo * 1000L + 999L,
                    totalChapters
            );
        }
        return new VisualCutoffInfo(visibility, null, 0, null, null, 0L, 0);
    }

    private static VisualChapterAxisItem toChapterAxisItem(
            ChapterEntity chapter,
            Map<UUID, VolumeEntity> volumesById,
            VolumeEntity firstVolume,
            Map<UUID, List<ChapterSectionEntity>> sectionsByChapterId
    ) {
        VolumeEntity volume = chapter.getVolumeId() != null ? volumesById.get(chapter.getVolumeId()) : null;
        if (volume == null) volume = firstVolume;
        List<ChapterSectionEntity> secs = sectionsByChapterId.getOrDefault(chapter.getId(), List.of());
        UUID firstSectionId = secs.isEmpty() ? null : secs.getFirst().getId();
        return new VisualChapterAxisItem(
                chapter.getId(),
                volume == null ? null : volume.getId(),
                volume == null || volume.getVolumeNo() == null ? 1 : volume.getVolumeNo(),
                volume == null || !hasText(volume.getTitle()) ? DEFAULT_VOLUME_TITLE : volume.getTitle().trim(),
                chapter.getChapterNo() == null ? 0 : chapter.getChapterNo(),
                safe(chapter.getTitle()),
                chapter.getWordCount() == null ? 0 : chapter.getWordCount(),
                nonBlankOr(chapter.getStatus(), "DRAFT"),
                secs.size(),
                firstSectionId
        );
    }

    private static VisualGraphNode buildResolvedCharacterNode(
            CharacterEntity c,
            boolean asOf,
            CharacterState snapshot,
            List<String> worldFactions,
            CharacterEvidenceTracker tracker
    ) {
        String roleType;
        String lifecycleStatus;
        String faction;
        String realmOrLevel;
        String currentLocation;
        String statusSummary;

        if (asOf) {
            if (snapshot != null) {
                roleType = nonBlankOr(snapshot.roleType(), nonBlankOr(c.getRoleType(), "SUPPORTING"));
                lifecycleStatus = nonBlankOr(snapshot.lifecycleStatus(), "ACTIVE");
                faction = extractFaction(snapshot.customAttributes(), null, List.of());
                realmOrLevel = nonBlankOr(snapshot.realmOrLevel(), safe(snapshot.customAttributes().get("realm")));
                currentLocation = safe(snapshot.currentLocation());
                statusSummary = nonBlankOr(snapshot.statusNotes(), formatStateSnippet(snapshot));
            } else {
                roleType = nonBlankOr(c.getRoleType(), "SUPPORTING");
                lifecycleStatus = "UNKNOWN";
                faction = "";
                realmOrLevel = "";
                currentLocation = "";
                statusSummary = UNKNOWN_STATUS_SUMMARY;
            }
        } else {
            Map<String, String> attrs = CharacterState.readCustomAttributes(c.getCustomAttributes());
            roleType = nonBlankOr(c.getRoleType(), "SUPPORTING");
            lifecycleStatus = nonBlankOr(c.getLifecycleStatus(),
                    "PROTAGONIST".equals(roleType) ? "CORE_PINNED" : "ACTIVE");
            faction = extractFaction(attrs, c, worldFactions);
            realmOrLevel = nonBlankOr(c.getRealmOrLevel(), safe(attrs.get("realm")));
            currentLocation = safe(c.getCurrentLocation());
            statusSummary = safe(c.getStatusNotes());
        }

        int appearanceCount = tracker == null ? 0 : tracker.appearanceCount();
        int relationCount = tracker == null ? 0 : tracker.relationCount();
        Integer firstSeen = tracker == null ? null : tracker.firstSeenChapterNo();
        Integer lastSeen = tracker == null ? null : tracker.lastSeenChapterNo();

        return new VisualGraphNode(
                c.getId().toString(),
                c.getId(),
                safe(c.getName()),
                roleType,
                lifecycleStatus,
                faction,
                realmOrLevel,
                currentLocation,
                statusSummary,
                appearanceCount,
                relationCount,
                firstSeen,
                lastSeen,
                false
        );
    }

    private static void ensureUnresolvedNodeIfMissing(
            Map<String, VisualGraphNode> nodesById,
            String canonicalName,
            Map<String, CharacterEntity> characterIndex,
            Map<String, CharacterEvidenceTracker> evidenceByNodeKey
    ) {
        String key = nodeKeyFor(canonicalName, characterIndex);
        if (nodesById.containsKey(key)) return;
        CharacterEvidenceTracker tracker = evidenceByNodeKey.get(key);
        nodesById.put(key, new VisualGraphNode(
                key,
                null,
                canonicalName,
                "SUPPORTING",
                "UNKNOWN",
                "",
                "",
                "",
                "未建档角色",
                tracker == null ? 0 : tracker.appearanceCount(),
                tracker == null ? 0 : tracker.relationCount(),
                tracker == null ? null : tracker.firstSeenChapterNo(),
                tracker == null ? null : tracker.lastSeenChapterNo(),
                true
        ));
    }

    private static ResolvedForeshadowing projectForeshadowing(
            ForeshadowingEntity clue,
            boolean asOf,
            int cutoffChapterNo,
            long cutoffOrder,
            int maxPlanChapterNo,
            Integer windowStart,
            Integer windowEnd,
            Map<UUID, ChapterSectionEntity> sectionsById,
            Map<String, CharacterEntity> characterIndex
    ) {
        ChapterSectionEntity plantedSec = clue.getPlantedSectionId() != null
                ? sectionsById.get(clue.getPlantedSectionId()) : null;
        Integer plantedChapterNo = plantedSec != null && plantedSec.getChapterNo() != null
                ? plantedSec.getChapterNo() : clue.getPlantedChapterNo();
        Long plantedOrder = plantedSec != null && plantedSec.getNarrativeOrder() != null
                ? plantedSec.getNarrativeOrder()
                : (clue.getPlantedNarrativeOrder() != null && clue.getPlantedNarrativeOrder() > 0L
                        ? clue.getPlantedNarrativeOrder()
                        : (plantedChapterNo != null && plantedChapterNo > 0
                                ? StructureService.narrativeOrder(plantedChapterNo, 1) : null));

        if (asOf) {
            if (plantedChapterNo == null || plantedChapterNo <= 0 || plantedChapterNo > cutoffChapterNo) {
                return null;
            }
            if (plantedOrder != null && plantedOrder > cutoffOrder) {
                return null;
            }
            if (clue.getPlantedNarrativeOrder() != null && clue.getPlantedNarrativeOrder() > cutoffOrder) {
                return null;
            }
        }

        List<ParsedHint> allHints = parseHints(clue.getHintHistoryJson());
        List<ParsedHint> visibleHints = allHints.stream()
                .filter(h -> !asOf || (h.chapterNo() <= cutoffChapterNo
                        && (h.sectionNo() == null || h.order() <= cutoffOrder)))
                .toList();
        List<Integer> hintChapters = visibleHints.stream()
                .map(ParsedHint::chapterNo)
                .distinct()
                .sorted()
                .toList();

        ChapterSectionEntity resolvedSec = clue.getResolvedSectionId() != null
                ? sectionsById.get(clue.getResolvedSectionId()) : null;
        Integer rawResolvedChapterNo = resolvedSec != null && resolvedSec.getChapterNo() != null
                ? resolvedSec.getChapterNo() : clue.getResolvedChapterNo();
        Long resolvedOrder = resolvedSec != null && resolvedSec.getNarrativeOrder() != null
                ? resolvedSec.getNarrativeOrder()
                : (rawResolvedChapterNo != null && rawResolvedChapterNo > 0
                        ? StructureService.narrativeOrder(rawResolvedChapterNo, 1) : null);

        String rawStatus = normalizeForeshadowingStatus(clue.getStatus(), plantedChapterNo);
        String rawSurface = nonBlankOr(clue.getSurfaceClue(), safe(clue.getDescription()));
        String rawTruth = safe(clue.getHiddenTruth());
        List<String> involved = canonicalList(clue.getInvolvedCharacters(), characterIndex);

        String title;
        String surfaceClue;
        String hiddenTruth;
        String status;
        String alertState;
        Integer targetMin;
        Integer targetMax;
        Integer resolvedChapterNo;
        UUID resolvedSectionId;
        boolean openEnded;
        List<String> allowedDirectorModes;

        if (asOf) {
            title = sanitizeSurface(safe(clue.getTitle()), rawTruth);
            surfaceClue = sanitizeSurface(rawSurface, rawTruth);
            hiddenTruth = null;
            targetMin = null;
            targetMax = null;

            boolean resolvedByCutoff = ("RESOLVED".equals(rawStatus) || rawResolvedChapterNo != null)
                    && rawResolvedChapterNo != null
                    && rawResolvedChapterNo > 0
                    && rawResolvedChapterNo <= cutoffChapterNo
                    && (resolvedOrder == null || resolvedOrder <= cutoffOrder);

            if (resolvedByCutoff) {
                resolvedChapterNo = rawResolvedChapterNo;
                resolvedSectionId = clue.getResolvedSectionId();
                status = "RESOLVED";
                alertState = "RESOLVED";
                openEnded = false;
                allowedDirectorModes = List.of();
            } else {
                resolvedChapterNo = null;
                resolvedSectionId = null;
                status = hintChapters.isEmpty() ? "PLANTED" : "HINTED";
                alertState = "OPEN";
                openEnded = true;
                allowedDirectorModes = List.of("HINT", "PAYOFF");
            }
        } else {
            title = safe(clue.getTitle());
            surfaceClue = rawSurface;
            hiddenTruth = rawTruth;
            targetMin = clue.getTargetChapterMin();
            targetMax = clue.getTargetChapterMax();
            resolvedChapterNo = rawResolvedChapterNo;
            resolvedSectionId = clue.getResolvedSectionId();
            status = rawStatus;
            openEnded = !"RESOLVED".equals(rawStatus) && !"ABANDONED".equals(rawStatus) && resolvedChapterNo == null;

            if ("RESOLVED".equals(rawStatus)) {
                alertState = "RESOLVED";
                allowedDirectorModes = List.of();
            } else if ("ABANDONED".equals(rawStatus)) {
                alertState = "ABANDONED";
                allowedDirectorModes = List.of();
            } else if (targetMax != null && cutoffChapterNo > targetMax) {
                alertState = "OVERDUE";
                allowedDirectorModes = "PLANNED".equals(rawStatus) ? List.of("PLANT") : List.of("HINT", "PAYOFF");
            } else if (targetMin != null && cutoffChapterNo >= targetMin) {
                alertState = "DUE";
                allowedDirectorModes = "PLANNED".equals(rawStatus) ? List.of("PLANT") : List.of("HINT", "PAYOFF");
            } else if ("PLANNED".equals(rawStatus)) {
                alertState = "PLANNED";
                allowedDirectorModes = List.of("PLANT");
            } else {
                alertState = "OPEN";
                allowedDirectorModes = List.of("HINT", "PAYOFF");
            }
        }

        Integer arcMin = minChapter(plantedChapterNo, hintChapters, targetMin, resolvedChapterNo);
        Integer arcMax = maxChapter(plantedChapterNo, hintChapters, targetMax, resolvedChapterNo);
        int horizonChapter = asOf ? cutoffChapterNo : maxPlanChapterNo;
        boolean continuesLeft = windowStart != null && arcMin != null && arcMin < windowStart;
        boolean continuesRight;
        if (windowEnd == null) {
            continuesRight = false;
        } else if (resolvedChapterNo != null) {
            continuesRight = resolvedChapterNo > windowEnd;
        } else if (openEnded) {
            continuesRight = (arcMax != null && arcMax > windowEnd) || horizonChapter > windowEnd;
        } else {
            continuesRight = arcMax != null && arcMax > windowEnd;
        }

        VisualForeshadowingArc arc = new VisualForeshadowingArc(
                clue.getId(),
                safe(clue.getCode()),
                title,
                surfaceClue,
                hiddenTruth,
                nonBlankOr(clue.getImportance(), "SUBPLOT"),
                status,
                alertState,
                plantedChapterNo,
                clue.getPlantedSectionId(),
                targetMin,
                targetMax,
                resolvedChapterNo,
                resolvedSectionId,
                hintChapters,
                involved,
                continuesLeft,
                continuesRight,
                openEnded,
                allowedDirectorModes
        );
        return new ResolvedForeshadowing(clue, arc, visibleHints);
    }

    private static boolean arcOverlapsWindow(
            VisualForeshadowingArc arc, Integer windowStart, Integer windowEnd, int horizonChapter
    ) {
        if (windowStart == null || windowEnd == null) return true;
        Integer minCh = minChapter(arc.plantedChapterNo(), arc.hintChapters(), arc.targetChapterMin(), arc.resolvedChapterNo());
        if (minCh == null) return false;
        Integer maxCh = maxChapter(arc.plantedChapterNo(), arc.hintChapters(), arc.targetChapterMax(), arc.resolvedChapterNo());
        int effectiveMax = arc.openEnded() ? Math.max(horizonChapter, maxCh == null ? minCh : maxCh) : (maxCh == null ? minCh : maxCh);
        return minCh <= windowEnd && effectiveMax >= windowStart;
    }

    private static List<VisualEntityLane> buildEntityLanes(
            List<VisualGraphNode> nodes,
            List<VisualTimelineLaneEvent> windowedEvents,
            List<ResolvedSnapshot> visibleSnapshots,
            List<SectionSummaryEntity> visibleSummaries,
            List<VisualForeshadowingArc> windowedArcs,
            List<ResolvedRelation> visibleRelations,
            Map<String, CharacterEntity> characterIndex,
            Set<Integer> allowedChapters,
            int cutoffChapterNo
    ) {
        Map<String, LaneAccumulator> charLanes = new LinkedHashMap<>();
        Map<String, VisualGraphNode> nodeByName = new LinkedHashMap<>();
        Map<UUID, VisualGraphNode> nodeByCharId = new HashMap<>();
        for (VisualGraphNode node : nodes) {
            nodeByName.put(node.name(), node);
            if (node.characterId() != null) {
                nodeByCharId.put(node.characterId(), node);
            }
        }

        for (VisualTimelineLaneEvent ev : windowedEvents) {
            if (ev.chapterNo() <= 0) continue;
            for (String charName : ev.involvedCharacters()) {
                VisualGraphNode node = nodeByName.get(charName);
                String laneId = node != null && node.characterId() != null
                        ? "character:" + node.characterId() : "character:" + charName;
                String tag = node != null ? nonBlankOr(node.faction(), node.roleType()) : "SUPPORTING";
                charLanes.computeIfAbsent(laneId, id -> new LaneAccumulator(id, "CHARACTER", charName, tag))
                        .addEvent(ev.chapterNo(), ev.id().toString());
            }
        }

        for (ResolvedSnapshot snap : visibleSnapshots) {
            if (snap.chapterNo() <= 0) continue;
            if (allowedChapters != null && !allowedChapters.contains(snap.chapterNo())) continue;
            VisualGraphNode node = nodeByCharId.get(snap.entity().getCharacterId());
            if (node == null) continue;
            String laneId = "character:" + node.characterId();
            charLanes.computeIfAbsent(laneId, id -> new LaneAccumulator(id, "CHARACTER", node.name(),
                    nonBlankOr(node.faction(), node.roleType())))
                    .addChapter(snap.chapterNo());
        }

        for (SectionSummaryEntity summary : visibleSummaries) {
            Integer chNo = summary.getChapterNo();
            if (chNo == null || chNo <= 0) continue;
            if (allowedChapters != null && !allowedChapters.contains(chNo)) continue;
            for (String charName : canonicalList(summary.getInvolvedCharacters(), characterIndex)) {
                VisualGraphNode node = nodeByName.get(charName);
                String laneId = node != null && node.characterId() != null
                        ? "character:" + node.characterId() : "character:" + charName;
                String tag = node != null ? nonBlankOr(node.faction(), node.roleType()) : "SUPPORTING";
                charLanes.computeIfAbsent(laneId, id -> new LaneAccumulator(id, "CHARACTER", charName, tag))
                        .addChapter(chNo);
            }
        }

        for (VisualForeshadowingArc arc : windowedArcs) {
            for (String charName : arc.involvedCharacters()) {
                VisualGraphNode node = nodeByName.get(charName);
                String laneId = node != null && node.characterId() != null
                        ? "character:" + node.characterId() : "character:" + charName;
                String tag = node != null ? nonBlankOr(node.faction(), node.roleType()) : "SUPPORTING";
                LaneAccumulator acc = charLanes.computeIfAbsent(laneId,
                        id -> new LaneAccumulator(id, "CHARACTER", charName, tag));
                if (arc.plantedChapterNo() != null && arc.plantedChapterNo() > 0
                        && (allowedChapters == null || allowedChapters.contains(arc.plantedChapterNo()))) {
                    acc.addChapter(arc.plantedChapterNo());
                }
                for (Integer h : arc.hintChapters()) {
                    if (allowedChapters == null || allowedChapters.contains(h)) acc.addChapter(h);
                }
                if (arc.resolvedChapterNo() != null && arc.resolvedChapterNo() > 0
                        && (allowedChapters == null || allowedChapters.contains(arc.resolvedChapterNo()))) {
                    acc.addChapter(arc.resolvedChapterNo());
                }
            }
        }

        for (ResolvedRelation rel : visibleRelations) {
            Integer chNo = rel.effectiveChapterNo();
            if (chNo == null || chNo <= 0) continue;
            if (allowedChapters != null && !allowedChapters.contains(chNo)) continue;
            for (String charName : List.of(rel.sourceName(), rel.targetName())) {
                VisualGraphNode node = nodeByName.get(charName);
                String laneId = node != null && node.characterId() != null
                        ? "character:" + node.characterId() : "character:" + charName;
                String tag = node != null ? nonBlankOr(node.faction(), node.roleType()) : "SUPPORTING";
                charLanes.computeIfAbsent(laneId, id -> new LaneAccumulator(id, "CHARACTER", charName, tag))
                        .addChapter(chNo);
            }
        }

        Map<String, LaneAccumulator> locationLanes = new LinkedHashMap<>();
        for (VisualTimelineLaneEvent ev : windowedEvents) {
            if (ev.chapterNo() <= 0 || !hasText(ev.location())) continue;
            String loc = ev.location().trim();
            locationLanes.computeIfAbsent("location:" + loc,
                    id -> new LaneAccumulator(id, "LOCATION", loc, "LOCATION"))
                    .addEvent(ev.chapterNo(), ev.id().toString());
        }
        for (ResolvedSnapshot snap : visibleSnapshots) {
            if (snap.chapterNo() <= 0 || snap.state() == null || !hasText(snap.state().currentLocation())) continue;
            if (allowedChapters != null && !allowedChapters.contains(snap.chapterNo())) continue;
            String loc = snap.state().currentLocation().trim();
            locationLanes.computeIfAbsent("location:" + loc,
                    id -> new LaneAccumulator(id, "LOCATION", loc, "LOCATION"))
                    .addChapter(snap.chapterNo());
        }

        List<VisualEntityLane> result = new ArrayList<>();
        charLanes.values().stream()
                .filter(acc -> !acc.activeChapters.isEmpty())
                .map(acc -> acc.build(cutoffChapterNo))
                .sorted(Comparator.comparingInt((VisualEntityLane l) -> l.activeChapters().size()).reversed()
                        .thenComparing(VisualEntityLane::label))
                .forEach(result::add);
        locationLanes.values().stream()
                .filter(acc -> !acc.activeChapters.isEmpty())
                .map(acc -> acc.build(cutoffChapterNo))
                .sorted(Comparator.comparingInt((VisualEntityLane l) -> l.activeChapters().size()).reversed()
                        .thenComparing(VisualEntityLane::label))
                .forEach(result::add);
        return result;
    }

    private static double computeCoverageRatio(
            List<VisualChapterAxisItem> axisChapters, Set<Integer> chaptersWithEvidence
    ) {
        if (axisChapters.isEmpty()) return 0.0;
        long covered = axisChapters.stream()
                .filter(c -> chaptersWithEvidence.contains(c.chapterNo()))
                .count();
        return Math.round(((double) covered / axisChapters.size()) * 10000.0) / 10000.0;
    }

    private static VisualProjectionResponse withEtag(VisualProjectionResponse raw) {
        String digest = sha256Hex(JSON.writeValueAsString(raw));
        return new VisualProjectionResponse(
                raw.bookId(),
                raw.view(),
                raw.capabilities(),
                raw.cutoff(),
                digest,
                raw.chapters(),
                raw.nodes(),
                raw.edges(),
                raw.factions(),
                raw.events(),
                raw.arcs(),
                raw.entityLanes(),
                raw.totalCharacters(),
                raw.visibleCharacters(),
                raw.undatedRelationCount(),
                raw.evidenceCoverageRatio()
        );
    }

    private static String resolveUpdatedEndpoint(
            String incoming, String existing, Map<String, CharacterEntity> characters
    ) {
        if (incoming != null) {
            String trimmed = incoming.strip();
            if (trimmed.isEmpty()) {
                throw BizException.badRequest("INVALID_RELATION_ENDPOINT", "人物关系端点名称不能为空");
            }
            return canonicalName(trimmed, characters);
        }
        return canonicalName(existing, characters);
    }

    private static String canonicalName(String raw, Map<String, CharacterEntity> characterIndex) {
        if (raw == null) return "";
        String cleaned = raw.strip();
        if (cleaned.isEmpty()) return "";
        CharacterEntity match = characterIndex.get(cleaned);
        return match == null ? cleaned : safe(match.getName());
    }

    private static List<String> canonicalList(String raw, Map<String, CharacterEntity> characterIndex) {
        return LoreNames.split(raw).stream()
                .map(name -> canonicalName(name, characterIndex))
                .filter(VisualProjectionService::hasText)
                .distinct()
                .toList();
    }

    private static String nodeKeyFor(String canonicalName, Map<String, CharacterEntity> characterIndex) {
        CharacterEntity match = characterIndex.get(canonicalName);
        return match != null && match.getId() != null ? match.getId().toString() : UNRESOLVED_PREFIX + canonicalName;
    }

    private static String extractFaction(
            Map<String, String> attrs, CharacterEntity entity, List<String> worldFactions
    ) {
        if (attrs != null) {
            for (String key : FACTION_ATTRIBUTE_KEYS) {
                String value = attrs.get(key);
                if (hasText(value)) return value.trim();
            }
        }
        if (entity != null && !worldFactions.isEmpty()) {
            String probe = safe(entity.getArcScope()) + " " + safe(entity.getStatusNotes()) + " " + safe(entity.getPersonality());
            for (String factionTitle : worldFactions) {
                if (probe.contains(factionTitle)) return factionTitle;
            }
        }
        return "";
    }

    private static String formatStateSnippet(CharacterState state) {
        List<String> parts = new ArrayList<>();
        if (hasText(state.statusNotes())) parts.add(state.statusNotes().trim());
        if (hasText(state.realmOrLevel())) parts.add("境界：" + state.realmOrLevel().trim());
        if (hasText(state.currentLocation())) parts.add("位置：" + state.currentLocation().trim());
        if (hasText(state.inventory())) parts.add("物品：" + state.inventory().trim());
        return parts.isEmpty() ? "" : String.join("；", parts);
    }

    private static CharacterState parseSnapshotState(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return CharacterState.readSnapshot(json);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static List<ParsedHint> parseHints(String hintHistoryJson) {
        if (hintHistoryJson == null || hintHistoryJson.isBlank()) return List.of();
        try {
            JsonNode tree = JSON.readTree(hintHistoryJson);
            if (tree == null || !tree.isArray()) return List.of();
            List<ParsedHint> hints = new ArrayList<>();
            for (JsonNode item : tree) {
                if (item == null || !item.isObject()) continue;
                JsonNode chNode = item.get("chapterNo");
                if (chNode == null || chNode.isNull()) continue;
                int chNo = chNode.asInt(0);
                if (chNo <= 0) continue;
                JsonNode secNode = item.get("sectionNo");
                Integer secNo = (secNode == null || secNode.isNull()) ? null : secNode.asInt(0);
                if (secNo != null && secNo <= 0) secNo = null;
                long order = StructureService.narrativeOrder(chNo, secNo == null ? 1 : secNo);
                String quote = item.hasNonNull("quote") ? item.get("quote").asString() : "";
                String note = item.hasNonNull("note") ? item.get("note").asString() : "";
                hints.add(new ParsedHint(chNo, secNo, order, safe(quote), safe(note)));
            }
            return hints;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static String sanitizeSurface(String text, String hiddenTruth) {
        if (text == null) return "";
        if (hiddenTruth == null || hiddenTruth.isBlank()) return text;
        return text.replace(hiddenTruth.trim(), "【伏笔底牌】");
    }

    private static String normalizeForeshadowingStatus(String status, Integer plantedChapterNo) {
        String value = safe(status).toUpperCase(Locale.ROOT);
        if (value.isEmpty() || "OPEN".equals(value)) {
            return plantedChapterNo == null ? "PLANNED" : "PLANTED";
        }
        return value;
    }

    private static Integer minChapter(Integer a, List<Integer> list, Integer b, Integer c) {
        Integer min = null;
        for (Integer v : List.of(
                a == null ? -1 : a,
                b == null ? -1 : b,
                c == null ? -1 : c
        )) {
            if (v > 0 && (min == null || v < min)) min = v;
        }
        for (Integer v : list) {
            if (v != null && v > 0 && (min == null || v < min)) min = v;
        }
        return min;
    }

    private static Integer maxChapter(Integer a, List<Integer> list, Integer b, Integer c) {
        Integer max = null;
        for (Integer v : List.of(
                a == null ? -1 : a,
                b == null ? -1 : b,
                c == null ? -1 : c
        )) {
            if (v > 0 && (max == null || v > max)) max = v;
        }
        for (Integer v : list) {
            if (v != null && v > 0 && (max == null || v > max)) max = v;
        }
        return max;
    }

    private static boolean isKnownVisible(String knownBy, boolean asOf, Set<String> povNames) {
        if ("PUBLIC".equals(knownBy)) return true;
        if (povNames != null && !povNames.isEmpty()) {
            return LoreNames.split(knownBy).stream().anyMatch(povNames::contains);
        }
        return !asOf;
    }

    private static String normalizeView(String rawView) {
        String normalized = rawView == null || rawView.isBlank() ? VIEW_GRAPH : rawView.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_VIEWS.contains(normalized)) {
            throw BizException.badRequest("INVALID_VISUAL_VIEW", "不支持的可视化视图: " + normalized);
        }
        return normalized;
    }

    private static String normalizeVisibility(String rawVisibility) {
        String normalized = rawVisibility == null || rawVisibility.isBlank()
                ? VISIBILITY_AS_OF : rawVisibility.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_VISIBILITIES.contains(normalized)) {
            throw BizException.badRequest("INVALID_VISIBILITY", "不支持的视角模式: " + normalized);
        }
        return normalized;
    }

    private static String normalizeEntityKind(String rawKind) {
        String normalized = rawKind == null ? "" : rawKind.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_ENTITY_KINDS.contains(normalized)) {
            throw BizException.badRequest("INVALID_ENTITY_KIND", "不支持的实体类型: " + rawKind);
        }
        return normalized;
    }

    private static UUID parseUuidOrNull(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static <T> List<T> filterBook(List<T> rows, UUID bookId, java.util.function.Function<T, UUID> bookIdGetter) {
        if (rows == null || rows.isEmpty()) return List.of();
        return rows.stream()
                .filter(Objects::nonNull)
                .filter(row -> bookId.equals(bookIdGetter.apply(row)))
                .toList();
    }

    private static <K, V> Map<K, V> orEmptyMap(Map<K, V> map) {
        return map == null ? Map.of() : map;
    }

    private static BizException entityNotFound() {
        return BizException.notFound("VISUAL_ENTITY_NOT_FOUND", "实体不存在或在当前视角下不可见");
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nonBlankOr(String preferred, String fallback) {
        return hasText(preferred) ? preferred.trim() : safe(fallback);
    }

    private record ResolvedSnapshot(
            CharacterStateSnapshotEntity entity,
            ChapterSectionEntity section,
            int chapterNo,
            long order,
            CharacterState state
    ) {
    }

    private record ResolvedRelation(
            CharacterRelationEntity entity,
            ChapterSectionEntity section,
            String sourceName,
            String targetName,
            Integer effectiveChapterNo,
            long effectiveOrder,
            String knownBy,
            boolean undated
    ) {
    }

    private record ParsedHint(
            int chapterNo,
            Integer sectionNo,
            long order,
            String quote,
            String note
    ) {
    }

    private record ResolvedForeshadowing(
            ForeshadowingEntity entity,
            VisualForeshadowingArc arc,
            List<ParsedHint> visibleHints
    ) {
    }

    private static final class CharacterEvidenceTracker {
        private final TreeSet<Integer> chapters = new TreeSet<>();
        private final Set<String> units = new LinkedHashSet<>();
        private int relationCount = 0;
        private boolean anyEvidence = false;

        void recordEvidence(Integer chapterNo, UUID sectionId) {
            anyEvidence = true;
            if (chapterNo != null && chapterNo > 0) {
                chapters.add(chapterNo);
            }
            if (sectionId != null) {
                units.add("sec:" + sectionId);
            } else if (chapterNo != null && chapterNo > 0) {
                units.add("ch:" + chapterNo);
            }
        }

        void recordRelation(Integer chapterNo, UUID sectionId) {
            relationCount++;
            recordEvidence(chapterNo, sectionId);
        }

        boolean hasEvidence() {
            return anyEvidence;
        }

        int appearanceCount() {
            return Math.max(chapters.size(), units.size());
        }

        int relationCount() {
            return relationCount;
        }

        Integer firstSeenChapterNo() {
            return chapters.isEmpty() ? null : chapters.first();
        }

        Integer lastSeenChapterNo() {
            return chapters.isEmpty() ? null : chapters.last();
        }
    }

    private static final class LaneAccumulator {
        private final String id;
        private final String kind;
        private final String label;
        private final String roleOrTag;
        private final TreeSet<Integer> activeChapters = new TreeSet<>();
        private final Map<String, List<String>> eventIdsByChapter = new LinkedHashMap<>();

        LaneAccumulator(String id, String kind, String label, String roleOrTag) {
            this.id = id;
            this.kind = kind;
            this.label = label;
            this.roleOrTag = roleOrTag;
        }

        void addChapter(int chapterNo) {
            if (chapterNo > 0) activeChapters.add(chapterNo);
        }

        void addEvent(int chapterNo, String eventId) {
            if (chapterNo <= 0) return;
            activeChapters.add(chapterNo);
            String key = String.valueOf(chapterNo);
            List<String> list = eventIdsByChapter.computeIfAbsent(key, k -> new ArrayList<>());
            if (!list.contains(eventId)) list.add(eventId);
        }

        VisualEntityLane build(int cutoffChapterNo) {
            List<Integer> sorted = List.copyOf(activeChapters);
            int gap = sorted.isEmpty() ? cutoffChapterNo : Math.max(0, cutoffChapterNo - sorted.getLast());
            return new VisualEntityLane(id, kind, label, roleOrTag, sorted, eventIdsByChapter, gap);
        }
    }

    private record ProjectionBundle(
            VisualProjectionResponse response,
            boolean asOf,
            VisualCutoffInfo cutoff,
            List<CharacterEntity> allCharacters,
            Map<UUID, CharacterEntity> charactersById,
            Map<String, CharacterEntity> characterIndex,
            Map<Integer, ChapterEntity> chaptersByNo,
            Map<UUID, ChapterSectionEntity> sectionsById,
            Map<UUID, List<ChapterSectionEntity>> sectionsByChapterId,
            Map<UUID, List<ResolvedSnapshot>> snapshotsByCharId,
            List<SectionSummaryEntity> visibleSummaries,
            List<ResolvedRelation> visibleRelations,
            List<VisualTimelineLaneEvent> allVisibleEvents,
            Map<UUID, ResolvedForeshadowing> visibleForeshadowingById,
            Map<UUID, CharacterState> latestSnapshotByCharId,
            Map<String, VisualGraphNode> nodesById
    ) {
    }
}
