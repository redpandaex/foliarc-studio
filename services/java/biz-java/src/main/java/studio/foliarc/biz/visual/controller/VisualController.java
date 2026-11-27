package studio.foliarc.biz.visual.controller;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.rag.dto.RagDtos;
import studio.foliarc.biz.visual.dto.VisualDtos;
import studio.foliarc.biz.visual.service.VisualProjectionService;

@RestController
@RequestMapping("/api/v1/books/{bookId}/visual")
public class VisualController {

    private final VisualProjectionService visualProjectionService;

    public VisualController(VisualProjectionService visualProjectionService) {
        this.visualProjectionService = visualProjectionService;
    }

    @GetMapping("/projection")
    public ResponseEntity<VisualDtos.VisualProjectionResponse> getProjection(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID bookId,
            @RequestParam(defaultValue = "graph") String view,
            @RequestParam(defaultValue = "AS_OF") String visibility,
            @RequestParam(required = false) UUID asOfChapterId,
            @RequestParam(required = false) UUID asOfSectionId,
            @RequestParam(required = false) UUID volumeId,
            @RequestParam(required = false) Integer fromChapter,
            @RequestParam(required = false) Integer toChapter,
            @RequestParam(required = false) UUID povId,
            @RequestParam(required = false) String focusId,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch
    ) {
        VisualDtos.VisualProjectionResponse response = visualProjectionService.getProjection(
                principal.userId(),
                bookId,
                view,
                visibility,
                asOfChapterId,
                asOfSectionId,
                volumeId,
                fromChapter,
                toChapter,
                povId,
                focusId
        );
        String quotedEtag = "\"" + response.etag() + "\"";
        if (matchesEtag(ifNoneMatch, response.etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(quotedEtag)
                    .cacheControl(CacheControl.noCache().cachePrivate())
                    .build();
        }
        return ResponseEntity.ok()
                .eTag(quotedEtag)
                .cacheControl(CacheControl.noCache().cachePrivate())
                .body(response);
    }

    @GetMapping("/entities/{kind}/{entityId}")
    public VisualDtos.VisualEntityDetailResponse getEntityDetail(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID bookId,
            @PathVariable String kind,
            @PathVariable String entityId,
            @RequestParam(defaultValue = "AS_OF") String visibility,
            @RequestParam(required = false) UUID asOfChapterId,
            @RequestParam(required = false) UUID asOfSectionId,
            @RequestParam(required = false) UUID povId
    ) {
        return visualProjectionService.getEntityDetail(
                principal.userId(),
                bookId,
                kind,
                entityId,
                visibility,
                asOfChapterId,
                asOfSectionId,
                povId
        );
    }

    @GetMapping("/director-options")
    public VisualDtos.VisualDirectorOptionsResponse getDirectorOptions(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID bookId,
            @RequestParam(defaultValue = "AS_OF") String visibility,
            @RequestParam(required = false) UUID asOfChapterId,
            @RequestParam(required = false) UUID asOfSectionId
    ) {
        return visualProjectionService.getDirectorOptions(
                principal.userId(),
                bookId,
                visibility,
                asOfChapterId,
                asOfSectionId
        );
    }

    @PatchMapping("/relations/{relationId}")
    public RagDtos.CharacterRelationResponse updateRelation(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID bookId,
            @PathVariable UUID relationId,
            @Valid @RequestBody VisualDtos.UpdateVisualRelationRequest body
    ) {
        return visualProjectionService.updateRelation(principal.userId(), bookId, relationId, body);
    }

    @PostMapping("/relations/{relationId}/confirm")
    public RagDtos.CharacterRelationResponse confirmAiRelation(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID bookId,
            @PathVariable UUID relationId
    ) {
        return visualProjectionService.confirmAiRelation(principal.userId(), bookId, relationId);
    }

    @PatchMapping("/events/{eventId}")
    public RagDtos.TimelineEventResponse updateEvent(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID bookId,
            @PathVariable UUID eventId,
            @Valid @RequestBody VisualDtos.UpdateVisualEventRequest body
    ) {
        return visualProjectionService.updateEvent(principal.userId(), bookId, eventId, body);
    }

    private static boolean matchesEtag(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank() || etag == null || etag.isBlank()) {
            return false;
        }
        for (String raw : ifNoneMatch.split(",")) {
            String token = raw.trim();
            if ("*".equals(token)) {
                return true;
            }
            if (token.startsWith("W/")) {
                token = token.substring(2).trim();
            }
            if (token.startsWith("\"") && token.endsWith("\"") && token.length() >= 2) {
                token = token.substring(1, token.length() - 1);
            }
            if (etag.equals(token)) {
                return true;
            }
        }
        return false;
    }
}
