package studio.foliarc.biz.billing.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("billing_ledger_entries")
public class BillingLedgerEntryEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;

    private UUID userId;
    private UUID runId;
    private String requestId;
    private String entryType;
    private Long amountMicros;
    private Long balanceAfterMicros;
    private Long frozenAfterMicros;
    private String modelAlias;
    private Integer inputTokens;
    private Integer cachedTokens;
    private Integer outputTokens;
    private Long cacheSavedMicros;
    private Integer sandboxCalls;
    private String description;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getEntryType() { return entryType; }
    public void setEntryType(String entryType) { this.entryType = entryType; }
    public Long getAmountMicros() { return amountMicros; }
    public void setAmountMicros(Long amountMicros) { this.amountMicros = amountMicros; }
    public Long getBalanceAfterMicros() { return balanceAfterMicros; }
    public void setBalanceAfterMicros(Long balanceAfterMicros) { this.balanceAfterMicros = balanceAfterMicros; }
    public Long getFrozenAfterMicros() { return frozenAfterMicros; }
    public void setFrozenAfterMicros(Long frozenAfterMicros) { this.frozenAfterMicros = frozenAfterMicros; }
    public String getModelAlias() { return modelAlias; }
    public void setModelAlias(String modelAlias) { this.modelAlias = modelAlias; }
    public Integer getInputTokens() { return inputTokens; }
    public void setInputTokens(Integer inputTokens) { this.inputTokens = inputTokens; }
    public Integer getCachedTokens() { return cachedTokens; }
    public void setCachedTokens(Integer cachedTokens) { this.cachedTokens = cachedTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Integer outputTokens) { this.outputTokens = outputTokens; }
    public Long getCacheSavedMicros() { return cacheSavedMicros; }
    public void setCacheSavedMicros(Long cacheSavedMicros) { this.cacheSavedMicros = cacheSavedMicros; }
    public Integer getSandboxCalls() { return sandboxCalls; }
    public void setSandboxCalls(Integer sandboxCalls) { this.sandboxCalls = sandboxCalls; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
