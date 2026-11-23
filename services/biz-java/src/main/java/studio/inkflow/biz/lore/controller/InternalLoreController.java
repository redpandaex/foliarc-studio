package studio.inkflow.biz.lore.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import studio.inkflow.biz.common.BizException;
import studio.inkflow.biz.lore.dto.LoreDtos;
import studio.inkflow.biz.lore.service.LoreService;

/**
 * Tenant-isolated internal Lore & Prompt Registry endpoints consumed by Python Tools
 * and the {@code novel-lore-mcp} MCP Server. Enforces both {@code X-Internal-Token}
 * and {@code X-Owner-Id} ownership checks on every request.
 */
@RestController
@RequestMapping("/internal/v1")
public class InternalLoreController {

    private final LoreService loreService;
    private final byte[] expectedToken;

    public InternalLoreController(
            LoreService loreService,
            @Value("${inkflow.agent.internal-token}") String internalToken
    ) {
        this.loreService = loreService;
        this.expectedToken = internalToken.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/books/{bookId}/lore/bible")
    public LoreDtos.NovelBibleResponse bible(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Owner-Id", required = false) String ownerId,
            @PathVariable UUID bookId
    ) {
        verifyToken(token);
        return loreService.getNovelBible(parseOwner(ownerId), bookId);
    }

    @GetMapping("/books/{bookId}/lore/characters")
    public List<LoreDtos.CharacterResponse> characters(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Owner-Id", required = false) String ownerId,
            @PathVariable UUID bookId,
            @RequestParam(required = false) String name
    ) {
        verifyToken(token);
        return loreService.listCharacters(parseOwner(ownerId), bookId, name);
    }

    @PostMapping("/books/{bookId}/lore/characters")
    public LoreDtos.CharacterResponse upsertCharacter(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Owner-Id", required = false) String ownerId,
            @PathVariable UUID bookId,
            @Valid @RequestBody LoreDtos.UpsertCharacterRequest body
    ) {
        verifyToken(token);
        return loreService.upsertCharacter(parseOwner(ownerId), bookId, body);
    }

    @GetMapping("/books/{bookId}/lore/worldbook")
    public List<LoreDtos.WorldEntryResponse> searchWorldbook(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Owner-Id", required = false) String ownerId,
            @PathVariable UUID bookId,
            @RequestParam(required = false) String q
    ) {
        verifyToken(token);
        return loreService.searchWorldbook(parseOwner(ownerId), bookId, q);
    }

    @GetMapping("/books/{bookId}/lore/foreshadowings")
    public List<LoreDtos.ForeshadowingResponse> foreshadowings(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Owner-Id", required = false) String ownerId,
            @PathVariable UUID bookId,
            @RequestParam(required = false) String status
    ) {
        verifyToken(token);
        return loreService.listForeshadowings(parseOwner(ownerId), bookId, status);
    }

    @PostMapping("/books/{bookId}/lore/foreshadowings")
    public LoreDtos.ForeshadowingResponse upsertForeshadowing(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Owner-Id", required = false) String ownerId,
            @PathVariable UUID bookId,
            @Valid @RequestBody LoreDtos.UpsertForeshadowingRequest body
    ) {
        verifyToken(token);
        return loreService.upsertForeshadowing(parseOwner(ownerId), bookId, body);
    }

    @GetMapping("/books/{bookId}/lore/chapters/{chapterNo}")
    public LoreDtos.ChapterExcerptResponse chapterExcerpt(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Owner-Id", required = false) String ownerId,
            @PathVariable UUID bookId,
            @PathVariable int chapterNo,
            @RequestParam(defaultValue = "1600") int maxChars
    ) {
        verifyToken(token);
        return loreService.getChapterExcerpt(parseOwner(ownerId), bookId, chapterNo, maxChars);
    }

    @GetMapping("/prompts/{name}")
    public LoreDtos.PromptTemplateResponse prompt(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable String name
    ) {
        verifyToken(token);
        return loreService.getActivePrompt(name);
    }

    private void verifyToken(String token) {
        if (token == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), expectedToken)) {
            throw BizException.forbidden("INVALID_INTERNAL_TOKEN", "非法内部服务凭证");
        }
    }

    private static UUID parseOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw BizException.badRequest("MISSING_OWNER_ID", "内部设定查询必须透传 X-Owner-Id 租户权限头");
        }
        try {
            return UUID.fromString(ownerId.trim());
        } catch (IllegalArgumentException ex) {
            throw BizException.badRequest("INVALID_OWNER_ID", "无效的 X-Owner-Id");
        }
    }
}
