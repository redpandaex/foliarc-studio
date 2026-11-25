package studio.foliarc.biz.lore.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("prompt_templates")
public class PromptTemplateEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;

    private String name;
    private Integer version;
    private String stage;
    private String templateBody;
    private String description;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getTemplateBody() { return templateBody; }
    public void setTemplateBody(String templateBody) { this.templateBody = templateBody; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
