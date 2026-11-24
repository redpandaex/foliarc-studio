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
            String content
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
}
