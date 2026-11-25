package studio.foliarc.biz.memory.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;

public final class MemoryProposalDtos {

    private MemoryProposalDtos() {
    }

    public record MemoryProposalResponse(
            UUID id, UUID bookId, UUID sectionId, String proposalType, UUID targetEntityId,
            String targetName, String summary, String diffJson, String status,
            OffsetDateTime createdAt, OffsetDateTime resolvedAt
    ) {}

    public record ProposalDecisionRequest(@NotBlank String decision) {}
}
