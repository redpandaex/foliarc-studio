package studio.foliarc.biz.billing.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("guardrail_audit_logs")
public class GuardrailAuditLogEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;

    private UUID userId;
    private UUID bookId;
    private UUID runId;
    private String stage;
    private String action;
    private String ruleCode;
    private String detail;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getBookId() { return bookId; }
    public void setBookId(UUID bookId) { this.bookId = bookId; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getRuleCode() { return ruleCode; }
    public void setRuleCode(String ruleCode) { this.ruleCode = ruleCode; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
