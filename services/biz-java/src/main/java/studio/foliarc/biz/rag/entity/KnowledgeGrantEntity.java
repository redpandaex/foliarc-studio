package studio.foliarc.biz.rag.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("knowledge_grants")
public class KnowledgeGrantEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;

    private UUID documentId;
    private UUID granteeUserId;
    private UUID grantedBy;
    private String permission;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }
    public UUID getGranteeUserId() { return granteeUserId; }
    public void setGranteeUserId(UUID granteeUserId) { this.granteeUserId = granteeUserId; }
    public UUID getGrantedBy() { return grantedBy; }
    public void setGrantedBy(UUID grantedBy) { this.grantedBy = grantedBy; }
    public String getPermission() { return permission; }
    public void setPermission(String permission) { this.permission = permission; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
