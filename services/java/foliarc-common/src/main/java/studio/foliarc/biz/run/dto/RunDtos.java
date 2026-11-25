package studio.foliarc.biz.run.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

            UUID sectionId,

            @Size(max = 100) String povCharacter,
            List<@Valid ForeshadowingDirective> foreshadowingDirectives
    ) {
        public StartRunRequest(UUID bookId, UUID chapterId, String mode, String action, String modelAlias,
                               String instruction, String chapterContext, Boolean requireOutlineApproval, UUID sectionId) {
            this(bookId, chapterId, mode, action, modelAlias, instruction, chapterContext,
                    requireOutlineApproval, sectionId, null, null);
        }

        public StartRunRequest(UUID bookId, UUID chapterId, String mode, String action, String modelAlias,
                               String instruction, String chapterContext, Boolean requireOutlineApproval) {
            this(bookId, chapterId, mode, action, modelAlias, instruction, chapterContext, requireOutlineApproval, null);
        }
    }

    public record ForeshadowingDirective(@NotNull UUID foreshadowingId, @NotBlank String mode, String resolveType) {}

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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InternalRunEventInput(
            @JsonProperty("seq") Integer seq,
            @JsonProperty("event_type") @Size(max = 48) String eventType,
            @JsonProperty("agent_name") @Size(max = 32) String agentName,
            @JsonProperty("trace_id") @Size(max = 64) String traceId,
            @JsonProperty("latency_ms") Integer latencyMs,
            @JsonProperty("payload_json") String payloadJson
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InternalSaveRunEventsRequest(List<@Valid InternalRunEventInput> events) {
    }
}
