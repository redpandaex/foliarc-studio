package studio.foliarc.biz.style.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.style.dto.StyleAnchorDtos.StyleAnchorResponse;
import studio.foliarc.biz.style.dto.StyleAnchorDtos.UpsertStyleAnchorRequest;
import studio.foliarc.biz.style.service.StyleAnchorService;

@RestController
@RequestMapping("/api/v1/books/{bookId}/style-anchors")
public class StyleAnchorController {

    private final StyleAnchorService anchors;

    public StyleAnchorController(StyleAnchorService anchors) {
        this.anchors = anchors;
    }

    @GetMapping
    public List<StyleAnchorResponse> listAnchors(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID bookId
    ) {
        return anchors.listAnchors(actor.userId(), bookId);
    }

    @PostMapping
    public StyleAnchorResponse upsertAnchor(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID bookId,
            @Valid @RequestBody UpsertStyleAnchorRequest body
    ) {
        return anchors.upsertAnchor(actor.userId(), bookId, body);
    }

    @DeleteMapping("/{anchorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAnchor(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID bookId, @PathVariable UUID anchorId
    ) {
        anchors.deleteAnchor(actor.userId(), bookId, anchorId);
    }
}
