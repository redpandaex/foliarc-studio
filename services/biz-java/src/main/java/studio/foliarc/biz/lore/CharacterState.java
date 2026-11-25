package studio.foliarc.biz.lore;

import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import studio.foliarc.biz.lore.dto.LoreDtos;
import studio.foliarc.biz.lore.entity.CharacterEntity;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CharacterState(
        String roleType, String personality, String realmOrLevel, String currentLocation,
        String statusNotes, String inventory, String aliases, String lifecycleStatus, String arcScope,
        Map<String, String> customAttributes
) {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> ATTRIBUTE_TYPE = new TypeReference<>() {};

    public CharacterState {
        customAttributes = Objects.requireNonNullElse(customAttributes, Map.of());
    }

    public CharacterState(String roleType, String personality, String realmOrLevel, String currentLocation,
                          String statusNotes, String inventory, String aliases, String lifecycleStatus, String arcScope) {
        this(roleType, personality, realmOrLevel, currentLocation, statusNotes, inventory,
                aliases, lifecycleStatus, arcScope, Map.of());
    }

    public static Map<String, String> readCustomAttributes(String json) {
        if (json == null || json.isBlank()) return Map.of();
        return Objects.requireNonNullElse(JSON.readValue(json, ATTRIBUTE_TYPE), Map.of());
    }

    public static String writeCustomAttributes(Map<String, String> attributes) {
        return JSON.writeValueAsString(attributes);
    }

    public static CharacterState readSnapshot(String json) {
        return json == null || json.isBlank() ? null : JSON.readValue(json, CharacterState.class);
    }

    public static String captureSnapshot(CharacterEntity row) {
        return JSON.writeValueAsString(of(row));
    }

    public static CharacterState of(CharacterEntity row) {
        return new CharacterState(row.getRoleType(), row.getPersonality(),
                row.getRealmOrLevel(), row.getCurrentLocation(), row.getStatusNotes(), row.getInventory(),
                row.getAliases(), row.getLifecycleStatus(), row.getArcScope(), readCustomAttributes(row.getCustomAttributes()));
    }

    public static CharacterState of(LoreDtos.CharacterResponse row) {
        return new CharacterState(row.roleType(), row.personality(), row.realmOrLevel(), row.currentLocation(),
                row.statusNotes(), row.inventory(), row.aliases(), row.lifecycleStatus(), row.arcScope(), row.customAttributes());
    }

    public void restore(CharacterEntity row) {
        if (roleType != null) row.setRoleType(roleType);
        if (lifecycleStatus != null) row.setLifecycleStatus(lifecycleStatus);
        row.setPersonality(Objects.requireNonNullElse(personality, ""));
        row.setRealmOrLevel(Objects.requireNonNullElse(realmOrLevel, ""));
        row.setCurrentLocation(Objects.requireNonNullElse(currentLocation, ""));
        row.setStatusNotes(Objects.requireNonNullElse(statusNotes, ""));
        row.setInventory(Objects.requireNonNullElse(inventory, ""));
        row.setAliases(Objects.requireNonNullElse(aliases, ""));
        row.setArcScope(Objects.requireNonNullElse(arcScope, ""));
        row.setCustomAttributes(writeCustomAttributes(customAttributes));
    }
}
