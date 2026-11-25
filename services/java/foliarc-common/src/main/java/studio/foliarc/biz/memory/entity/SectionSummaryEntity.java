package studio.foliarc.biz.memory.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("section_summaries")
public class SectionSummaryEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID bookId;
    private UUID ownerId;
    private UUID chapterId;
    private UUID sectionId;
    private Integer chapterNo;
    private Integer sectionNo;
    private Long narrativeOrder;
    private String chapterTitle;
    private String sectionTitle;
    private String contentHash;
    private String timeAndPlace;
    private String summaryText;
    private String stateChanges;
    private String clueSignals;
    private String povNotes;
    private String involvedCharacters;
    private String endingHook;
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
    public UUID getChapterId() { return chapterId; }
    public void setChapterId(UUID chapterId) { this.chapterId = chapterId; }
    public UUID getSectionId() { return sectionId; }
    public void setSectionId(UUID sectionId) { this.sectionId = sectionId; }
    public Integer getChapterNo() { return chapterNo; }
    public void setChapterNo(Integer chapterNo) { this.chapterNo = chapterNo; }
    public Integer getSectionNo() { return sectionNo; }
    public void setSectionNo(Integer sectionNo) { this.sectionNo = sectionNo; }
    public Long getNarrativeOrder() { return narrativeOrder; }
    public void setNarrativeOrder(Long narrativeOrder) { this.narrativeOrder = narrativeOrder; }
    public String getChapterTitle() { return chapterTitle; }
    public void setChapterTitle(String chapterTitle) { this.chapterTitle = chapterTitle; }
    public String getSectionTitle() { return sectionTitle; }
    public void setSectionTitle(String sectionTitle) { this.sectionTitle = sectionTitle; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getTimeAndPlace() { return timeAndPlace; }
    public void setTimeAndPlace(String timeAndPlace) { this.timeAndPlace = timeAndPlace; }
    public String getSummaryText() { return summaryText; }
    public void setSummaryText(String summaryText) { this.summaryText = summaryText; }
    public String getStateChanges() { return stateChanges; }
    public void setStateChanges(String stateChanges) { this.stateChanges = stateChanges; }
    public String getClueSignals() { return clueSignals; }
    public void setClueSignals(String clueSignals) { this.clueSignals = clueSignals; }
    public String getPovNotes() { return povNotes; }
    public void setPovNotes(String povNotes) { this.povNotes = povNotes; }
    public String getInvolvedCharacters() { return involvedCharacters; }
    public void setInvolvedCharacters(String involvedCharacters) { this.involvedCharacters = involvedCharacters; }
    public String getEndingHook() { return endingHook; }
    public void setEndingHook(String endingHook) { this.endingHook = endingHook; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
