package studio.foliarc.biz.novel.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class StructureDtos {

    private StructureDtos() {}

    public record BookTreeResponse(
            UUID bookId, String precisionMode, String effectivePrecisionMode, long totalUnits,
            List<VolumeTreeNode> volumes
    ) {}

    public record VolumeTreeNode(
            UUID id, int volumeNo, String title, String arcGoal, String pacingNotes,
            String realmScope, String status, List<ChapterTreeNode> chapters
    ) {}

    public record ChapterTreeNode(
            UUID id, int chapterNo, UUID volumeId, String title, String structureMode,
            int wordCount, String status, Long version, List<SectionTreeNode> sections
    ) {}

    public record SectionTreeNode(
            UUID id, UUID chapterId, int chapterNo, int sectionNo, long narrativeOrder,
            String title, int wordCount, boolean isDefault, boolean summaryDirty, Long version
    ) {}

    public record CreateVolumeRequest(
            @Size(max = 200) String title,
            String arcGoal,
            String pacingNotes,
            @Size(max = 120) String realmScope
    ) {}

    public record UpdateVolumeRequest(
            @Size(max = 200) String title,
            String arcGoal,
            String pacingNotes,
            @Size(max = 120) String realmScope,
            @Size(max = 24) String status
    ) {}

    public record VolumeResponse(
            UUID id, UUID bookId, int volumeNo, String title, String arcGoal, String pacingNotes,
            String realmScope, String status
    ) {}

    public record MoveChapterRequest(UUID volumeId) {}

    public record CreateSectionRequest(
            @Size(max = 200) String title,
            String content
    ) {}

    public record UpdateSectionRequest(
            @Size(max = 200) String title,
            @NotNull String content,
            @NotNull Long version,
            Boolean createSnapshot,
            @Size(max = 255) String changeSummary
    ) {}

    public record SectionDetailResponse(
            UUID id, UUID bookId, UUID chapterId, int chapterNo, int sectionNo, long narrativeOrder,
            String title, String content, int wordCount, String contentHash, boolean isDefault,
            boolean summaryDirty, Long version, OffsetDateTime createdAt, OffsetDateTime updatedAt
    ) {}

    public record FullChapterPreviewResponse(
            UUID chapterId, int chapterNo, String title, String structureMode,
            int totalWordCount, int sectionCount, String combinedContent
    ) {}
}
