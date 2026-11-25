package studio.foliarc.biz.lore;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import studio.foliarc.biz.lore.dto.LoreDtos;
import studio.foliarc.biz.lore.entity.CharacterEntity;
import tools.jackson.databind.ObjectMapper;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CharacterState(
        String roleType, String personality, String realmOrLevel, String currentLocation,
        String statusNotes, String inventory, String aliases, String lifecycleStatus, String arcScope
) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static CharacterState readSnapshot(String json) {
        return json == null || json.isBlank() ? null : JSON.readValue(json, CharacterState.class);
    }

    public static String captureSnapshot(CharacterEntity row) {
        return JSON.writeValueAsString(of(row));
    }

    public static CharacterState of(CharacterEntity row) {
        return new CharacterState(row.getRoleType(), row.getPersonality(),
                row.getRealmOrLevel(), row.getCurrentLocation(), row.getStatusNotes(), row.getInventory(),
                row.getAliases(), row.getLifecycleStatus(), row.getArcScope());
    }

    public static CharacterState of(LoreDtos.CharacterResponse row) {
        return new CharacterState(row.roleType(), row.personality(), row.realmOrLevel(), row.currentLocation(),
                row.statusNotes(), row.inventory(), row.aliases(), row.lifecycleStatus(), row.arcScope());
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
    }
}
