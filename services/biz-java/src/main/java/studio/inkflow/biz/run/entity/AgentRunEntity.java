package studio.inkflow.biz.run.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("agent_runs")
public class AgentRunEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;

    private UUID bookId;
    private UUID chapterId;
    private UUID ownerId;
    private String mode;
    private String action;
    private String modelAlias;
    private String instruction;
    private String chapterContext;
    private String status;
    private String accumulatedText;
    private Integer lastSeq;
    private String lastEventId;
    private Integer inputTokens;
    private Integer cachedTokens;
    private Integer outputTokens;
    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getBookId() { return bookId; }
    public void setBookId(UUID bookId) { this.bookId = bookId; }
    public UUID getChapterId() { return chapterId; }
    public void setChapterId(UUID chapterId) { this.chapterId = chapterId; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getModelAlias() { return modelAlias; }
    public void setModelAlias(String modelAlias) { this.modelAlias = modelAlias; }
    public String getInstruction() { return instruction; }
    public void setInstruction(String instruction) { this.instruction = instruction; }
    public String getChapterContext() { return chapterContext; }
    public void setChapterContext(String chapterContext) { this.chapterContext = chapterContext; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAccumulatedText() { return accumulatedText; }
    public void setAccumulatedText(String accumulatedText) { this.accumulatedText = accumulatedText; }
    public Integer getLastSeq() { return lastSeq; }
    public void setLastSeq(Integer lastSeq) { this.lastSeq = lastSeq; }
    public String getLastEventId() { return lastEventId; }
    public void setLastEventId(String lastEventId) { this.lastEventId = lastEventId; }
    public Integer getInputTokens() { return inputTokens; }
    public void setInputTokens(Integer inputTokens) { this.inputTokens = inputTokens; }
    public Integer getCachedTokens() { return cachedTokens; }
    public void setCachedTokens(Integer cachedTokens) { this.cachedTokens = cachedTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Integer outputTokens) { this.outputTokens = outputTokens; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
