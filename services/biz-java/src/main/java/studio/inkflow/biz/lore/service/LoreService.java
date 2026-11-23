package studio.inkflow.biz.lore.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import studio.inkflow.biz.common.BizException;
import studio.inkflow.biz.lore.dto.LoreDtos;
import studio.inkflow.biz.lore.entity.CharacterEntity;
import studio.inkflow.biz.lore.entity.ForeshadowingEntity;
import studio.inkflow.biz.lore.entity.PromptTemplateEntity;
import studio.inkflow.biz.lore.entity.WorldEntryEntity;
import studio.inkflow.biz.lore.mapper.CharacterMapper;
import studio.inkflow.biz.lore.mapper.ForeshadowingMapper;
import studio.inkflow.biz.lore.mapper.PromptTemplateMapper;
import studio.inkflow.biz.lore.mapper.WorldEntryMapper;
import studio.inkflow.biz.novel.entity.BookEntity;
import studio.inkflow.biz.novel.entity.ChapterEntity;
import studio.inkflow.biz.novel.mapper.ChapterMapper;
import studio.inkflow.biz.novel.service.BookService;

@Service
public class LoreService {

    private static final Set<String> VALID_ROLES = Set.of("PROTAGONIST", "ANTAGONIST", "SUPPORTING");
    private static final Set<String> VALID_CATEGORIES = Set.of("WORLD", "POWER_SYSTEM", "FACTION", "ITEM", "LOCATION", "RULE");
    private static final Set<String> VALID_FORESHADOW_STATUSES = Set.of("OPEN", "RESOLVED", "ABANDONED");

    private final BookService bookService;
    private final CharacterMapper characterMapper;
    private final WorldEntryMapper worldEntryMapper;
    private final ForeshadowingMapper foreshadowingMapper;
    private final ChapterMapper chapterMapper;
    private final PromptTemplateMapper promptTemplateMapper;

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

    public List<LoreDtos.CharacterResponse> listCharacters(UUID ownerId, UUID bookId, String nameQuery) {
        bookService.requireOwnedBook(ownerId, bookId);
        var query = new LambdaQueryWrapper<CharacterEntity>()
                .eq(CharacterEntity::getBookId, bookId)
                .orderByAsc(CharacterEntity::getName);
        if (nameQuery != null && !nameQuery.isBlank()) {
            query.apply("LOWER(name) LIKE {0}", "%" + nameQuery.trim().toLowerCase(Locale.ROOT) + "%");
        }
        return characterMapper.selectList(query).stream().map(LoreService::toChar).toList();
    }

    @Transactional
    public LoreDtos.CharacterResponse upsertCharacter(UUID ownerId, UUID bookId, LoreDtos.UpsertCharacterRequest req) {
        bookService.requireOwnedBook(ownerId, bookId);
        String cleanName = req.name().trim();
        String roleType = normalizeEnum(req.roleType(), "SUPPORTING", VALID_ROLES, "INVALID_ROLE_TYPE");

        CharacterEntity existing = characterMapper.selectOne(
                new LambdaQueryWrapper<CharacterEntity>()
                        .eq(CharacterEntity::getBookId, bookId)
                        .eq(CharacterEntity::getName, cleanName)
        );
        if (existing == null) {
            CharacterEntity entity = new CharacterEntity();
            entity.setId(UUID.randomUUID());
            entity.setBookId(bookId);
            entity.setName(cleanName);
            entity.setRoleType(roleType);
            entity.setPersonality(safeText(req.personality()));
            entity.setRealmOrLevel(safeText(req.realmOrLevel()));
            entity.setCurrentLocation(safeText(req.currentLocation()));
            entity.setStatusNotes(safeText(req.statusNotes()));
            entity.setInventory(safeText(req.inventory()));
            characterMapper.insert(entity);
            return toChar(entity);
        }

        if (req.roleType() != null && !req.roleType().isBlank()) existing.setRoleType(roleType);
        if (req.personality() != null) existing.setPersonality(req.personality().trim());
        if (req.realmOrLevel() != null) existing.setRealmOrLevel(req.realmOrLevel().trim());
        if (req.currentLocation() != null) existing.setCurrentLocation(req.currentLocation().trim());
        if (req.statusNotes() != null) existing.setStatusNotes(req.statusNotes().trim());
        if (req.inventory() != null) existing.setInventory(req.inventory().trim());
        characterMapper.updateById(existing);
        return toChar(existing);
    }

    @Transactional
    public void deleteCharacter(UUID ownerId, UUID bookId, UUID characterId) {
        bookService.requireOwnedBook(ownerId, bookId);
        CharacterEntity row = characterMapper.selectById(characterId);
        if (row == null || !bookId.equals(row.getBookId())) {
            throw BizException.notFound("CHARACTER_NOT_FOUND", "人物设定不存在");
        }
        characterMapper.deleteById(characterId);
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
        if (statusFilter != null && !statusFilter.isBlank()) {
            query.eq(ForeshadowingEntity::getStatus, statusFilter.trim().toUpperCase(Locale.ROOT));
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
        String title = req.title().trim();
        String status = normalizeEnum(req.status(), "OPEN", VALID_FORESHADOW_STATUSES, "INVALID_FORESHADOWING_STATUS");

        ForeshadowingEntity existing = foreshadowingMapper.selectOne(
                new LambdaQueryWrapper<ForeshadowingEntity>()
                        .eq(ForeshadowingEntity::getBookId, bookId)
                        .eq(ForeshadowingEntity::getTitle, title)
        );
        if (existing == null) {
            ForeshadowingEntity entity = new ForeshadowingEntity();
            entity.setId(UUID.randomUUID());
            entity.setBookId(bookId);
            entity.setTitle(title);
            entity.setDescription(safeText(req.description()));
            entity.setPlantedChapterNo(req.plantedChapterNo());
            entity.setResolvedChapterNo(req.resolvedChapterNo());
            entity.setStatus(status);
            foreshadowingMapper.insert(entity);
            return toForeshadowing(entity);
        }

        if (req.description() != null) existing.setDescription(req.description().trim());
        if (req.plantedChapterNo() != null) existing.setPlantedChapterNo(req.plantedChapterNo());
        if (req.resolvedChapterNo() != null) existing.setResolvedChapterNo(req.resolvedChapterNo());
        if (req.status() != null && !req.status().isBlank()) existing.setStatus(status);
        foreshadowingMapper.updateById(existing);
        return toForeshadowing(existing);
    }

    @Transactional
    public void deleteForeshadowing(UUID ownerId, UUID bookId, UUID foreshadowingId) {
        bookService.requireOwnedBook(ownerId, bookId);
        ForeshadowingEntity row = foreshadowingMapper.selectById(foreshadowingId);
        if (row == null || !bookId.equals(row.getBookId())) {
            throw BizException.notFound("FORESHADOWING_NOT_FOUND", "伏笔记录不存在");
        }
        foreshadowingMapper.deleteById(foreshadowingId);
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

    private static LoreDtos.CharacterResponse toChar(CharacterEntity c) {
        return new LoreDtos.CharacterResponse(
                c.getId(), c.getBookId(), c.getName(), c.getRoleType(),
                c.getPersonality(), c.getRealmOrLevel(), c.getCurrentLocation(),
                c.getStatusNotes(), c.getInventory(), c.getUpdatedAt()
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
                f.getPlantedChapterNo(), f.getResolvedChapterNo(), f.getStatus(), f.getUpdatedAt()
        );
    }
}
