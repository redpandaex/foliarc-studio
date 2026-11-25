package studio.foliarc.biz.style.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class StyleAnchorDtos {

    private StyleAnchorDtos() {}

    public record UpsertStyleAnchorRequest(
            UUID id,
            @Size(max = 120, message = "文风锚点标题最多 120 个字符") String title,
            @NotBlank(message = "文风锚点摘录不能为空") String excerptText,
            Integer sortOrder,
            Boolean enabled
    ) {}

    public record StyleAnchorResponse(
            UUID id,
            UUID bookId,
            String title,
            String excerptText,
            int sortOrder,
            boolean enabled,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}
}
