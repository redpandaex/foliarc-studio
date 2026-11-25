package studio.foliarc.biz.memory.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import studio.foliarc.biz.config.mybatis.PostgresJsonbTypeHandler;

@TableName(value = "memory_proposals", autoResultMap = true)
public class MemoryProposalEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID bookId;
    private UUID ownerId;
    private UUID sectionId;
    private String proposalType;
    private UUID targetEntityId;
    private String targetName;
    private String summary;
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String diffJson;
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    private OffsetDateTime resolvedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getBookId() { return bookId; }
    public void setBookId(UUID bookId) { this.bookId = bookId; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
    public UUID getSectionId() { return sectionId; }
    public void setSectionId(UUID sectionId) { this.sectionId = sectionId; }
    public String getProposalType() { return proposalType; }
    public void setProposalType(String proposalType) { this.proposalType = proposalType; }
    public UUID getTargetEntityId() { return targetEntityId; }
    public void setTargetEntityId(UUID targetEntityId) { this.targetEntityId = targetEntityId; }
    public String getTargetName() { return targetName; }
    public void setTargetName(String targetName) { this.targetName = targetName; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getDiffJson() { return diffJson; }
    public void setDiffJson(String diffJson) { this.diffJson = diffJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(OffsetDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
}
