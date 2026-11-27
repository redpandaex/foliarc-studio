package studio.foliarc.biz.visual.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

public final class VisualDtos {

    private VisualDtos() {
    }

    public record VisualCapabilities(
            boolean visualExplore,
            boolean storyboard,
            boolean branching,
            boolean pacingAtlas
    ) {
        public static VisualCapabilities phase7A() {
            return new VisualCapabilities(true, false, false, false);
        }
    }

    public record VisualCutoffInfo(
            String visibility,
            UUID chapterId,
            int chapterNo,
            UUID sectionId,
            Integer sectionNo,
            long narrativeOrder,
            int totalChapters
    ) {
    }

    public record VisualChapterAxisItem(
            UUID id,
            UUID volumeId,
            int volumeNo,
            String volumeTitle,
            int chapterNo,
            String title,
            int wordCount,
            String status,
            int sectionCount,
            UUID firstSectionId
    ) {
    }

    public record VisualGraphNode(
            String id,
            UUID characterId,
            String name,
            String roleType,
            String lifecycleStatus,
            String faction,
            String realmOrLevel,
            String currentLocation,
            String statusSummary,
            int appearanceCount,
            int relationCount,
            Integer firstSeenChapterNo,
            Integer lastSeenChapterNo,
            boolean unresolved
    ) {
    }

    public record VisualGraphEdge(
            UUID id,
            String sourceId,
            String targetId,
            String sourceName,
            String targetName,
            String relationType,
            String description,
            Integer sinceChapterNo,
            UUID sourceSectionId,
            long narrativeOrder,
            String source,
            String knownBy,
            double confidence,
            boolean undated,
            OffsetDateTime updatedAt
    ) {
    }

    public record VisualTimelineLaneEvent(
            UUID id,
            int chapterNo,
            UUID chapterId,
            UUID sourceSectionId,
            long narrativeOrder,
            String eventTitle,
            String summary,
            List<String> involvedCharacters,
            String location,
            String source,
            String knownBy
    ) {
    }

    public record VisualForeshadowingArc(
            UUID id,
            String code,
            String title,
            String surfaceClue,
            String hiddenTruth,
            String importance,
            String status,
            String alertState,
            Integer plantedChapterNo,
            UUID plantedSectionId,
            Integer targetChapterMin,
            Integer targetChapterMax,
            Integer resolvedChapterNo,
            UUID resolvedSectionId,
            List<Integer> hintChapters,
            List<String> involvedCharacters,
            boolean continuesLeft,
            boolean continuesRight,
            boolean openEnded,
            List<String> allowedDirectorModes
    ) {
    }

    public record VisualEntityLane(
            String id,
            String kind,
            String label,
            String roleOrTag,
            List<Integer> activeChapters,
            Map<String, List<String>> eventIdsByChapter,
            int gapFromCutoff
    ) {
    }

    public record VisualProjectionResponse(
            UUID bookId,
            String view,
            VisualCapabilities capabilities,
            VisualCutoffInfo cutoff,
            String etag,
            List<VisualChapterAxisItem> chapters,
            List<VisualGraphNode> nodes,
            List<VisualGraphEdge> edges,
            List<String> factions,
            List<VisualTimelineLaneEvent> events,
            List<VisualForeshadowingArc> arcs,
            List<VisualEntityLane> entityLanes,
            int totalCharacters,
            int visibleCharacters,
            int undatedRelationCount,
            double evidenceCoverageRatio
    ) {
    }

    public record VisualHeatCell(
            int chapterNo,
            UUID chapterId,
            UUID firstSectionId,
            String chapterTitle,
            int evidenceCount
    ) {
    }

    public record VisualEvidenceItem(
            String kind,
            Integer chapterNo,
            UUID chapterId,
            UUID sectionId,
            String title,
            String snippet
    ) {
    }

    public record VisualEntityDetailResponse(
            String kind,
            String id,
            String title,
            String subtitle,
            String visibility,
            Map<String, Object> visibleFields,
            List<VisualHeatCell> heatByChapter,
            List<VisualEvidenceItem> evidence,
            List<VisualGraphEdge> relatedEdges,
            List<VisualForeshadowingArc> relatedArcs,
            boolean editable
    ) {
    }

    public record DirectorCharacterOption(
            UUID id,
            String name,
            String roleType
    ) {
    }

    public record VisualDirectorOptionsResponse(
            String visibility,
            int cutoffChapterNo,
            List<DirectorCharacterOption> characters,
            List<VisualForeshadowingArc> foreshadowings
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdateVisualRelationRequest(
            String sourceCharacter,
            String targetCharacter,
            String relationType,
            String description,
            Integer sinceChapterNo,
            UUID sourceSectionId,
            String knownBy,
            OffsetDateTime expectedUpdatedAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdateVisualEventRequest(
            Integer chapterNo,
            String eventTitle,
            String summary,
            String involvedCharacters,
            String location,
            UUID sourceSectionId,
            String knownBy
    ) {
    }
}
