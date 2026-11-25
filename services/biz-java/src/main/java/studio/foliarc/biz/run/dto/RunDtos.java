package studio.foliarc.biz.run.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class RunDtos {

    private RunDtos() {
    }

    public record StartRunRequest(
            @NotNull(message = "bookId 不能为空")
            UUID bookId,

            UUID chapterId,

            @Size(max = 24)
            String mode,

            @Size(max = 32)
            String action,

            @Size(max = 64)
            String modelAlias,

            @Size(max = 4000)
            String instruction,

            String chapterContext,

            Boolean requireOutlineApproval,

            UUID sectionId
    ) {
        public StartRunRequest(UUID bookId, UUID chapterId, String mode, String action, String modelAlias,
                               String instruction, String chapterContext, Boolean requireOutlineApproval) {
            this(bookId, chapterId, mode, action, modelAlias, instruction, chapterContext, requireOutlineApproval, null);
        }
    }

    public record ResumeRunRequest(
            @Size(max = 4000)
            String revisedInstruction
    ) {
    }

    public record ApproveRunRequest(
            Boolean approved,

            @Size(max = 8000)
            String editedOutline,

            @Size(max = 4000)
            String feedback
    ) {
    }

    public record RunResponse(
            UUID id,
            UUID bookId,
            UUID chapterId,
            String mode,
            String action,
            String modelAlias,
            String instruction,
            String status,
            String accumulatedText,
            int lastSeq,
            String lastEventId,
            int inputTokens,
            int cachedTokens,
            int outputTokens,
            String errorMessage,
            String currentNode,
            String interruptType,
            String interruptPayloadJson,
            String routeIntent,
            int revisionCount,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record CheckpointResponse(
            UUID id,
            UUID runId,
            int stepNo,
            String nodeName,
            String status,
            String stateJson,
            OffsetDateTime createdAt
    ) {
    }

    public record InternalStateUpdateRequest(
            String status,
            @JsonProperty("accumulated_text") String accumulatedText,
            @JsonProperty("last_seq") Integer lastSeq,
            @JsonProperty("last_event_id") String lastEventId,
            @JsonProperty("input_tokens") Integer inputTokens,
            @JsonProperty("cached_tokens") Integer cachedTokens,
            @JsonProperty("output_tokens") Integer outputTokens,
            @JsonProperty("error_message") String errorMessage,
            @JsonProperty("current_node") String currentNode,
            @JsonProperty("interrupt_type") String interruptType,
            @JsonProperty("interrupt_payload_json") String interruptPayloadJson,
            @JsonProperty("route_intent") String routeIntent,
            @JsonProperty("revision_count") Integer revisionCount,
            @JsonProperty("sandbox_calls") Integer sandboxCalls
    ) {
    }

    public record InternalSaveCheckpointRequest(
            @JsonProperty("step_no") Integer stepNo,
            @JsonProperty("node_name") String nodeName,
            String status,
            @JsonProperty("state_json") String stateJson
    ) {
    }
}
