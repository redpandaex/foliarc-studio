package studio.foliarc.biz.lore;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class LoreNames {

    private LoreNames() {
    }

    public static List<String> split(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.split("[,，、]"))
                .map(String::strip).filter(name -> !name.isEmpty()).distinct().toList();
    }

    public static String normalize(String text) {
        return String.join(",", split(text));
    }

    public static String mergeAliases(String canonicalName, String existing, String extracted) {
        Set<String> aliases = new LinkedHashSet<>(split(existing));
        aliases.addAll(split(extracted));
        aliases.remove(canonicalName);
        return String.join(",", aliases);
    }

    public static String fromJson(Object names) {
        if (names instanceof Collection<?> list) {
            return String.join(",", list.stream().filter(String.class::isInstance).map(String.class::cast).toList());
        }
        return names instanceof String text ? text : null;
    }

    public static String normalizeKnownBy(String knownBy) {
        List<String> names = split(knownBy);
        if (names.isEmpty() || names.stream().anyMatch(name -> "PUBLIC".equalsIgnoreCase(name) || "公开".equals(name))) {
            return "PUBLIC";
        }
        return String.join(",", names);
    }
}
