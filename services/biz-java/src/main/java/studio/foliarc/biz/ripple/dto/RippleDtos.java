package studio.foliarc.biz.ripple.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

public final class RippleDtos {

    private RippleDtos() {}

    public record FactDeltaItem(String type, String entity, String oldFact, String newFact, List<String> searchKeywords) {}

    public record PatchHunkItem(String originalParagraph, String patchedParagraph, String reason) {}

    public record StartRippleAnalyzeRequest(
            String rangeMode, Integer rangeStartChapter, Integer rangeEndChapter, List<UUID> volumeIds,
            UUID oldVersionId, String oldContent, String newContent, String changeNote
    ) {}

    public record GeneratePatchesRequest(List<UUID> impactIds) {}

    public record ApplyPatchesRequest(List<UUID> patchIds, List<UUID> rejectedPatchIds) {}

    public record RippleJobResponse(
            UUID id, UUID bookId, UUID sourceSectionId, int sourceChapterNo, long sourceNarrativeOrder,
            String vipPlanCode, String rangeMode, int rangeStartChapter, int rangeEndChapter, int maxCandidatesCap,
            int maxPatchBatchLimit, String status, String changeSummary, int scannedSectionCount, int candidateSectionCount,
            int backwardConflictCount, int forwardConflictCount, int minorMentionCount,
            long estimatedCostMicros, double estimatedCostCredits, long actualCostMicros, double actualCostCredits,
            String errorMessage, OffsetDateTime createdAt, OffsetDateTime updatedAt
    ) {}

    public record RippleImpactResponse(
            UUID id, UUID jobId, UUID targetSectionId, int chapterNo, int sectionNo, long narrativeOrder,
            String chapterTitle, String direction, String severity, String conflictReason, String matchedExcerpt,
            String patchStatus
    ) {}

    public record RipplePatchResponse(
            UUID id, UUID jobId, UUID impactId, UUID targetSectionId, int chapterNo, int sectionNo,
            String chapterTitle, String direction, int batchNo, long baseVersion, List<PatchHunkItem> hunks,
            String status, OffsetDateTime updatedAt
    ) {}

    public record RippleJobDetailResponse(
            RippleJobResponse job, List<FactDeltaItem> factDeltas, List<RippleImpactResponse> backwardImpacts,
            List<RippleImpactResponse> forwardImpacts, List<RipplePatchResponse> patches
    ) {}

    public record ApplyRipplePatchesResponse(
            String jobStatus, int appliedCount, int conflictCount, int manualReviewCount, int rejectedCount,
            List<UUID> appliedSectionIds
    ) {}

    public record InternalRippleJobContext(
            UUID jobId, UUID bookId, UUID ownerId, String bookTitle, String genre, String genreCode, String subGenreTags,
            UUID sourceSectionId, int sourceChapterNo, int sourceSectionNo, long sourceNarrativeOrder, String rangeMode,
            int rangeStartChapter, int rangeEndChapter, long rangeStartOrder, long rangeEndOrder, int maxCandidatesCap,
            int maxPatchBatchLimit, String oldContent, String newContent, String changeNote, String styleAnchorsText
    ) {}

    public record InternalCandidateSearchRequest(List<String> entities, List<String> keywords, Integer limit) {}

    public record InternalCandidateSection(
            UUID sectionId, UUID chapterId, int chapterNo, int sectionNo, long narrativeOrder, String chapterTitle,
            String sectionTitle, long version, String summaryText, String content, String matchedVia
    ) {}

    public record InternalCandidateSearchResponse(int scannedSectionCount, List<InternalCandidateSection> candidates) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InternalImpactInput(
            UUID targetSectionId, Integer chapterNo, Integer sectionNo, Long narrativeOrder, String chapterTitle,
            String direction, String severity, String conflictReason, String matchedExcerpt
    ) {}

    public record InternalAnalysisResultRequest(
            String status, String changeSummary, List<FactDeltaItem> factDeltas, Integer scannedSectionCount,
            Integer candidateSectionCount, List<InternalImpactInput> impacts, Long actualCostMicros, String errorMessage
    ) {}

    public record InternalPatchTarget(
            UUID patchId, UUID impactId, UUID targetSectionId, int chapterNo, int sectionNo, String chapterTitle,
            String direction, String conflictReason, long baseVersion, String currentContent
    ) {}

    public record InternalPatchBatchContext(
            UUID jobId, UUID bookId, UUID ownerId, int batchNo, String genreCode, String changeSummary,
            String styleAnchorsText, List<FactDeltaItem> factDeltas, List<InternalPatchTarget> targets
    ) {}

    public record InternalPatchResultItem(UUID patchId, String status, List<PatchHunkItem> hunks) {}

    public record InternalPatchBatchResultRequest(
            List<InternalPatchResultItem> patches, Integer inputTokens, Integer cachedTokens, Integer outputTokens,
            String errorMessage
    ) {}
}
