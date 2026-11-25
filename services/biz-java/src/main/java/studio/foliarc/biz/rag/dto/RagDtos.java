package studio.foliarc.biz.rag.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
            String metadataJson,
            @JsonAlias("narrative_order") Long narrativeOrder,
            @JsonAlias("section_id") UUID sectionId
    ) {
        public InternalChunkInput(
                UUID id, String chunkLevel, UUID parentChunkId, Integer chunkIndex, Integer chapterNo,
                String sceneTitle, String content, Integer tokenCount, String metadataJson
        ) {
            this(id, chunkLevel, parentChunkId, chunkIndex, chapterNo, sceneTitle, content, tokenCount, metadataJson, null, null);
        }
    }

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
            OffsetDateTime updatedAt,
            UUID sourceSectionId,
            Long narrativeOrder,
            String source,
            String knownBy
    ) {
        public CharacterRelationResponse(UUID id, UUID bookId, String sourceCharacter, String targetCharacter,
                                         String relationType, String description, Integer sinceChapterNo,
                                         Double confidence, OffsetDateTime updatedAt) {
            this(id, bookId, sourceCharacter, targetCharacter, relationType, description, sinceChapterNo,
                    confidence, updatedAt, null, 0L, "AUTHOR", "PUBLIC");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpsertRelationRequest(
            @NotBlank String sourceCharacter,
            @NotBlank String targetCharacter,
            @NotBlank String relationType,
            String description,
            Integer sinceChapterNo,
            Double confidence,
            UUID sourceSectionId,
            Long narrativeOrder,
            String source,
            String knownBy
    ) {
        public UpsertRelationRequest(String sourceCharacter, String targetCharacter, String relationType,
                                     String description, Integer sinceChapterNo, Double confidence) {
            this(sourceCharacter, targetCharacter, relationType, description, sinceChapterNo, confidence, null);
        }

        public UpsertRelationRequest(String sourceCharacter, String targetCharacter, String relationType,
                                     String description, Integer sinceChapterNo, Double confidence, String knownBy) {
            this(sourceCharacter, targetCharacter, relationType, description, sinceChapterNo, confidence,
                    null, null, null, knownBy);
        }
    }

    public record TimelineEventResponse(
            UUID id,
            UUID bookId,
            Integer chapterNo,
            String eventTitle,
            String summary,
            String involvedCharacters,
            String location,
            OffsetDateTime updatedAt,
            UUID sourceSectionId,
            Long narrativeOrder,
            String source,
            String knownBy
    ) {
        public TimelineEventResponse(UUID id, UUID bookId, Integer chapterNo, String eventTitle,
                                     String summary, String involvedCharacters, String location, OffsetDateTime updatedAt) {
            this(id, bookId, chapterNo, eventTitle, summary, involvedCharacters, location,
                    updatedAt, null, 0L, "AUTHOR", "PUBLIC");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpsertTimelineEventRequest(
            Integer chapterNo,
            @NotBlank String eventTitle,
            String summary,
            String involvedCharacters,
            String location,
            UUID sourceSectionId,
            Long narrativeOrder,
            String source,
            String knownBy
    ) {
        public UpsertTimelineEventRequest(Integer chapterNo, String eventTitle, String summary,
                                          String involvedCharacters, String location) {
            this(chapterNo, eventTitle, summary, involvedCharacters, location, null);
        }

        public UpsertTimelineEventRequest(Integer chapterNo, String eventTitle, String summary,
                                          String involvedCharacters, String location, String knownBy) {
            this(chapterNo, eventTitle, summary, involvedCharacters, location, null, null, null, knownBy);
        }
    }

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
            @JsonAlias("foreshadowingSignals") List<LoreDtos.UpsertForeshadowingRequest> foreshadowingUpdates,
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

    public record InternalSectionPayload(
            UUID sectionId, UUID chapterId, UUID volumeId, UUID bookId, UUID ownerId,
            int chapterNo, int sectionNo, long narrativeOrder, String chapterTitle, String sectionTitle,
            String content, String contentHash, String volumeArcGoal, String prevEndingHook, String firstSectionSample,
            String latestSectionSample, String bookTitle, String genre, String synopsis
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DistilledSectionSummary(
            @Size(max = 255) String timeAndPlace,
            @NotBlank String summaryText,
            String stateChanges, String clueSignals, String povNotes, String involvedCharacters, String endingHook
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InternalApplyDistillRequest(
            @NotNull UUID sectionId,
            @NotNull @Size(max = 64) String contentHash,
            @NotNull @Valid DistilledSectionSummary summary,
            ApplyExtractedMemoryRequest memory
    ) {}

    public record InternalApplyDistillResponse(
            boolean saved, UUID sectionId, UUID summaryId, boolean rollupQueued,
            boolean refreshAutoCharter, ApplyExtractedMemoryResponse memory
    ) {}

    public record SectionSummaryItem(
            UUID id, UUID sectionId, UUID chapterId, int chapterNo, int sectionNo, long narrativeOrder,
            String chapterTitle, String sectionTitle, String contentHash, String timeAndPlace, String summaryText,
            String stateChanges, String clueSignals, String povNotes, String involvedCharacters, String endingHook,
            String status
    ) {}

    public record StoryRollupItem(
            UUID nodeId, int tierLevel, int seqNo, UUID volumeId, long startOrder, long endOrder,
            int startChapterNo, int endChapterNo, String title, String summaryText, String openThreads,
            String sourceHash, String status
    ) {}

    public record RollupVolumeItem(
            UUID id, int volumeNo, String title, String arcGoal, String pacingNotes, String realmScope,
            Integer startChapterNo, Integer endChapterNo, String status
    ) {}

    public record InternalBookRollupsPayload(
            UUID bookId, UUID ownerId, String bookTitle, String genre, String synopsis,
            List<SectionSummaryItem> sectionSummaries, List<StoryRollupItem> rollups,
            List<RollupVolumeItem> volumes, String firstSectionSample, String latestSectionSample,
            String existingAutoCharter, String charterSource
    ) {}

    public record InternalSaveRollupRequest(
            int tierLevel, int seqNo, UUID volumeId, long startOrder, long endOrder,
            Integer startChapterNo, Integer endChapterNo,
            @Size(max = 200) String title,
            @NotBlank String summaryText,
            String openThreads,
            @Size(max = 64) String sourceHash
    ) {}

    public record InternalSaveRollupResponse(UUID nodeId, int tierLevel, int seqNo, long startOrder, long endOrder) {}

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
