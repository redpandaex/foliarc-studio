package studio.inkflow.biz.novel.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class NovelDtos {

    private NovelDtos() {
    }

    public record CreateBookRequest(
            @NotBlank(message = "书名不能为空")
            @Size(max = 200, message = "书名最多 200 个字符")
            String title,

            @Size(max = 64, message = "题材最多 64 个字符")
            String genre,

            @Size(max = 4000, message = "简介最多 4000 个字符")
            String synopsis
    ) {
    }

    public record UpdateBookRequest(
            @NotBlank(message = "书名不能为空")
            @Size(max = 200)
            String title,

            @Size(max = 64)
            String genre,

            @Size(max = 4000)
            String synopsis,

            @Size(max = 32)
            String status,

            @NotNull(message = "版本号不能为空")
            Long version
    ) {
    }

    public record BookResponse(
            UUID id,
            String title,
            String genre,
            String synopsis,
            String status,
            Long version,
            int chapterCount,
            long totalWordCount,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record CreateChapterRequest(
            @NotBlank(message = "章节标题不能为空")
            @Size(max = 200)
            String title,

            @Min(value = 1, message = "章节序号必须大于等于 1")
            Integer chapterNo,

            String content
    ) {
    }

    public record UpdateChapterRequest(
            @NotBlank(message = "章节标题不能为空")
            @Size(max = 200)
            String title,

            @NotNull(message = "章节正文不能为 null")
            String content,

            @Size(max = 32)
            String status,

            @NotNull(message = "版本号不能为空")
            Long version,

            Boolean createSnapshot,

            @Size(max = 255)
            String changeSummary
    ) {
    }

    public record ChapterSummaryResponse(
            UUID id,
            UUID bookId,
            int chapterNo,
            String title,
            int wordCount,
            String status,
            Long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record ChapterDetailResponse(
            UUID id,
            UUID bookId,
            int chapterNo,
            String title,
            String content,
            int wordCount,
            String status,
            Long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record CreateSnapshotRequest(
            @Size(max = 255, message = "变更摘要最多 255 个字符")
            String changeSummary
    ) {
    }

    public record RollbackChapterRequest(
            @NotNull(message = "当前章节版本号不能为空")
            Long version
    ) {
    }

    public record ChapterVersionResponse(
            UUID id,
            UUID chapterId,
            int versionNo,
            String content,
            String changeSummary,
            UUID createdBy,
            OffsetDateTime createdAt
    ) {
    }
}
