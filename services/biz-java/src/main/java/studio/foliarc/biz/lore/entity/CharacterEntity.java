package studio.foliarc.biz.lore.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import studio.foliarc.biz.config.mybatis.PostgresJsonbTypeHandler;

@TableName(value = "characters", autoResultMap = true)
public class CharacterEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;

    private UUID bookId;
    private String name;
    private String roleType;
    private String personality;
    private String realmOrLevel;
    private String currentLocation;
    private String statusNotes;
    private String inventory;
    private String aliases;
    private String lifecycleStatus;
    private Long lastSeenOrder;
    private String arcScope;
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String lockedFields;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getBookId() { return bookId; }
    public void setBookId(UUID bookId) { this.bookId = bookId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRoleType() { return roleType; }
    public void setRoleType(String roleType) { this.roleType = roleType; }
    public String getPersonality() { return personality; }
    public void setPersonality(String personality) { this.personality = personality; }
    public String getRealmOrLevel() { return realmOrLevel; }
    public void setRealmOrLevel(String realmOrLevel) { this.realmOrLevel = realmOrLevel; }
    public String getCurrentLocation() { return currentLocation; }
    public void setCurrentLocation(String currentLocation) { this.currentLocation = currentLocation; }
    public String getStatusNotes() { return statusNotes; }
    public void setStatusNotes(String statusNotes) { this.statusNotes = statusNotes; }
    public String getInventory() { return inventory; }
    public void setInventory(String inventory) { this.inventory = inventory; }
    public String getAliases() { return aliases; }
    public void setAliases(String aliases) { this.aliases = aliases; }
    public String getLifecycleStatus() { return lifecycleStatus; }
    public void setLifecycleStatus(String lifecycleStatus) { this.lifecycleStatus = lifecycleStatus; }
    public Long getLastSeenOrder() { return lastSeenOrder; }
    public void setLastSeenOrder(Long lastSeenOrder) { this.lastSeenOrder = lastSeenOrder; }
    public String getArcScope() { return arcScope; }
    public void setArcScope(String arcScope) { this.arcScope = arcScope; }
    public String getLockedFields() { return lockedFields; }
    public void setLockedFields(String lockedFields) { this.lockedFields = lockedFields; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
