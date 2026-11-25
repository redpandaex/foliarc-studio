package studio.foliarc.biz.lore.service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.lore.CharacterState;
import studio.foliarc.biz.lore.LoreNames;
import studio.foliarc.biz.lore.dto.LoreDtos;
import studio.foliarc.biz.lore.entity.CharacterEntity;
import studio.foliarc.biz.lore.entity.ForeshadowingEntity;
import studio.foliarc.biz.lore.entity.PromptTemplateEntity;
import studio.foliarc.biz.lore.entity.WorldEntryEntity;
import studio.foliarc.biz.lore.mapper.CharacterMapper;
import studio.foliarc.biz.lore.mapper.ForeshadowingMapper;
import studio.foliarc.biz.lore.mapper.PromptTemplateMapper;
import studio.foliarc.biz.lore.mapper.WorldEntryMapper;
import studio.foliarc.biz.memory.service.MemoryProposalService;
import studio.foliarc.biz.memory.service.MemoryProposalService.Type;
import studio.foliarc.biz.novel.entity.BookEntity;
import studio.foliarc.biz.novel.entity.ChapterEntity;
import studio.foliarc.biz.novel.entity.ChapterSectionEntity;
import studio.foliarc.biz.novel.mapper.ChapterMapper;
import studio.foliarc.biz.novel.mapper.ChapterSectionMapper;
import studio.foliarc.biz.novel.service.BookService;
import studio.foliarc.biz.novel.service.StructureService;
import studio.foliarc.biz.vip.service.VipEntitlementService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class LoreService {

    private static final Set<String> VALID_ROLES = Set.of("PROTAGONIST", "ANTAGONIST", "SUPPORTING");
    private static final Set<String> VALID_CATEGORIES = Set.of("WORLD", "POWER_SYSTEM", "FACTION", "ITEM", "LOCATION", "RULE");
    private static final Set<String> VALID_LIFECYCLE = Set.of("CORE_PINNED", "ACTIVE", "DORMANT", "DEAD");
    private static final Set<String> LOCKABLE_FIELDS = Set.of("roleType", "personality", "realmOrLevel",
            "currentLocation", "statusNotes", "inventory", "aliases", "lifecycleStatus");
    private static final Set<String> VALID_FORESHADOW_STATUSES =
            Set.of("PLANNED", "PLANTED", "HINTED", "TRIGGERED", "RESOLVED", "DORMANT_CLUE", "ABANDONED");
    private static final Set<String> ACTIVE_FORESHADOW_STATUSES = Set.of("PLANNED", "PLANTED", "HINTED", "TRIGGERED", "OPEN");
    private static final Set<String> PLANTED_FORESHADOW_STATUSES = Set.of("PLANTED", "HINTED", "TRIGGERED");
    private static final Set<String> IMPORTANCES = Set.of("MAIN_ARC", "SUBPLOT", "DETAIL");
    private static final Set<String> REVEAL_POLICIES = Set.of("HIDE_TRUTH", "HINT_ONLY", "REVEAL_NOW");
    private static final Pattern NUMBERED_CODE = Pattern.compile("#F([0-9]{1,14})");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final BookService bookService;
    private final CharacterMapper characterMapper;
    private final WorldEntryMapper worldEntryMapper;
    private final ForeshadowingMapper foreshadowingMapper;
    private final ChapterMapper chapterMapper;
    private final PromptTemplateMapper promptTemplateMapper;
    private ChapterSectionMapper chapterSectionMapper;
    private VipEntitlementService vipEntitlementService;
    private MemoryProposalService proposalService;

    public LoreService(
            BookService bookService,
            CharacterMapper characterMapper,
            WorldEntryMapper worldEntryMapper,
            ForeshadowingMapper foreshadowingMapper,
            ChapterMapper chapterMapper,
            PromptTemplateMapper promptTemplateMapper
    ) {
        this.bookService = bookService;
        this.characterMapper = characterMapper;
        this.worldEntryMapper = worldEntryMapper;
        this.foreshadowingMapper = foreshadowingMapper;
        this.chapterMapper = chapterMapper;
        this.promptTemplateMapper = promptTemplateMapper;
    }

    @Autowired(required = false)
    public void setChapterSectionMapper(ChapterSectionMapper chapterSectionMapper) {
        this.chapterSectionMapper = chapterSectionMapper;
    }

    @Autowired(required = false)
    public void setVipEntitlementService(VipEntitlementService vipEntitlementService) {
        this.vipEntitlementService = vipEntitlementService;
    }

    @Autowired(required = false)
    public void setMemoryProposalService(MemoryProposalService proposalService) {
        this.proposalService = proposalService;
    }

    public List<LoreDtos.CharacterResponse> listCharacters(UUID ownerId, UUID bookId, String nameQuery) {
        bookService.requireOwnedBook(ownerId, bookId);
        var query = new LambdaQueryWrapper<CharacterEntity>()
                .eq(CharacterEntity::getBookId, bookId)
                .orderByAsc(CharacterEntity::getName);
        if (nameQuery != null && !nameQuery.isBlank()) {
            query.apply("(LOWER(name) LIKE {0} OR LOWER(aliases) LIKE {0})",
                    "%" + nameQuery.trim().toLowerCase(Locale.ROOT) + "%");
        }
        return characterMapper.selectList(query).stream().map(LoreService::toChar).toList();
    }

    @Transactional
    public LoreDtos.CharacterResponse upsertCharacter(UUID ownerId, UUID bookId, LoreDtos.UpsertCharacterRequest req) {
        bookService.requireOwnedBook(ownerId, bookId);
        bookService.lockStructure(bookId);
        CharacterEntity existing = characterIndex(bookId).get(req.name().strip());
        CharacterEntity row = existing == null ? newCharacter(bookId, req.name()) : existing;
        applyCharacterFields(row, req, Set.of(), false);
        row.setLifecycleStatus(normalizeEnum(req.lifecycleStatus(), lifecycle(row), VALID_LIFECYCLE, "INVALID_LIFECYCLE_STATUS"));
        if (req.lastSeenOrder() != null) row.setLastSeenOrder(req.lastSeenOrder());
        if (req.lockedFields() != null) row.setLockedFields(serializeLockedFields(req.lockedFields()));
        if (existing == null) characterMapper.insert(row);
        else characterMapper.updateById(row);
        return toChar(row);
    }

    public Map<String, CharacterEntity> characterIndex(UUID bookId) {
        List<CharacterEntity> rows = characterMapper.selectList(new LambdaQueryWrapper<CharacterEntity>()
                .eq(CharacterEntity::getBookId, bookId).orderByAsc(CharacterEntity::getName));
        Map<String, CharacterEntity> byName = new LinkedHashMap<>();
        rows.forEach(row -> byName.put(row.getName(), row));
        rows.forEach(row -> LoreNames.split(row.getAliases()).forEach(alias -> byName.putIfAbsent(alias, row)));
        return byName;
    }

    @Transactional
    public LoreDtos.CharacterResponse upsertAgentCharacter(UUID ownerId, UUID bookId, LoreDtos.UpsertCharacterRequest req) {
        bookService.requireOwnedBook(ownerId, bookId);
        bookService.lockStructure(bookId);
        CharacterEntity existing = findExtractedCharacter(characterIndex(bookId), req);
        CharacterEntity updated = applyExtractedCharacter(ownerId, bookId, existing, req, null, null);
        return updated == null ? (existing == null ? null : toChar(existing)) : toChar(updated);
    }

    public static CharacterEntity findExtractedCharacter(Map<String, CharacterEntity> index, LoreDtos.UpsertCharacterRequest req) {
        CharacterEntity row = index.get(req.name().strip());
        if (row != null) return row;
        return LoreNames.split(req.aliases()).stream().map(index::get).filter(Objects::nonNull).findFirst().orElse(null);
    }

    // The caller owns the transaction and holds the shared book lock.
    public CharacterEntity applyExtractedCharacter(
            UUID ownerId, UUID bookId, CharacterEntity existing, LoreDtos.UpsertCharacterRequest req,
            ChapterSectionEntity section, CharacterState priorState
    ) {
        long order = section == null ? 0L : section.getNarrativeOrder();
        boolean historical = existing != null && section != null && lastSeen(existing) > order;
        String requestedLifecycle = normalizeEnum(req.lifecycleStatus(), null, VALID_LIFECYCLE, "INVALID_LIFECYCLE_STATUS");
        String currentLifecycle = existing == null ? "ACTIVE" : lifecycle(existing);
        String priorLifecycle = historical ? (priorState == null ? null : priorState.lifecycleStatus()) : currentLifecycle;
        boolean death = "DEAD".equals(requestedLifecycle) && !"DEAD".equals(priorLifecycle);
        boolean identityShift = "CORE_PINNED".equals(currentLifecycle) && Boolean.TRUE.equals(req.isHighRisk());
        if (death || identityShift) {
            proposeCharacterChange(ownerId, bookId, existing, req, section, death);
            return null;
        }

        CharacterEntity row = existing == null || historical ? newCharacter(bookId, req.name()) : existing;
        if (historical) {
            row.setId(existing.getId());
            row.setName(existing.getName());
            if (priorState != null) priorState.restore(row);
        }
        Set<String> locks = existing == null ? Set.of() : Set.copyOf(readLockedFields(existing));
        applyCharacterFields(row, req, locks, true);
        String lifecycle = lifecycle(row);
        if (!locks.contains("lifecycleStatus") && !"CORE_PINNED".equals(lifecycle) && !"DEAD".equals(lifecycle)) {
            row.setLifecycleStatus("DORMANT".equals(lifecycle) ? "ACTIVE"
                    : Objects.requireNonNullElse(requestedLifecycle, lifecycle));
        } else {
            row.setLifecycleStatus(lifecycle);
        }
        row.setLastSeenOrder(Math.max(lastSeen(row), order));
        if (existing == null) characterMapper.insert(row);
        else if (!historical) characterMapper.updateById(row);
        return row;
    }

    private void proposeCharacterChange(UUID ownerId, UUID bookId, CharacterEntity existing,
                                        LoreDtos.UpsertCharacterRequest req, ChapterSectionEntity section, boolean death) {
        if (proposalService == null) return;
        Map<String, String> diff = new LinkedHashMap<>();
        if (req.statusNotes() != null) diff.put("statusNotes", safeText(req.statusNotes()));
        if (death) {
            diff.put("lifecycleStatus", "DEAD");
        } else {
            if (req.roleType() != null && !req.roleType().isBlank()) {
                diff.put("roleType", normalizeEnum(req.roleType(), null, VALID_ROLES, "INVALID_ROLE_TYPE"));
            }
            if (req.inventory() != null) diff.put("inventory", safeText(req.inventory()));
        }
        proposalService.propose(ownerId, bookId, section == null ? null : section.getId(),
                death ? Type.CHARACTER_DEATH : Type.CORE_IDENTITY_SHIFT,
                existing == null ? null : existing.getId(), existing == null ? req.name().strip() : existing.getName(),
                death ? "AI 提议标记角色死亡" : "AI 提议修改核心角色身份", diff);
    }

    private static CharacterEntity newCharacter(UUID bookId, String name) {
        CharacterEntity row = new CharacterEntity();
        row.setId(UUID.randomUUID());
        row.setBookId(bookId);
        row.setName(name.strip());
        row.setPersonality("");
        row.setRealmOrLevel("");
        row.setCurrentLocation("");
        row.setStatusNotes("");
        row.setInventory("");
        row.setAliases("");
        row.setLastSeenOrder(0L);
        row.setArcScope("");
        row.setLockedFields("[]");
        return row;
    }

    private static void applyCharacterFields(CharacterEntity row, LoreDtos.UpsertCharacterRequest req,
                                             Set<String> locks, boolean extracted) {
        if (row.getRoleType() == null) row.setRoleType("SUPPORTING");
        if (req.roleType() != null && !locks.contains("roleType")) {
            row.setRoleType(normalizeEnum(req.roleType(), row.getRoleType(), VALID_ROLES, "INVALID_ROLE_TYPE"));
        }
        if (req.personality() != null && !locks.contains("personality")) row.setPersonality(safeText(req.personality()));
        if (req.realmOrLevel() != null && !locks.contains("realmOrLevel")) row.setRealmOrLevel(safeText(req.realmOrLevel()));
        if (req.currentLocation() != null && !locks.contains("currentLocation")) row.setCurrentLocation(safeText(req.currentLocation()));
        if (req.statusNotes() != null && !locks.contains("statusNotes")) row.setStatusNotes(safeText(req.statusNotes()));
        if (req.inventory() != null && !locks.contains("inventory")) row.setInventory(safeText(req.inventory()));
        if (req.aliases() != null && !locks.contains("aliases")) {
            row.setAliases(LoreNames.mergeAliases(row.getName(), extracted ? row.getAliases() : "", req.aliases()));
        }
        if (req.arcScope() != null) row.setArcScope(safeText(req.arcScope()));
    }

    private static String lifecycle(CharacterEntity row) {
        return row.getLifecycleStatus() != null ? row.getLifecycleStatus()
                : ("PROTAGONIST".equals(row.getRoleType()) ? "CORE_PINNED" : "ACTIVE");
    }

    public static long lastSeen(CharacterEntity row) {
        return row.getLastSeenOrder() == null ? 0L : row.getLastSeenOrder();
    }

    @Transactional
    public LoreDtos.CharacterResponse wakeCharacter(UUID ownerId, UUID bookId, UUID characterId) {
        bookService.requireOwnedBook(ownerId, bookId);
        bookService.lockStructure(bookId);
        CharacterEntity row = requireCharacter(bookId, characterId);
        long latest = chapterSectionMapper == null ? 0 : chapterSectionMapper.findMaxNarrativeOrder(bookId);
        if (latest == 0) {
            int chapterNo = chapterMapper.findMaxChapterNo(bookId);
            latest = chapterNo == 0 ? 0 : StructureService.narrativeOrder(chapterNo, 1);
        }
        row.setLastSeenOrder(Math.max(lastSeen(row), latest));
        row.setLifecycleStatus("ACTIVE");
        characterMapper.updateById(row);
        return toChar(row);
    }

    @Transactional
    public LoreDtos.CharacterResponse updateCharacterLocks(UUID ownerId, UUID bookId, UUID characterId,
                                                           LoreDtos.UpdateCharacterLocksRequest req) {
        bookService.requireOwnedBook(ownerId, bookId);
        bookService.lockStructure(bookId);
        CharacterEntity row = requireCharacter(bookId, characterId);
        row.setLockedFields(serializeLockedFields(req.lockedFields()));
        characterMapper.updateById(row);
        return toChar(row);
    }

    private static List<String> readLockedFields(CharacterEntity row) {
        if (row.getLockedFields() == null || row.getLockedFields().isBlank()) return List.of();
        return JSON.readValue(row.getLockedFields(), new TypeReference<List<String>>() {});
    }

    private static String serializeLockedFields(List<String> fields) {
        if (fields == null) throw BizException.badRequest("INVALID_CHARACTER_LOCK", "需要提供锁定字段列表");
        Set<String> result = new LinkedHashSet<>();
        for (String field : fields) {
            if (field == null || !LOCKABLE_FIELDS.contains(field)) {
                throw BizException.badRequest("INVALID_CHARACTER_LOCK", "字段不可锁定: " + field);
            }
            result.add(field);
        }
        return JSON.writeValueAsString(result);
    }

    @Transactional
    public void deleteCharacter(UUID ownerId, UUID bookId, UUID characterId) {
        bookService.requireOwnedBook(ownerId, bookId);
        bookService.lockStructure(bookId);
        requireCharacter(bookId, characterId);
        characterMapper.deleteById(characterId);
    }

    private CharacterEntity requireCharacter(UUID bookId, UUID characterId) {
        CharacterEntity row = characterMapper.selectById(characterId);
        if (row == null || !bookId.equals(row.getBookId())) {
            throw BizException.notFound("CHARACTER_NOT_FOUND", "人物设定不存在");
        }
        return row;
    }

    public List<LoreDtos.WorldEntryResponse> searchWorldbook(UUID ownerId, UUID bookId, String keyword) {
        bookService.requireOwnedBook(ownerId, bookId);
        var query = new LambdaQueryWrapper<WorldEntryEntity>()
                .eq(WorldEntryEntity::getBookId, bookId)
                .orderByDesc(WorldEntryEntity::getUpdatedAt);
        if (keyword != null && !keyword.isBlank()) {
            String pattern = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
            query.apply(
                    "(LOWER(title) LIKE {0} OR LOWER(content) LIKE {0} OR LOWER(tags) LIKE {0} OR LOWER(category) LIKE {0})",
                    pattern
            );
        }
        return worldEntryMapper.selectList(query).stream().map(LoreService::toWorld).toList();
    }

    @Transactional
    public LoreDtos.WorldEntryResponse createWorldEntry(UUID ownerId, UUID bookId, LoreDtos.UpsertWorldEntryRequest req) {
        bookService.requireOwnedBook(ownerId, bookId);
        String category = normalizeEnum(req.category(), "WORLD", VALID_CATEGORIES, "INVALID_WORLD_CATEGORY");
        WorldEntryEntity entity = new WorldEntryEntity();
        entity.setId(UUID.randomUUID());
        entity.setBookId(bookId);
        entity.setCategory(category);
        entity.setTitle(req.title().trim());
        entity.setContent(safeText(req.content()));
        entity.setTags(safeText(req.tags()));
        worldEntryMapper.insert(entity);
        return toWorld(entity);
    }

    @Transactional
    public void deleteWorldEntry(UUID ownerId, UUID bookId, UUID entryId) {
        bookService.requireOwnedBook(ownerId, bookId);
        WorldEntryEntity row = worldEntryMapper.selectById(entryId);
        if (row == null || !bookId.equals(row.getBookId())) {
            throw BizException.notFound("WORLD_ENTRY_NOT_FOUND", "世界观词条不存在");
        }
        worldEntryMapper.deleteById(entryId);
    }

    public List<LoreDtos.ForeshadowingResponse> listForeshadowings(UUID ownerId, UUID bookId, String statusFilter) {
        bookService.requireOwnedBook(ownerId, bookId);
        var query = new LambdaQueryWrapper<ForeshadowingEntity>()
                .eq(ForeshadowingEntity::getBookId, bookId)
                .orderByAsc(ForeshadowingEntity::getPlantedChapterNo)
                .orderByDesc(ForeshadowingEntity::getUpdatedAt);
        String status = safeText(statusFilter).toUpperCase(Locale.ROOT);
        switch (status) {
            case "" -> { }
            case "OPEN" -> query.in(ForeshadowingEntity::getStatus, ACTIVE_FORESHADOW_STATUSES);
            case "PLANNED", "PLANTED" -> query.and(w -> w.eq(ForeshadowingEntity::getStatus, status)
                    .or(legacy -> legacy.eq(ForeshadowingEntity::getStatus, "OPEN")
                            .isNull("PLANNED".equals(status), ForeshadowingEntity::getPlantedChapterNo)
                            .isNotNull("PLANTED".equals(status), ForeshadowingEntity::getPlantedChapterNo)));
            default -> query.eq(ForeshadowingEntity::getStatus, status);
        }
        return foreshadowingMapper.selectList(query).stream().map(LoreService::toForeshadowing).toList();
    }

    @Transactional
    public LoreDtos.ForeshadowingResponse upsertForeshadowing(
            UUID ownerId,
            UUID bookId,
            LoreDtos.UpsertForeshadowingRequest req
    ) {
        bookService.requireOwnedBook(ownerId, bookId);
        bookService.lockStructure(bookId);
        String code = normalizeCode(req.code());
        ForeshadowingEntity existing = findForeshadowing(bookId, req.id(), code, req.title());
        ForeshadowingEntity row = existing == null
                ? newForeshadowing(ownerId, bookId, req.title(), code, "AUTHOR") : existing;
        if (!code.isEmpty() && !code.equalsIgnoreCase(safeText(row.getCode()))) {
            if (findByCode(bookId, code) != null) {
                throw BizException.conflict("FORESHADOWING_CODE_CONFLICT", "该伏笔编号已被使用");
            }
            row.setCode(code);
        }
        if (req.title() != null && !req.title().isBlank()) row.setTitle(req.title().strip());
        if (req.surfaceClue() != null) {
            row.setSurfaceClue(safeText(req.surfaceClue()));
        } else if (req.description() != null && (safeText(row.getSurfaceClue()).isEmpty()
                || Objects.equals(row.getSurfaceClue(), row.getDescription()))) {
            row.setSurfaceClue(safeText(req.description()));
        }
        if (req.description() != null) row.setDescription(safeText(req.description()));
        if (req.hiddenTruth() != null) row.setHiddenTruth(safeText(req.hiddenTruth()));
        if (req.plantedChapterNo() != null) row.setPlantedChapterNo(req.plantedChapterNo());
        if (req.resolvedChapterNo() != null) row.setResolvedChapterNo(req.resolvedChapterNo());
        if (req.plantedNarrativeOrder() != null) row.setPlantedNarrativeOrder(req.plantedNarrativeOrder());
        if (req.plantedQuote() != null) row.setPlantedQuote(safeText(req.plantedQuote()));
        if (req.plantedSectionId() != null) {
            ChapterSectionEntity section = requireSection(bookId, req.plantedSectionId());
            row.setPlantedSectionId(section.getId());
            row.setPlantedChapterNo(section.getChapterNo());
            row.setPlantedNarrativeOrder(section.getNarrativeOrder());
        }
        if (req.resolvedSectionId() != null) {
            ChapterSectionEntity section = requireSection(bookId, req.resolvedSectionId());
            row.setResolvedSectionId(section.getId());
            row.setResolvedChapterNo(section.getChapterNo());
        }

        if (req.targetChapterMin() != null) row.setTargetChapterMin(req.targetChapterMin());
        if (req.targetChapterMax() != null) row.setTargetChapterMax(req.targetChapterMax());
        if (row.getTargetChapterMin() != null && row.getTargetChapterMax() != null
                && row.getTargetChapterMin() > row.getTargetChapterMax()) {
            throw BizException.badRequest("INVALID_FORESHADOWING_CHAPTER_RANGE", "计划回收起始章不能晚于截止章");
        }
        if (req.involvedCharacters() != null) row.setInvolvedCharacters(LoreNames.normalize(req.involvedCharacters()));
        if (req.authorLocked() != null) row.setAuthorLocked(req.authorLocked());
        row.setImportance(normalizeEnum(req.importance(), row.getImportance(), IMPORTANCES, "INVALID_FORESHADOWING_IMPORTANCE"));
        row.setRevealPolicy(normalizeEnum(req.revealPolicy(), row.getRevealPolicy(), REVEAL_POLICIES, "INVALID_REVEAL_POLICY"));
        String status = req.status() == null || req.status().isBlank() ? row.getStatus() : req.status();
        row.setStatus(normalizeForeshadowingStatus(status, row.getPlantedChapterNo()));
        if (existing == null) foreshadowingMapper.insert(row);
        else foreshadowingMapper.updateById(row);
        return toForeshadowing(row);
    }

    @Transactional
    public LoreDtos.ForeshadowingResponse upsertAgentForeshadowing(UUID ownerId, UUID bookId,
                                                                  LoreDtos.UpsertForeshadowingRequest req) {
        bookService.requireOwnedBook(ownerId, bookId);
        bookService.lockStructure(bookId);
        ForeshadowingEntity row = applyExtractedForeshadowing(ownerId, bookId, req, null);
        if (row == null) throw foreshadowingNotFound();
        return toForeshadowing(row);
    }

    // Progress signals may update an anchor or add a hint. Author-written plans and truth fields stay intact.
    public ForeshadowingEntity applyExtractedForeshadowing(UUID ownerId, UUID bookId,
                                                           LoreDtos.UpsertForeshadowingRequest signal,
                                                           ChapterSectionEntity section) {
        String code = normalizeCode(signal.code());
        ForeshadowingEntity existing = findForeshadowing(bookId, null, code, signal.title());
        if (existing == null && safeText(signal.title()).isEmpty()) return null;
        Integer plantedChapter = signal.plantedChapterNo();
        if (plantedChapter == null && existing != null) plantedChapter = existing.getPlantedChapterNo();
        String status = normalizeForeshadowingStatus(signal.status(), plantedChapter);
        if (existing != null) {
            String currentStatus = normalizeForeshadowingStatus(existing.getStatus(), existing.getPlantedChapterNo());
            if ("RESOLVED".equals(currentStatus) || "ABANDONED".equals(currentStatus)) return existing;
            if ("RESOLVED".equals(status) && Boolean.TRUE.equals(existing.getAuthorLocked())) {
                if (proposalService != null) {
                    Map<String, Object> diff = new LinkedHashMap<>();
                    if (section != null) {
                        diff.put("resolvedSectionId", section.getId());
                        diff.put("resolvedChapterNo", section.getChapterNo());
                    } else if (signal.resolvedChapterNo() != null) {
                        diff.put("resolvedChapterNo", signal.resolvedChapterNo());
                    }
                    proposalService.propose(ownerId, bookId, section == null ? null : section.getId(),
                            Type.FORESHADOWING_RESOLVE, existing.getId(), existing.getTitle(), "AI 提议回收已锁定伏笔", diff);
                }
                return existing;
            }
            if ("PLANNED".equals(status) && !"PLANNED".equals(currentStatus)) return existing;
            if ("PLANTED".equals(status) && !"PLANNED".equals(currentStatus)) {
                boolean sameAnchor = section != null && section.getId().equals(existing.getPlantedSectionId());
                boolean missingAnchor = section != null && existing.getPlantedSectionId() == null
                        && Objects.equals(section.getChapterNo(), existing.getPlantedChapterNo());
                if (sameAnchor || missingAnchor) {
                    applyTransition(existing, new LoreDtos.TransitionForeshadowingRequest(
                            status, null, null, null, signal.plantedQuote(), null), section, true);
                    existing.setStatus(currentStatus);
                    foreshadowingMapper.updateById(existing);
                }
                return existing;
            }
        }
        ForeshadowingEntity row = existing == null
                ? newForeshadowing(ownerId, bookId, signal.title(), code, "AI_EXTRACTED") : existing;
        if (existing == null) {
            row.setDescription(safeText(signal.description()));
            row.setSurfaceClue(signal.surfaceClue() == null ? row.getDescription() : safeText(signal.surfaceClue()));
            row.setInvolvedCharacters(LoreNames.normalize(signal.involvedCharacters()));
        }
        Integer chapterNo = "RESOLVED".equals(status) ? signal.resolvedChapterNo() : signal.plantedChapterNo();
        applyTransition(row, new LoreDtos.TransitionForeshadowingRequest(
                status, null, chapterNo, null, signal.plantedQuote(), signal.description()), section, true);
        if (existing == null) foreshadowingMapper.insert(row);
        else foreshadowingMapper.updateById(row);
        return row;
    }

    private ForeshadowingEntity findForeshadowing(UUID bookId, UUID id, String code, String title) {
        if (id != null) {
            ForeshadowingEntity row = foreshadowingMapper.selectById(id);
            if (row != null) {
                if (!bookId.equals(row.getBookId())) throw foreshadowingNotFound();
                return row;
            }
        }
        if (!code.isEmpty()) {
            ForeshadowingEntity row = findByCode(bookId, code);
            if (row != null) return row;
        }
        if (title == null || title.isBlank()) return null;
        return foreshadowingMapper.selectOne(new LambdaQueryWrapper<ForeshadowingEntity>()
                .eq(ForeshadowingEntity::getBookId, bookId)
                .eq(ForeshadowingEntity::getTitle, title.strip())
                .orderByDesc(ForeshadowingEntity::getUpdatedAt).last("LIMIT 1"));
    }

    private ForeshadowingEntity findByCode(UUID bookId, String code) {
        return foreshadowingMapper.selectOne(new LambdaQueryWrapper<ForeshadowingEntity>()
                .eq(ForeshadowingEntity::getBookId, bookId)
                .apply("UPPER(code) = {0}", code).last("LIMIT 1"));
    }

    private ForeshadowingEntity newForeshadowing(UUID ownerId, UUID bookId, String title, String code, String source) {
        if (title == null || title.isBlank()) {
            throw BizException.badRequest("MISSING_FORESHADOWING_TITLE", "伏笔标题不能为空");
        }
        enforceForeshadowingQuota(ownerId, bookId);
        ForeshadowingEntity row = new ForeshadowingEntity();
        row.setId(UUID.randomUUID());
        row.setBookId(bookId);
        row.setTitle(title.strip());
        row.setCode(code.isEmpty() ? nextCode(bookId) : code);
        row.setDescription("");
        row.setImportance("SUBPLOT");
        row.setRevealPolicy("HIDE_TRUTH");
        row.setSurfaceClue("");
        row.setHiddenTruth("");
        row.setPlantedQuote("");
        row.setInvolvedCharacters("");
        row.setHintHistoryJson("[]");
        row.setSource(source);
        row.setAuthorLocked(false);
        return row;
    }

    private void enforceForeshadowingQuota(UUID ownerId, UUID bookId) {
        if (vipEntitlementService == null) return;
        var plan = vipEntitlementService.resolveForUser(ownerId);
        int limit = plan.entitlements().maxForeshadowings();
        if (limit <= 0) return;
        long count = foreshadowingMapper.selectCount(new LambdaQueryWrapper<ForeshadowingEntity>()
                .eq(ForeshadowingEntity::getBookId, bookId));
        if (count >= limit) {
            throw BizException.vipQuotaExceeded("该小说的伏笔数量已达到当前套餐上限",
                    Map.of("feature", "FORESHADOWINGS", "currentPlan", plan.planCode(),
                            "currentLimit", String.valueOf(limit), "requested", String.valueOf(count + 1)));
        }
    }

    private String nextCode(UUID bookId) {
        long largest = 0;
        for (ForeshadowingEntity row : foreshadowingMapper.selectList(new LambdaQueryWrapper<ForeshadowingEntity>()
                .select(ForeshadowingEntity::getCode).eq(ForeshadowingEntity::getBookId, bookId))) {
            var match = NUMBERED_CODE.matcher(safeText(row.getCode()).toUpperCase(Locale.ROOT));
            if (match.matches()) largest = Math.max(largest, Long.parseLong(match.group(1)));
        }
        return normalizeCode(String.format(Locale.ROOT, "#F%02d", largest + 1));
    }

    private static String normalizeCode(String code) {
        String cleaned = safeText(code).toUpperCase(Locale.ROOT);
        if (cleaned.length() > 16) throw BizException.badRequest("INVALID_FORESHADOWING_CODE", "伏笔编号最多 16 字符");
        return cleaned;
    }

    @Transactional
    public LoreDtos.ForeshadowingResponse transitionForeshadowing(UUID ownerId, UUID bookId, UUID id,
                                                                LoreDtos.TransitionForeshadowingRequest req) {
        bookService.requireOwnedBook(ownerId, bookId);
        bookService.lockStructure(bookId);
        ForeshadowingEntity row = requireForeshadowing(bookId, id);
        if (req.targetStatus() == null || req.targetStatus().isBlank()) {
            throw BizException.badRequest("INVALID_FORESHADOWING_STATUS", "需要提供目标状态");
        }
        ChapterSectionEntity section = resolveTransitionSection(bookId, req);
        Integer plantedChapter = section != null ? section.getChapterNo()
                : req.chapterNo() != null ? req.chapterNo() : row.getPlantedChapterNo();
        String status = normalizeForeshadowingStatus(req.targetStatus(), plantedChapter);
        applyTransition(row, new LoreDtos.TransitionForeshadowingRequest(
                status, req.sectionId(), req.chapterNo(), req.sectionNo(), req.quote(), req.note()), section, false);
        foreshadowingMapper.updateById(row);
        return toForeshadowing(row);
    }

    private static void applyTransition(ForeshadowingEntity row, LoreDtos.TransitionForeshadowingRequest req,
                                        ChapterSectionEntity section, boolean extracted) {
        Integer chapterNo = section == null ? req.chapterNo() : section.getChapterNo();
        Integer sectionNo = section == null ? req.sectionNo() : section.getSectionNo();
        switch (req.targetStatus()) {
            case "PLANTED" -> {
                if (section != null) {
                    row.setPlantedSectionId(section.getId());
                    row.setPlantedNarrativeOrder(section.getNarrativeOrder());
                } else if (chapterNo != null && row.getPlantedSectionId() == null) {
                    row.setPlantedNarrativeOrder(StructureService.narrativeOrder(chapterNo, sectionNo == null ? 1 : sectionNo));
                }
                if (chapterNo != null) row.setPlantedChapterNo(chapterNo);
                if (req.quote() != null) row.setPlantedQuote(safeText(req.quote()));
            }
            case "HINTED" -> appendHint(row, chapterNo, sectionNo, req.quote(), req.note(), extracted);
            case "RESOLVED" -> {
                if (section != null) row.setResolvedSectionId(section.getId());
                if (chapterNo != null) row.setResolvedChapterNo(chapterNo);
            }
            default -> { }
        }
        row.setStatus(req.targetStatus());
    }

    private static void appendHint(ForeshadowingEntity row, Integer chapterNo, Integer sectionNo,
                                   String quote, String note, boolean extracted) {
        ArrayNode history = row.getHintHistoryJson() == null || row.getHintHistoryJson().isBlank()
                ? JSON.createArrayNode() : (ArrayNode) JSON.readTree(row.getHintHistoryJson());
        ObjectNode hint = JSON.createObjectNode().put("chapterNo", chapterNo).put("sectionNo", sectionNo)
                .put("quote", safeText(quote)).put("note", safeText(note));
        if (extracted) {
            for (var previous : history) {
                if (previous.equals(hint)) return;
            }
        }
        history.add(hint);
        row.setHintHistoryJson(JSON.writeValueAsString(history));
    }

    private ChapterSectionEntity requireSection(UUID bookId, UUID sectionId) {
        ChapterSectionEntity section = chapterSectionMapper == null ? null : chapterSectionMapper.selectById(sectionId);
        if (section == null || !bookId.equals(section.getBookId())) {
            throw BizException.notFound("SECTION_NOT_FOUND", "小节不存在");
        }
        return section;
    }

    private ChapterSectionEntity resolveTransitionSection(UUID bookId, LoreDtos.TransitionForeshadowingRequest req) {
        if (req.sectionId() != null) return requireSection(bookId, req.sectionId());
        if (chapterSectionMapper == null || req.chapterNo() == null) return null;
        return chapterSectionMapper.selectOne(new LambdaQueryWrapper<ChapterSectionEntity>()
                .eq(ChapterSectionEntity::getBookId, bookId)
                .eq(ChapterSectionEntity::getChapterNo, req.chapterNo())
                .eq(ChapterSectionEntity::getSectionNo, req.sectionNo() == null ? 1 : req.sectionNo())
                .last("LIMIT 1"));
    }

    public LoreDtos.DueForeshadowingsResponse dueForeshadowings(UUID ownerId, UUID bookId, int chapterNo) {
        if (chapterNo < 1) throw BizException.badRequest("INVALID_CHAPTER_NO", "章节编号必须大于零");
        List<LoreDtos.ForeshadowingResponse> active = listForeshadowings(ownerId, bookId, "OPEN");
        return new LoreDtos.DueForeshadowingsResponse(chapterNo,
                active.stream().filter(clue -> isDue(clue, chapterNo)).sorted(duePriority()).toList(),
                active.stream().filter(clue -> "PLANNED".equals(clue.status())).toList(),
                active.stream().filter(clue -> PLANTED_FORESHADOW_STATUSES.contains(clue.status())).toList());
    }

    public static boolean isActiveForeshadowing(String status) {
        return status != null && ACTIVE_FORESHADOW_STATUSES.contains(status);
    }

    public static boolean isDue(LoreDtos.ForeshadowingResponse clue, int chapterNo) {
        return (clue.targetChapterMin() != null && clue.targetChapterMin() <= chapterNo)
                || (clue.targetChapterMax() != null && clue.targetChapterMax() < chapterNo);
    }

    public static Comparator<LoreDtos.ForeshadowingResponse> duePriority() {
        return Comparator.comparing(LoreDtos.ForeshadowingResponse::targetChapterMax, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(LoreDtos.ForeshadowingResponse::targetChapterMin, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    public void shelveDormantEntities(UUID bookId, long maxOrder) {
        characterMapper.update(null, new LambdaUpdateWrapper<CharacterEntity>()
                .eq(CharacterEntity::getBookId, bookId)
                .eq(CharacterEntity::getLifecycleStatus, "ACTIVE")
                .gt(CharacterEntity::getLastSeenOrder, 0)
                .lt(CharacterEntity::getLastSeenOrder, maxOrder - 200_000)
                .apply("NOT (locked_fields @> '[\"lifecycleStatus\"]'::jsonb)")
                .set(CharacterEntity::getLifecycleStatus, "DORMANT"));
        foreshadowingMapper.update(null, new LambdaUpdateWrapper<ForeshadowingEntity>()
                .eq(ForeshadowingEntity::getBookId, bookId)
                .eq(ForeshadowingEntity::getStatus, "PLANTED")
                .ne(ForeshadowingEntity::getImportance, "MAIN_ARC")
                .lt(ForeshadowingEntity::getPlantedNarrativeOrder, maxOrder - 300_000)
                .set(ForeshadowingEntity::getStatus, "DORMANT_CLUE"));
    }

    @Transactional
    public void deleteForeshadowing(UUID ownerId, UUID bookId, UUID foreshadowingId) {
        bookService.requireOwnedBook(ownerId, bookId);
        bookService.lockStructure(bookId);
        requireForeshadowing(bookId, foreshadowingId);
        foreshadowingMapper.deleteById(foreshadowingId);
    }

    private ForeshadowingEntity requireForeshadowing(UUID bookId, UUID foreshadowingId) {
        ForeshadowingEntity row = foreshadowingMapper.selectById(foreshadowingId);
        if (row == null || !bookId.equals(row.getBookId())) throw foreshadowingNotFound();
        return row;
    }

    private static BizException foreshadowingNotFound() {
        return BizException.notFound("FORESHADOWING_NOT_FOUND", "伏笔记录不存在");
    }

    public LoreDtos.ChapterExcerptResponse getChapterExcerpt(UUID ownerId, UUID bookId, int chapterNo, int maxChars) {
        bookService.requireOwnedBook(ownerId, bookId);
        ChapterEntity chapter = chapterMapper.selectOne(
                new LambdaQueryWrapper<ChapterEntity>()
                        .eq(ChapterEntity::getBookId, bookId)
                        .eq(ChapterEntity::getChapterNo, chapterNo)
        );
        if (chapter == null) {
            throw BizException.notFound("CHAPTER_NOT_FOUND", "未找到第 " + chapterNo + " 章");
        }
        String body = chapter.getContent() == null ? "" : chapter.getContent();
        int cap = Math.max(200, Math.min(maxChars, 4000));
        String excerpt = body.length() <= cap ? body : body.substring(body.length() - cap);
        return new LoreDtos.ChapterExcerptResponse(
                chapter.getId(),
                chapter.getChapterNo(),
                chapter.getTitle(),
                chapter.getWordCount() == null ? 0 : chapter.getWordCount(),
                excerpt
        );
    }

    public LoreDtos.NovelBibleResponse getNovelBible(UUID ownerId, UUID bookId) {
        BookEntity book = bookService.requireOwnedBook(ownerId, bookId);
        return new LoreDtos.NovelBibleResponse(
                book.getId(),
                book.getTitle(),
                book.getGenre(),
                book.getSynopsis(),
                listCharacters(ownerId, bookId, null),
                searchWorldbook(ownerId, bookId, null),
                listForeshadowings(ownerId, bookId, "OPEN")
        );
    }

    public LoreDtos.PromptTemplateResponse getActivePrompt(String name) {
        PromptTemplateEntity entity = promptTemplateMapper.selectOne(
                new LambdaQueryWrapper<PromptTemplateEntity>()
                        .eq(PromptTemplateEntity::getName, name)
                        .eq(PromptTemplateEntity::getStage, "ACTIVE")
                        .orderByDesc(PromptTemplateEntity::getVersion)
                        .last("LIMIT 1")
        );
        if (entity == null) {
            throw BizException.notFound("PROMPT_NOT_FOUND", "未找到启用的 Prompt 模板: " + name);
        }
        return new LoreDtos.PromptTemplateResponse(
                entity.getId(),
                entity.getName(),
                entity.getVersion(),
                entity.getStage(),
                entity.getDescription(),
                entity.getTemplateBody()
        );
    }

    private static String normalizeEnum(String raw, String fallback, Set<String> allowed, String errCode) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw BizException.badRequest(errCode, "无效枚举值: " + normalized);
        }
        return normalized;
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeForeshadowingStatus(String status, Integer plantedChapterNo) {
        String defaultStatus = plantedChapterNo == null ? "PLANNED" : "PLANTED";
        String value = safeText(status);
        return "OPEN".equalsIgnoreCase(value) ? defaultStatus
                : normalizeEnum(value, defaultStatus, VALID_FORESHADOW_STATUSES, "INVALID_FORESHADOWING_STATUS");
    }

    private static LoreDtos.CharacterResponse toChar(CharacterEntity c) {
        return new LoreDtos.CharacterResponse(
                c.getId(), c.getBookId(), c.getName(), c.getRoleType(),
                c.getPersonality(), c.getRealmOrLevel(), c.getCurrentLocation(),
                c.getStatusNotes(), c.getInventory(), c.getUpdatedAt(), safeText(c.getAliases()),
                lifecycle(c), lastSeen(c), safeText(c.getArcScope()), readLockedFields(c)
        );
    }

    private static LoreDtos.WorldEntryResponse toWorld(WorldEntryEntity w) {
        return new LoreDtos.WorldEntryResponse(
                w.getId(), w.getBookId(), w.getCategory(),
                w.getTitle(), w.getContent(), w.getTags(), w.getUpdatedAt()
        );
    }

    private static LoreDtos.ForeshadowingResponse toForeshadowing(ForeshadowingEntity f) {
        return new LoreDtos.ForeshadowingResponse(
                f.getId(), f.getBookId(), f.getTitle(), f.getDescription(),
                f.getPlantedChapterNo(), f.getResolvedChapterNo(), normalizeForeshadowingStatus(f.getStatus(), f.getPlantedChapterNo()),
                f.getUpdatedAt(), safeText(f.getCode()), Objects.requireNonNullElse(f.getImportance(), "SUBPLOT"),
                Objects.requireNonNullElse(f.getRevealPolicy(), "HIDE_TRUTH"), Objects.requireNonNullElse(f.getSurfaceClue(), safeText(f.getDescription())),
                safeText(f.getHiddenTruth()), f.getPlantedSectionId(), f.getPlantedNarrativeOrder(), safeText(f.getPlantedQuote()),
                f.getResolvedSectionId(), f.getTargetChapterMin(), f.getTargetChapterMax(), safeText(f.getInvolvedCharacters()),
                Objects.requireNonNullElse(f.getHintHistoryJson(), "[]"), Objects.requireNonNullElse(f.getSource(), "AUTHOR"),
                Boolean.TRUE.equals(f.getAuthorLocked())
        );
    }
}
