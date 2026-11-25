package studio.foliarc.biz.lore.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import studio.foliarc.biz.lore.LoreNames;

public final class LoreDtos {

    private LoreDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpsertCharacterRequest(
            @NotBlank(message = "角色姓名不能为空")
            @Size(max = 100)
            String name,
            @Size(max = 32) String roleType,
            @Size(max = 2000) String personality,
            @Size(max = 120) String realmOrLevel,
            @Size(max = 160) String currentLocation,
            @Size(max = 2000) String statusNotes,
            @Size(max = 2000) String inventory,
            String aliases,
            @Size(max = 24) String lifecycleStatus,
            @PositiveOrZero Long lastSeenOrder,
            @Size(max = 120) String arcScope,
            List<String> lockedFields,
            Boolean isHighRisk,
            Map<String, String> customAttributes
    ) {
        public UpsertCharacterRequest(String name, String roleType, String personality, String realmOrLevel,
                                      String currentLocation, String statusNotes, String inventory) {
            this(name, roleType, personality, realmOrLevel, currentLocation, statusNotes, inventory,
                    null, null, null, null, null, null);
        }

        public UpsertCharacterRequest(String name, String roleType, String personality, String realmOrLevel,
                                      String currentLocation, String statusNotes, String inventory, String aliases,
                                      String lifecycleStatus, Long lastSeenOrder, String arcScope, List<String> lockedFields) {
            this(name, roleType, personality, realmOrLevel, currentLocation, statusNotes, inventory,
                    aliases, lifecycleStatus, lastSeenOrder, arcScope, lockedFields, null);
        }

        public UpsertCharacterRequest(String name, String roleType, String personality, String realmOrLevel,
                                      String currentLocation, String statusNotes, String inventory, String aliases,
                                      String lifecycleStatus, Long lastSeenOrder, String arcScope, List<String> lockedFields,
                                      Boolean isHighRisk) {
            this(name, roleType, personality, realmOrLevel, currentLocation, statusNotes, inventory,
                    aliases, lifecycleStatus, lastSeenOrder, arcScope, lockedFields, isHighRisk, null);
        }

        public static UpsertCharacterRequest fromJson(
                String name, String roleType, String personality, String realmOrLevel, String currentLocation,
                String statusNotes, String inventory, Object aliases, String lifecycleStatus, Long lastSeenOrder,
                String arcScope, List<String> lockedFields, Boolean isHighRisk
        ) {
            return fromJson(name, roleType, personality, realmOrLevel, currentLocation, statusNotes,
                    inventory, aliases, lifecycleStatus, lastSeenOrder, arcScope, lockedFields, isHighRisk, null);
        }

        // Section workers may send an array, while the author API stores comma-separated aliases.
        @JsonCreator
        public static UpsertCharacterRequest fromJson(
                @JsonProperty("name") String name,
                @JsonProperty("roleType") String roleType,
                @JsonProperty("personality") String personality,
                @JsonProperty("realmOrLevel") String realmOrLevel,
                @JsonProperty("currentLocation") String currentLocation,
                @JsonProperty("statusNotes") String statusNotes,
                @JsonProperty("inventory") @JsonAlias("inventoryDelta") String inventory,
                @JsonProperty("aliases") Object aliases,
                @JsonProperty("lifecycleStatus") String lifecycleStatus,
                @JsonProperty("lastSeenOrder") Long lastSeenOrder,
                @JsonProperty("arcScope") String arcScope,
                @JsonProperty("lockedFields") List<String> lockedFields,
                @JsonProperty("isHighRisk") Boolean isHighRisk,
                @JsonProperty("customAttributes") @JsonAlias("custom_attributes") Map<String, String> customAttributes
        ) {
            return new UpsertCharacterRequest(name, roleType, personality, realmOrLevel, currentLocation,
                    statusNotes, inventory, LoreNames.fromJson(aliases), lifecycleStatus, lastSeenOrder,
                    arcScope, lockedFields, isHighRisk, customAttributes);
        }
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
            OffsetDateTime updatedAt,
            String aliases,
            String lifecycleStatus,
            Long lastSeenOrder,
            String arcScope,
            List<String> lockedFields,
            Map<String, String> customAttributes
    ) {
        public CharacterResponse(UUID id, UUID bookId, String name, String roleType, String personality,
                                 String realmOrLevel, String currentLocation, String statusNotes,
                                 String inventory, OffsetDateTime updatedAt) {
            this(id, bookId, name, roleType, personality, realmOrLevel, currentLocation, statusNotes,
                    inventory, updatedAt, "", "PROTAGONIST".equals(roleType) ? "CORE_PINNED" : "ACTIVE",
                    0L, "", List.of());
        }

        public CharacterResponse(UUID id, UUID bookId, String name, String roleType, String personality,
                                 String realmOrLevel, String currentLocation, String statusNotes, String inventory,
                                 OffsetDateTime updatedAt, String aliases, String lifecycleStatus,
                                 Long lastSeenOrder, String arcScope, List<String> lockedFields) {
            this(id, bookId, name, roleType, personality, realmOrLevel, currentLocation, statusNotes,
                    inventory, updatedAt, aliases, lifecycleStatus, lastSeenOrder, arcScope, lockedFields, Map.of());
        }
    }

    public record UpdateCharacterLocksRequest(@NotNull List<String> lockedFields) {}

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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpsertForeshadowingRequest(
            @Size(max = 160) String title,
            @Size(max = 4000) String description,
            Integer plantedChapterNo,
            Integer resolvedChapterNo,
            @Size(max = 32) @JsonAlias("action") String status,
            UUID id,
            @Size(max = 16) String code,
            @Size(max = 24) String importance,
            @Size(max = 24) String revealPolicy,
            String surfaceClue,
            String hiddenTruth,
            UUID plantedSectionId,
            Long plantedNarrativeOrder,
            @JsonAlias("quote") String plantedQuote,
            UUID resolvedSectionId,
            @Positive Integer targetChapterMin,
            @Positive Integer targetChapterMax,
            String involvedCharacters,
            Boolean authorLocked
    ) {
        public UpsertForeshadowingRequest(String title, String description, Integer plantedChapterNo,
                                         Integer resolvedChapterNo, String status) {
            this(title, description, plantedChapterNo, resolvedChapterNo, status, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    public record ForeshadowingResponse(
            UUID id,
            UUID bookId,
            String title,
            String description,
            Integer plantedChapterNo,
            Integer resolvedChapterNo,
            String status,
            OffsetDateTime updatedAt,
            String code,
            String importance,
            String revealPolicy,
            String surfaceClue,
            String hiddenTruth,
            UUID plantedSectionId,
            Long plantedNarrativeOrder,
            String plantedQuote,
            UUID resolvedSectionId,
            Integer targetChapterMin,
            Integer targetChapterMax,
            String involvedCharacters,
            String hintHistoryJson,
            String source,
            Boolean authorLocked
    ) {
        public ForeshadowingResponse(UUID id, UUID bookId, String title, String description, Integer plantedChapterNo,
                                    Integer resolvedChapterNo, String status, OffsetDateTime updatedAt) {
            this(id, bookId, title, description, plantedChapterNo, resolvedChapterNo, status, updatedAt,
                    "", "SUBPLOT", "HIDE_TRUTH", description, "", null, null, "", null,
                    null, null, "", "[]", "AUTHOR", false);
        }

        public String writerSurface(String text) {
            if (text == null) return "";
            return hiddenTruth == null || hiddenTruth.isBlank() ? text : text.replace(hiddenTruth, "【伏笔底牌】");
        }

        public ForeshadowingResponse forAgent() {
            String clue = writerSurface(surfaceClue);
            return new ForeshadowingResponse(id, bookId, writerSurface(title), clue, plantedChapterNo, resolvedChapterNo,
                    status, updatedAt, code, importance, revealPolicy, clue, "", plantedSectionId, plantedNarrativeOrder,
                    "", resolvedSectionId, targetChapterMin, targetChapterMax, involvedCharacters, "[]", source, authorLocked);
        }
    }

    public record TransitionForeshadowingRequest(
            @NotBlank String targetStatus, UUID sectionId, @Positive Integer chapterNo, @Positive Integer sectionNo,
            String quote, String note
    ) {}

    public record DueForeshadowingsResponse(
            int chapterNo, List<ForeshadowingResponse> dueNow, List<ForeshadowingResponse> readyToPlant,
            List<ForeshadowingResponse> activePlanted
    ) {}

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
