package studio.foliarc.biz.ripple.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import studio.foliarc.biz.config.mybatis.PostgresJsonbTypeHandler;

@TableName(value = "ripple_jobs", autoResultMap = true)
public class RippleJobEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID bookId;
    private UUID ownerId;
    private UUID sourceSectionId;
    private int sourceChapterNo;
    private long sourceNarrativeOrder;
    private String vipPlanCode = "FREE";
    private String rangeMode = "BIDIRECTIONAL";
    private int rangeStartChapter;
    private int rangeEndChapter;
    private long rangeStartOrder;
    private long rangeEndOrder;
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String selectedVolumeIds = "[]";
    private int maxCandidatesCap = 50;
    private int maxPatchBatchLimit = 20;
    private String status = "ANALYZING";
    private String oldContentSnapshot = "";
    private String newContentSnapshot = "";
    private String changeNote = "";
    private String changeSummary = "";
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String factDeltasJson = "[]";
    private int scannedSectionCount;
    private int candidateSectionCount;
    private int backwardConflictCount;
    private int forwardConflictCount;
    private int minorMentionCount;
    private long estimatedCostMicros;
    private long frozenCostMicros;
    private long actualCostMicros;
    private int nextBatchNo = 1;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getBookId() { return bookId; }
    public void setBookId(UUID bookId) { this.bookId = bookId; }

    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }

    public UUID getSourceSectionId() { return sourceSectionId; }
    public void setSourceSectionId(UUID sourceSectionId) { this.sourceSectionId = sourceSectionId; }

    public int getSourceChapterNo() { return sourceChapterNo; }
    public void setSourceChapterNo(int sourceChapterNo) { this.sourceChapterNo = sourceChapterNo; }

    public long getSourceNarrativeOrder() { return sourceNarrativeOrder; }
    public void setSourceNarrativeOrder(long sourceNarrativeOrder) { this.sourceNarrativeOrder = sourceNarrativeOrder; }

    public String getVipPlanCode() { return vipPlanCode; }
    public void setVipPlanCode(String vipPlanCode) { this.vipPlanCode = vipPlanCode; }

    public String getRangeMode() { return rangeMode; }
    public void setRangeMode(String rangeMode) { this.rangeMode = rangeMode; }

    public int getRangeStartChapter() { return rangeStartChapter; }
    public void setRangeStartChapter(int rangeStartChapter) { this.rangeStartChapter = rangeStartChapter; }

    public int getRangeEndChapter() { return rangeEndChapter; }
    public void setRangeEndChapter(int rangeEndChapter) { this.rangeEndChapter = rangeEndChapter; }

    public long getRangeStartOrder() { return rangeStartOrder; }
    public void setRangeStartOrder(long rangeStartOrder) { this.rangeStartOrder = rangeStartOrder; }

    public long getRangeEndOrder() { return rangeEndOrder; }
    public void setRangeEndOrder(long rangeEndOrder) { this.rangeEndOrder = rangeEndOrder; }

    public String getSelectedVolumeIds() { return selectedVolumeIds; }
    public void setSelectedVolumeIds(String selectedVolumeIds) { this.selectedVolumeIds = selectedVolumeIds; }

    public int getMaxCandidatesCap() { return maxCandidatesCap; }
    public void setMaxCandidatesCap(int maxCandidatesCap) { this.maxCandidatesCap = maxCandidatesCap; }

    public int getMaxPatchBatchLimit() { return maxPatchBatchLimit; }
    public void setMaxPatchBatchLimit(int maxPatchBatchLimit) { this.maxPatchBatchLimit = maxPatchBatchLimit; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getOldContentSnapshot() { return oldContentSnapshot; }
    public void setOldContentSnapshot(String oldContentSnapshot) { this.oldContentSnapshot = oldContentSnapshot; }

    public String getNewContentSnapshot() { return newContentSnapshot; }
    public void setNewContentSnapshot(String newContentSnapshot) { this.newContentSnapshot = newContentSnapshot; }

    public String getChangeNote() { return changeNote; }
    public void setChangeNote(String changeNote) { this.changeNote = changeNote; }

    public String getChangeSummary() { return changeSummary; }
    public void setChangeSummary(String changeSummary) { this.changeSummary = changeSummary; }

    public String getFactDeltasJson() { return factDeltasJson; }
    public void setFactDeltasJson(String factDeltasJson) { this.factDeltasJson = factDeltasJson; }

    public int getScannedSectionCount() { return scannedSectionCount; }
    public void setScannedSectionCount(int scannedSectionCount) { this.scannedSectionCount = scannedSectionCount; }

    public int getCandidateSectionCount() { return candidateSectionCount; }
    public void setCandidateSectionCount(int candidateSectionCount) { this.candidateSectionCount = candidateSectionCount; }

    public int getBackwardConflictCount() { return backwardConflictCount; }
    public void setBackwardConflictCount(int backwardConflictCount) { this.backwardConflictCount = backwardConflictCount; }

    public int getForwardConflictCount() { return forwardConflictCount; }
    public void setForwardConflictCount(int forwardConflictCount) { this.forwardConflictCount = forwardConflictCount; }

    public int getMinorMentionCount() { return minorMentionCount; }
    public void setMinorMentionCount(int minorMentionCount) { this.minorMentionCount = minorMentionCount; }

    public long getEstimatedCostMicros() { return estimatedCostMicros; }
    public void setEstimatedCostMicros(long estimatedCostMicros) { this.estimatedCostMicros = estimatedCostMicros; }

    public long getFrozenCostMicros() { return frozenCostMicros; }
    public void setFrozenCostMicros(long frozenCostMicros) { this.frozenCostMicros = frozenCostMicros; }

    public long getActualCostMicros() { return actualCostMicros; }
    public void setActualCostMicros(long actualCostMicros) { this.actualCostMicros = actualCostMicros; }

    public int getNextBatchNo() { return nextBatchNo; }
    public void setNextBatchNo(int nextBatchNo) { this.nextBatchNo = nextBatchNo; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
