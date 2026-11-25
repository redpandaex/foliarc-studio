package studio.foliarc.biz.rag.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("knowledge_documents")
public class KnowledgeDocumentEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;

    private UUID bookId;
    private UUID ownerId;
    private String scope;
    private UUID uploadedBy;
    private String genreTags;
    private String contentSha256;
    private Boolean isReadonly;
    private Boolean enabled;
    private String sourceType;
    private UUID sourceChapterId;
    private Integer chapterNo;
    private String docType;
    private String title;
    private String fileName;
    private String contentType;
    private Long fileSize;
    private String minioBucket;
    private String minioObjectKey;
    private Integer docVersion;
    private String status;
    private Integer progress;
    private Integer chunkCount;
    private String errorMessage;
    private OffsetDateTime deletedAt;

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
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public UUID getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(UUID uploadedBy) { this.uploadedBy = uploadedBy; }
    public String getGenreTags() { return genreTags; }
    public void setGenreTags(String genreTags) { this.genreTags = genreTags; }
    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }
    public Boolean getIsReadonly() { return isReadonly; }
    public void setIsReadonly(Boolean isReadonly) { this.isReadonly = isReadonly; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public UUID getSourceChapterId() { return sourceChapterId; }
    public void setSourceChapterId(UUID sourceChapterId) { this.sourceChapterId = sourceChapterId; }
    public Integer getChapterNo() { return chapterNo; }
    public void setChapterNo(Integer chapterNo) { this.chapterNo = chapterNo; }
    public String getDocType() { return docType; }
    public void setDocType(String docType) { this.docType = docType; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getMinioBucket() { return minioBucket; }
    public void setMinioBucket(String minioBucket) { this.minioBucket = minioBucket; }
    public String getMinioObjectKey() { return minioObjectKey; }
    public void setMinioObjectKey(String minioObjectKey) { this.minioObjectKey = minioObjectKey; }
    public Integer getDocVersion() { return docVersion; }
    public void setDocVersion(Integer docVersion) { this.docVersion = docVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime deletedAt) { this.deletedAt = deletedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
