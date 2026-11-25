package studio.foliarc.biz.genre;

import java.util.List;

public final class GenreDtos {

    private GenreDtos() {}

    public record CharacterFieldDef(String key, String label, String placeholder) {}

    public record GenreProfileResponse(
            String code,
            String displayName,
            String shortName,
            String description,
            List<String> recommendedSubTags,
            List<CharacterFieldDef> characterFieldSchema,
            String stylePrinciples,
            List<String> antiClicheRules,
            String defaultBeatPattern,
            List<String> criticRubric,
            String summaryFocus
    ) {}
}
