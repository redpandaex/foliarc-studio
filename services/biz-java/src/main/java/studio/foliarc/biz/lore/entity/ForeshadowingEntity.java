package studio.foliarc.biz.lore.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import studio.foliarc.biz.config.mybatis.PostgresJsonbTypeHandler;

@TableName(value = "foreshadowings", autoResultMap = true)
public class ForeshadowingEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;

    private UUID bookId;
    private String title;
    private String description;
    private Integer plantedChapterNo;
    private Integer resolvedChapterNo;
    private String status;
    private String code;
    private String importance;
    private String revealPolicy;
    private String surfaceClue;
    private String hiddenTruth;
    private UUID plantedSectionId;
    private Long plantedNarrativeOrder;
    private String plantedQuote;
    private UUID resolvedSectionId;
    private Integer targetChapterMin;
    private Integer targetChapterMax;
    private String involvedCharacters;
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String hintHistoryJson;
    private String source;
    private Boolean authorLocked;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getBookId() { return bookId; }
    public void setBookId(UUID bookId) { this.bookId = bookId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getPlantedChapterNo() { return plantedChapterNo; }
    public void setPlantedChapterNo(Integer plantedChapterNo) { this.plantedChapterNo = plantedChapterNo; }
    public Integer getResolvedChapterNo() { return resolvedChapterNo; }
    public void setResolvedChapterNo(Integer resolvedChapterNo) { this.resolvedChapterNo = resolvedChapterNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getImportance() { return importance; }
    public void setImportance(String importance) { this.importance = importance; }
    public String getRevealPolicy() { return revealPolicy; }
    public void setRevealPolicy(String revealPolicy) { this.revealPolicy = revealPolicy; }
    public String getSurfaceClue() { return surfaceClue; }
    public void setSurfaceClue(String surfaceClue) { this.surfaceClue = surfaceClue; }
    public String getHiddenTruth() { return hiddenTruth; }
    public void setHiddenTruth(String hiddenTruth) { this.hiddenTruth = hiddenTruth; }
    public UUID getPlantedSectionId() { return plantedSectionId; }
    public void setPlantedSectionId(UUID plantedSectionId) { this.plantedSectionId = plantedSectionId; }
    public Long getPlantedNarrativeOrder() { return plantedNarrativeOrder; }
    public void setPlantedNarrativeOrder(Long plantedNarrativeOrder) { this.plantedNarrativeOrder = plantedNarrativeOrder; }
    public String getPlantedQuote() { return plantedQuote; }
    public void setPlantedQuote(String plantedQuote) { this.plantedQuote = plantedQuote; }
    public UUID getResolvedSectionId() { return resolvedSectionId; }
    public void setResolvedSectionId(UUID resolvedSectionId) { this.resolvedSectionId = resolvedSectionId; }
    public Integer getTargetChapterMin() { return targetChapterMin; }
    public void setTargetChapterMin(Integer targetChapterMin) { this.targetChapterMin = targetChapterMin; }
    public Integer getTargetChapterMax() { return targetChapterMax; }
    public void setTargetChapterMax(Integer targetChapterMax) { this.targetChapterMax = targetChapterMax; }
    public String getInvolvedCharacters() { return involvedCharacters; }
    public void setInvolvedCharacters(String involvedCharacters) { this.involvedCharacters = involvedCharacters; }
    public String getHintHistoryJson() { return hintHistoryJson; }
    public void setHintHistoryJson(String hintHistoryJson) { this.hintHistoryJson = hintHistoryJson; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Boolean getAuthorLocked() { return authorLocked; }
    public void setAuthorLocked(Boolean authorLocked) { this.authorLocked = authorLocked; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
