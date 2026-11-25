package studio.foliarc.biz.audit.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import studio.foliarc.biz.config.mybatis.PostgresJsonbTypeHandler;

@TableName(value = "admin_audit_logs", autoResultMap = true)
public class AdminAuditLogEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID operatorId;
    private String operatorName;
    private String action;
    private String targetType;
    private String targetId;
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String beforeJson;
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String afterJson;
    private String clientIp;
    private String traceId;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getOperatorId() { return operatorId; }
    public void setOperatorId(UUID operatorId) { this.operatorId = operatorId; }

    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }

    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }

    public String getBeforeJson() { return beforeJson; }
    public void setBeforeJson(String beforeJson) { this.beforeJson = beforeJson; }

    public String getAfterJson() { return afterJson; }
    public void setAfterJson(String afterJson) { this.afterJson = afterJson; }

    public String getClientIp() { return clientIp; }
    public void setClientIp(String clientIp) { this.clientIp = clientIp; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
