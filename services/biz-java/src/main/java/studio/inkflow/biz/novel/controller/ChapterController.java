package studio.inkflow.biz.novel.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import studio.inkflow.biz.auth.AuthenticatedUser;
import studio.inkflow.biz.novel.dto.NovelDtos;
import studio.inkflow.biz.novel.service.ChapterService;

@RestController
@RequestMapping("/api/v1/books/{bookId}/chapters")
public class ChapterController {

    private final ChapterService chapters;

    public ChapterController(ChapterService chapters) {
        this.chapters = chapters;
    }

    @GetMapping
    public List<NovelDtos.ChapterSummaryResponse> listChapters(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID bookId
    ) {
        return chapters.listChapters(actor.userId(), bookId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NovelDtos.ChapterDetailResponse createChapter(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID bookId,
            @Valid @RequestBody NovelDtos.CreateChapterRequest body
    ) {
        return chapters.createChapter(actor.userId(), bookId, body);
    }

    @GetMapping("/{chapterId}")
    public NovelDtos.ChapterDetailResponse getChapter(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID bookId,
            @PathVariable UUID chapterId
    ) {
        return chapters.getChapter(actor.userId(), bookId, chapterId);
    }

    @PutMapping("/{chapterId}")
    public NovelDtos.ChapterDetailResponse updateChapter(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID bookId,
            @PathVariable UUID chapterId,
            @Valid @RequestBody NovelDtos.UpdateChapterRequest body
    ) {
        return chapters.updateChapter(actor.userId(), bookId, chapterId, body);
    }

    @DeleteMapping("/{chapterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteChapter(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID bookId,
            @PathVariable UUID chapterId
    ) {
        chapters.deleteChapter(actor.userId(), bookId, chapterId);
    }

    @GetMapping("/{chapterId}/versions")
    public List<NovelDtos.ChapterVersionResponse> listVersions(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID bookId,
            @PathVariable UUID chapterId
    ) {
        return chapters.listVersions(actor.userId(), bookId, chapterId);
    }

    @PostMapping("/{chapterId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public NovelDtos.ChapterVersionResponse createSnapshot(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID bookId,
            @PathVariable UUID chapterId,
            @Valid @RequestBody(required = false) NovelDtos.CreateSnapshotRequest body
    ) {
        return chapters.createSnapshot(actor.userId(), bookId, chapterId, body);
    }

    @PostMapping("/{chapterId}/versions/{versionId}/rollback")
    public NovelDtos.ChapterDetailResponse rollbackVersion(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID bookId,
            @PathVariable UUID chapterId,
            @PathVariable UUID versionId,
            @Valid @RequestBody NovelDtos.RollbackChapterRequest body
    ) {
        return chapters.rollbackToVersion(actor.userId(), bookId, chapterId, versionId, body);
    }
}
