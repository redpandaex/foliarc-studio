package studio.foliarc.biz.memory.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import studio.foliarc.biz.config.mybatis.PostgresJsonbTypeHandler;

@TableName(value = "character_state_snapshots", autoResultMap = true)
public class CharacterStateSnapshotEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID bookId;
    private UUID characterId;
    private Long narrativeOrder;
    private UUID sectionId;
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String snapshotJson;
    private String source;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getBookId() { return bookId; }
    public void setBookId(UUID bookId) { this.bookId = bookId; }
    public UUID getCharacterId() { return characterId; }
    public void setCharacterId(UUID characterId) { this.characterId = characterId; }
    public Long getNarrativeOrder() { return narrativeOrder; }
    public void setNarrativeOrder(Long narrativeOrder) { this.narrativeOrder = narrativeOrder; }
    public UUID getSectionId() { return sectionId; }
    public void setSectionId(UUID sectionId) { this.sectionId = sectionId; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
