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

            @Size(max = 16)
            String mode,

            @Size(max = 32)
            String action,

            @Size(max = 64)
            String modelAlias,

            @Size(max = 4000)
            String instruction,

            String chapterContext
    ) {
    }

    public record ResumeRunRequest(
            @Size(max = 4000)
            String revisedInstruction
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
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
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
            @JsonProperty("error_message") String errorMessage
    ) {
    }
}
