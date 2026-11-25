package studio.foliarc.biz.memory.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("story_rollup_summaries")
public class StoryRollupSummaryEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID bookId;
    private UUID ownerId;
    private Integer tierLevel;
    private Integer seqNo;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private UUID volumeId;
    private Long startOrder;
    private Long endOrder;
    private Integer startChapterNo;
    private Integer endChapterNo;
    private String title;
    private String summaryText;
    private String openThreads;
    private String sourceHash;
    private String status;

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
    public Integer getTierLevel() { return tierLevel; }
    public void setTierLevel(Integer tierLevel) { this.tierLevel = tierLevel; }
    public Integer getSeqNo() { return seqNo; }
    public void setSeqNo(Integer seqNo) { this.seqNo = seqNo; }
    public UUID getVolumeId() { return volumeId; }
    public void setVolumeId(UUID volumeId) { this.volumeId = volumeId; }
    public Long getStartOrder() { return startOrder; }
    public void setStartOrder(Long startOrder) { this.startOrder = startOrder; }
    public Long getEndOrder() { return endOrder; }
    public void setEndOrder(Long endOrder) { this.endOrder = endOrder; }
    public Integer getStartChapterNo() { return startChapterNo; }
    public void setStartChapterNo(Integer startChapterNo) { this.startChapterNo = startChapterNo; }
    public Integer getEndChapterNo() { return endChapterNo; }
    public void setEndChapterNo(Integer endChapterNo) { this.endChapterNo = endChapterNo; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummaryText() { return summaryText; }
    public void setSummaryText(String summaryText) { this.summaryText = summaryText; }
    public String getOpenThreads() { return openThreads; }
    public void setOpenThreads(String openThreads) { this.openThreads = openThreads; }
    public String getSourceHash() { return sourceHash; }
    public void setSourceHash(String sourceHash) { this.sourceHash = sourceHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
