package studio.foliarc.biz.ripple.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("ripple_impacts")
public class RippleImpactEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID jobId;
    private UUID targetSectionId;
    private int chapterNo;
    private int sectionNo;
    private long narrativeOrder;
    private String chapterTitle = "";
    private String direction;
    private String severity;
    private String conflictReason = "";
    private String matchedExcerpt = "";
    private String patchStatus = "UNSELECTED";
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getJobId() { return jobId; }
    public void setJobId(UUID jobId) { this.jobId = jobId; }

    public UUID getTargetSectionId() { return targetSectionId; }
    public void setTargetSectionId(UUID targetSectionId) { this.targetSectionId = targetSectionId; }

    public int getChapterNo() { return chapterNo; }
    public void setChapterNo(int chapterNo) { this.chapterNo = chapterNo; }

    public int getSectionNo() { return sectionNo; }
    public void setSectionNo(int sectionNo) { this.sectionNo = sectionNo; }

    public long getNarrativeOrder() { return narrativeOrder; }
    public void setNarrativeOrder(long narrativeOrder) { this.narrativeOrder = narrativeOrder; }

    public String getChapterTitle() { return chapterTitle; }
    public void setChapterTitle(String chapterTitle) { this.chapterTitle = chapterTitle; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getConflictReason() { return conflictReason; }
    public void setConflictReason(String conflictReason) { this.conflictReason = conflictReason; }

    public String getMatchedExcerpt() { return matchedExcerpt; }
    public void setMatchedExcerpt(String matchedExcerpt) { this.matchedExcerpt = matchedExcerpt; }

    public String getPatchStatus() { return patchStatus; }
    public void setPatchStatus(String patchStatus) { this.patchStatus = patchStatus; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
