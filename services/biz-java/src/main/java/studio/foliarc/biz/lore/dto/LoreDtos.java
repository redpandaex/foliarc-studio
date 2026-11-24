package studio.foliarc.biz.lore.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class LoreDtos {

    private LoreDtos() {
    }

    public record UpsertCharacterRequest(
            @NotBlank(message = "角色姓名不能为空")
            @Size(max = 100)
            String name,
            @Size(max = 32) String roleType,
            @Size(max = 2000) String personality,
            @Size(max = 120) String realmOrLevel,
            @Size(max = 160) String currentLocation,
            @Size(max = 2000) String statusNotes,
            @Size(max = 2000) String inventory
    ) {
    }

    public record CharacterResponse(
            UUID id,
            UUID bookId,
            String name,
            String roleType,
            String personality,
            String realmOrLevel,
            String currentLocation,
            String statusNotes,
            String inventory,
            OffsetDateTime updatedAt
    ) {
    }

    public record UpsertWorldEntryRequest(
            @Size(max = 32) String category,
            @NotBlank(message = "词条标题不能为空")
            @Size(max = 160)
            String title,
            @Size(max = 8000) String content,
            @Size(max = 255) String tags
    ) {
    }

    public record WorldEntryResponse(
            UUID id,
            UUID bookId,
            String category,
            String title,
            String content,
            String tags,
            OffsetDateTime updatedAt
    ) {
    }

    public record UpsertForeshadowingRequest(
            @NotBlank(message = "伏笔标题不能为空")
            @Size(max = 160)
            String title,
            @Size(max = 4000) String description,
            Integer plantedChapterNo,
            Integer resolvedChapterNo,
            @Size(max = 32) String status
    ) {
    }

    public record ForeshadowingResponse(
            UUID id,
            UUID bookId,
            String title,
            String description,
            Integer plantedChapterNo,
            Integer resolvedChapterNo,
            String status,
            OffsetDateTime updatedAt
    ) {
    }

    public record ChapterExcerptResponse(
            UUID chapterId,
            int chapterNo,
            String title,
            int wordCount,
            String excerpt
    ) {
    }

    public record PromptTemplateResponse(
            UUID id,
            String name,
            int version,
            String stage,
            String description,
            String templateBody
    ) {
    }

    public record NovelBibleResponse(
            UUID bookId,
            String bookTitle,
            String genre,
            String synopsis,
            List<CharacterResponse> characters,
            List<WorldEntryResponse> worldEntries,
            List<ForeshadowingResponse> openForeshadowings
    ) {
    }
}
