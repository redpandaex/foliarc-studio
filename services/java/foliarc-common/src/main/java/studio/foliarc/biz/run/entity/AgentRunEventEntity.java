package studio.foliarc.biz.run.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import studio.foliarc.biz.config.mybatis.PostgresJsonbTypeHandler;

@TableName(value = "agent_run_events", autoResultMap = true)
public class AgentRunEventEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID runId;
    private int seq;
    private String eventType;
    private String agentName;
    private String traceId;
    private int latencyMs;
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String payloadJson;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }

    public int getSeq() { return seq; }
    public void setSeq(int seq) { this.seq = seq; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public int getLatencyMs() { return latencyMs; }
    public void setLatencyMs(int latencyMs) { this.latencyMs = latencyMs; }

    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
