package studio.foliarc.biz.novel.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.novel.dto.StructureDtos;
import studio.foliarc.biz.novel.service.StructureService;

@RestController
@RequestMapping("/api/v1/books/{bookId}")
public class StructureController {

    private final StructureService structureService;

    public StructureController(StructureService structureService) {
        this.structureService = structureService;
    }

    @GetMapping("/tree")
    public StructureDtos.BookTreeResponse getTree(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID bookId
    ) {
        return structureService.getTree(actor.userId(), bookId);
    }

    @PostMapping("/volumes")
    @ResponseStatus(HttpStatus.CREATED)
    public StructureDtos.VolumeResponse createVolume(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID bookId,
            @Valid @RequestBody StructureDtos.CreateVolumeRequest body
    ) {
        return structureService.createVolume(actor.userId(), bookId, body);
    }

    @PatchMapping("/volumes/{volumeId}")
    public StructureDtos.VolumeResponse updateVolume(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID bookId,
            @PathVariable UUID volumeId, @Valid @RequestBody StructureDtos.UpdateVolumeRequest body
    ) {
        return structureService.updateVolume(actor.userId(), bookId, volumeId, body);
    }

    @DeleteMapping("/volumes/{volumeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteVolume(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID bookId, @PathVariable UUID volumeId
    ) {
        structureService.deleteVolume(actor.userId(), bookId, volumeId);
    }

    @PatchMapping("/chapters/{chapterId}/volume")
    public StructureDtos.ChapterTreeNode moveChapter(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID bookId, @PathVariable UUID chapterId,
            @Valid @RequestBody StructureDtos.MoveChapterRequest body
    ) {
        return structureService.moveChapter(actor.userId(), bookId, chapterId, body);
    }

    @PostMapping("/chapters/{chapterId}/sections")
    @ResponseStatus(HttpStatus.CREATED)
    public StructureDtos.SectionDetailResponse createSection(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID bookId, @PathVariable UUID chapterId,
            @Valid @RequestBody StructureDtos.CreateSectionRequest body
    ) {
        return structureService.createSection(actor.userId(), bookId, chapterId, body);
    }

    @GetMapping("/chapters/{chapterId}/sections/{sectionId}")
    public StructureDtos.SectionDetailResponse getSection(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID bookId,
            @PathVariable UUID chapterId, @PathVariable UUID sectionId
    ) {
        return structureService.getSection(actor.userId(), bookId, chapterId, sectionId);
    }

    @PutMapping("/chapters/{chapterId}/sections/{sectionId}")
    public StructureDtos.SectionDetailResponse updateSection(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID bookId,
            @PathVariable UUID chapterId, @PathVariable UUID sectionId,
            @Valid @RequestBody StructureDtos.UpdateSectionRequest body
    ) {
        return structureService.updateSection(actor.userId(), bookId, chapterId, sectionId, body);
    }

    @DeleteMapping("/chapters/{chapterId}/sections/{sectionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSection(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID bookId,
            @PathVariable UUID chapterId, @PathVariable UUID sectionId
    ) {
        structureService.deleteSection(actor.userId(), bookId, chapterId, sectionId);
    }

    @GetMapping("/chapters/{chapterId}/full-preview")
    public StructureDtos.FullChapterPreviewResponse fullPreview(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID bookId, @PathVariable UUID chapterId
    ) {
        return structureService.fullPreview(actor.userId(), bookId, chapterId);
    }
}
