package studio.foliarc.biz.lore.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.lore.dto.LoreDtos;
import studio.foliarc.biz.lore.service.LoreService;

@RestController
@RequestMapping("/api/v1/books/{bookId}")
public class LoreController {

    private final LoreService loreService;

    public LoreController(LoreService loreService) {
        this.loreService = loreService;
    }

    @GetMapping("/bible")
    public LoreDtos.NovelBibleResponse bible(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID bookId
    ) {
        return loreService.getNovelBible(actor.userId(), bookId);
    }

    @GetMapping("/characters")
    public List<LoreDtos.CharacterResponse> listCharacters(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID bookId,
            @RequestParam(required = false) String q
    ) {
        return loreService.listCharacters(actor.userId(), bookId, q);
    }

    @PostMapping("/characters")
    @ResponseStatus(HttpStatus.CREATED)
    public LoreDtos.CharacterResponse upsertCharacter(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID bookId,
            @Valid @RequestBody LoreDtos.UpsertCharacterRequest body
    ) {
        return loreService.upsertCharacter(actor.userId(), bookId, body);
    }

    @DeleteMapping("/characters/{characterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCharacter(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID bookId,
            @PathVariable UUID characterId
    ) {
        loreService.deleteCharacter(actor.userId(), bookId, characterId);
    }

    @GetMapping("/worldbook")
    public List<LoreDtos.WorldEntryResponse> searchWorldbook(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID bookId,
            @RequestParam(required = false) String q
    ) {
        return loreService.searchWorldbook(actor.userId(), bookId, q);
    }

    @PostMapping("/worldbook")
    @ResponseStatus(HttpStatus.CREATED)
    public LoreDtos.WorldEntryResponse createWorldEntry(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID bookId,
            @Valid @RequestBody LoreDtos.UpsertWorldEntryRequest body
    ) {
        return loreService.createWorldEntry(actor.userId(), bookId, body);
    }

    @DeleteMapping("/worldbook/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWorldEntry(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID bookId,
            @PathVariable UUID entryId
    ) {
        loreService.deleteWorldEntry(actor.userId(), bookId, entryId);
    }

    @GetMapping("/foreshadowings")
    public List<LoreDtos.ForeshadowingResponse> listForeshadowings(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID bookId,
            @RequestParam(required = false) String status
    ) {
        return loreService.listForeshadowings(actor.userId(), bookId, status);
    }

    @PostMapping("/foreshadowings")
    @ResponseStatus(HttpStatus.CREATED)
    public LoreDtos.ForeshadowingResponse upsertForeshadowing(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID bookId,
            @Valid @RequestBody LoreDtos.UpsertForeshadowingRequest body
    ) {
        return loreService.upsertForeshadowing(actor.userId(), bookId, body);
    }

    @DeleteMapping("/foreshadowings/{foreshadowingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteForeshadowing(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID bookId,
            @PathVariable UUID foreshadowingId
    ) {
        loreService.deleteForeshadowing(actor.userId(), bookId, foreshadowingId);
    }
}
