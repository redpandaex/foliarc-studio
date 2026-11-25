package studio.foliarc.biz.novel.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("volumes")
public class VolumeEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID bookId;
    private UUID ownerId;
    private Integer volumeNo;
    private String title;
    private String arcGoal;
    private String pacingNotes;
    private String realmScope;
    private Integer startChapterNo;
    private Integer endChapterNo;
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
    public Integer getVolumeNo() { return volumeNo; }
    public void setVolumeNo(Integer volumeNo) { this.volumeNo = volumeNo; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getArcGoal() { return arcGoal; }
    public void setArcGoal(String arcGoal) { this.arcGoal = arcGoal; }
    public String getPacingNotes() { return pacingNotes; }
    public void setPacingNotes(String pacingNotes) { this.pacingNotes = pacingNotes; }
    public String getRealmScope() { return realmScope; }
    public void setRealmScope(String realmScope) { this.realmScope = realmScope; }
    public Integer getStartChapterNo() { return startChapterNo; }
    public void setStartChapterNo(Integer startChapterNo) { this.startChapterNo = startChapterNo; }
    public Integer getEndChapterNo() { return endChapterNo; }
    public void setEndChapterNo(Integer endChapterNo) { this.endChapterNo = endChapterNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
