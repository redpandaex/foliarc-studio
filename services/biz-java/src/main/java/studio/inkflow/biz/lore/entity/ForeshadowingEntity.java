package studio.inkflow.biz.lore.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("foreshadowings")
public class ForeshadowingEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;

    private UUID bookId;
    private String title;
    private String description;
    private Integer plantedChapterNo;
    private Integer resolvedChapterNo;
    private String status;

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
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
