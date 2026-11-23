package studio.inkflow.biz.lore.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("characters")
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
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
