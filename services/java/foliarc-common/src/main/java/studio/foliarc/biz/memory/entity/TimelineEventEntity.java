package studio.foliarc.biz.memory.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("timeline_events")
public class TimelineEventEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;

    private UUID bookId;
    private UUID ownerId;
    private Integer chapterNo;
    private String eventTitle;
    private String summary;
    private String involvedCharacters;
    private String location;
    private UUID sourceSectionId;
    private Long narrativeOrder;
    private String source;
    private String knownBy;

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
    public Integer getChapterNo() { return chapterNo; }
    public void setChapterNo(Integer chapterNo) { this.chapterNo = chapterNo; }
    public String getEventTitle() { return eventTitle; }
    public void setEventTitle(String eventTitle) { this.eventTitle = eventTitle; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getInvolvedCharacters() { return involvedCharacters; }
    public void setInvolvedCharacters(String involvedCharacters) { this.involvedCharacters = involvedCharacters; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public UUID getSourceSectionId() { return sourceSectionId; }
    public void setSourceSectionId(UUID sourceSectionId) { this.sourceSectionId = sourceSectionId; }
    public Long getNarrativeOrder() { return narrativeOrder; }
    public void setNarrativeOrder(Long narrativeOrder) { this.narrativeOrder = narrativeOrder; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getKnownBy() { return knownBy; }
    public void setKnownBy(String knownBy) { this.knownBy = knownBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
