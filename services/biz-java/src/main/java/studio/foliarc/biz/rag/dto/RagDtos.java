package studio.foliarc.biz.rag.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import studio.foliarc.biz.lore.dto.LoreDtos;

public final class RagDtos {

    private RagDtos() {}

    public record KnowledgeDocumentResponse(
            UUID id,
            UUID bookId,
            String sourceType,
            UUID sourceChapterId,
            Integer chapterNo,
            String docType,
            String title,
            String fileName,
            String contentType,
            Long fileSize,
            Integer docVersion,
            String status,
            Integer progress,
            Integer chunkCount,
            String errorMessage,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    public record SyncChapterRequest(
            Boolean extractMemory
    ) {}

    public record SyncChapterResponse(
            KnowledgeDocumentResponse document,
            boolean memoryExtractionQueued
    ) {}

    public record RagSearchRequest(
            @NotBlank(message = "查询语句不能为空")
            @Size(max = 1000)
            String query,
            Integer topK,
            Integer maxChapterNo,
            String docType,
            Boolean rewriteQuery
    ) {}

    public record InternalStatusUpdateRequest(
            Integer docVersion,
            @NotBlank String status,
            Integer progress,
            Integer chunkCount,
            String errorMessage
    ) {}

    public record InternalChunkInput(
            UUID id,
            String chunkLevel,
            UUID parentChunkId,
            Integer chunkIndex,
            Integer chapterNo,
            String sceneTitle,
            String content,
            Integer tokenCount,
            String metadataJson
    ) {}

    public record InternalSaveChunksRequest(
            Integer docVersion,
            List<InternalChunkInput> chunks
    ) {}

    public record InternalIdempotencyRequest(
            @NotBlank String idempotencyKey,
            @NotBlank String consumerName
    ) {}

    public record InternalIdempotencyResponse(
            boolean claimed
    ) {}

    public record InternalParentChunkResponse(
            UUID id,
            UUID documentId,
            String documentTitle,
            String docType,
            Integer docVersion,
            Integer chapterNo,
            String sceneTitle,
            String content,
            String scope
    ) {}

    public record InternalChapterPayload(
            UUID chapterId,
            UUID bookId,
            UUID ownerId,
            Integer chapterNo,
            String title,
            String content,
            String status
    ) {}

    public record CharacterRelationResponse(
            UUID id,
            UUID bookId,
            String sourceCharacter,
            String targetCharacter,
            String relationType,
            String description,
            Integer sinceChapterNo,
            Double confidence,
            OffsetDateTime updatedAt
    ) {}

    public record UpsertRelationRequest(
            @NotBlank String sourceCharacter,
            @NotBlank String targetCharacter,
            @NotBlank String relationType,
            String description,
            Integer sinceChapterNo,
            Double confidence
    ) {}

    public record TimelineEventResponse(
            UUID id,
            UUID bookId,
            Integer chapterNo,
            String eventTitle,
            String summary,
            String involvedCharacters,
            String location,
            OffsetDateTime updatedAt
    ) {}

    public record UpsertTimelineEventRequest(
            Integer chapterNo,
            @NotBlank String eventTitle,
            String summary,
            String involvedCharacters,
            String location
    ) {}

    public record StructuredMemoryResponse(
            UUID bookId,
            List<LoreDtos.CharacterResponse> characters,
            List<LoreDtos.ForeshadowingResponse> foreshadowings,
            List<CharacterRelationResponse> relations,
            List<TimelineEventResponse> timelineEvents
    ) {}

    public record ApplyExtractedMemoryRequest(
            Integer chapterNo,
            List<LoreDtos.UpsertCharacterRequest> characterUpdates,
            List<LoreDtos.UpsertForeshadowingRequest> foreshadowingUpdates,
            List<UpsertRelationRequest> relations,
            List<UpsertTimelineEventRequest> timelineEvents
    ) {}

    public record ApplyExtractedMemoryResponse(
            int charactersUpserted,
            int foreshadowingsUpserted,
            int relationsUpserted,
            int timelineEventsUpserted
    ) {}

    public record RawSearchProxyResponse(
            Map<String, Object> result
    ) {}

    // --- Phase 5.5: Hierarchical Summaries, Auto-Charter & Three-Scope Library DTOs ---

    public record ChapterSummaryItem(
            UUID chapterId,
            int chapterNo,
            String chapterTitle,
            String summaryText,
            String keyEvents,
            String involvedCharacters,
            String endingHook,
            String status
    ) {}

    public record VolumeSummaryItem(
            int volumeNo,
            int startChapterNo,
            int endChapterNo,
            String summaryText,
            String status
    ) {}

    public record InternalBookSummariesPayload(
            UUID bookId,
            UUID ownerId,
            String bookTitle,
            String genre,
            String synopsis,
            String charterSource,
            List<ChapterSummaryItem> chapterSummaries,
            List<VolumeSummaryItem> volumeSummaries
    ) {}

    public record InternalSaveChapterSummaryRequest(
            UUID chapterId,
            Integer chapterNo,
            String chapterTitle,
            String contentHash,
            String summaryText,
            String keyEvents,
            String involvedCharacters,
            String endingHook
    ) {}

    public record InternalSaveChapterSummaryResponse(
            boolean saved,
            boolean refreshVolumeSummary,
            int volumeNo,
            int volumeStartChapterNo,
            int volumeEndChapterNo,
            boolean refreshAutoCharter,
            int totalSummariesCount
    ) {}

    public record InternalSaveVolumeSummaryRequest(
            Integer volumeNo,
            Integer startChapterNo,
            Integer endChapterNo,
            String summaryText,
            String sourceHash
    ) {}

    public record InternalSaveAutoCharterRequest(
            String autoCharter,
            Integer chapterCount
    ) {}

    public record LibraryDocumentResponse(
            UUID id,
            UUID bookId,
            String scope,
            String accessType,
            String docType,
            String title,
            String fileName,
            Long fileSize,
            String genreTags,
            boolean readonly,
            boolean enabled,
            boolean boundToBook,
            String status,
            Integer progress,
            Integer chunkCount,
            String errorMessage,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    public record UpdateBookBindingRequest(
            boolean enabled
    ) {}

    public record GrantDocumentRequest(
            UUID granteeUserId,
            String permission
    ) {}

    public record InternalAccessibleDocsResponse(
            List<String> userDocIds,
            List<String> assignedDocIds,
            List<String> globalDocIds
    ) {}
}
