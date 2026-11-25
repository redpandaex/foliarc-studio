package studio.foliarc.biz.ripple.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import studio.foliarc.biz.config.mybatis.PostgresJsonbTypeHandler;

@TableName(value = "ripple_patches", autoResultMap = true)
public class RipplePatchEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID jobId;
    private UUID impactId;
    private UUID targetSectionId;
    private int batchNo = 1;
    private long baseVersion;
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String hunksJson = "[]";
    private String status = "PENDING";
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getJobId() { return jobId; }
    public void setJobId(UUID jobId) { this.jobId = jobId; }

    public UUID getImpactId() { return impactId; }
    public void setImpactId(UUID impactId) { this.impactId = impactId; }

    public UUID getTargetSectionId() { return targetSectionId; }
    public void setTargetSectionId(UUID targetSectionId) { this.targetSectionId = targetSectionId; }

    public int getBatchNo() { return batchNo; }
    public void setBatchNo(int batchNo) { this.batchNo = batchNo; }

    public long getBaseVersion() { return baseVersion; }
    public void setBaseVersion(long baseVersion) { this.baseVersion = baseVersion; }

    public String getHunksJson() { return hunksJson; }
    public void setHunksJson(String hunksJson) { this.hunksJson = hunksJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
