package studio.foliarc.biz.vip.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import studio.foliarc.biz.config.mybatis.PostgresJsonbTypeHandler;

@TableName(value = "vip_plans", autoResultMap = true)
public class VipPlanEntity {

    @TableId(type = IdType.INPUT)
    private String planCode;

    private String displayName;
    private Integer monthlyPriceCents;
    private Long monthlyGrantMicros;
    private Integer creditDiscountBps;

    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String entitlementsJson;

    private Boolean enabled;
    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    public String getPlanCode() { return planCode; }
    public void setPlanCode(String planCode) { this.planCode = planCode; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public Integer getMonthlyPriceCents() { return monthlyPriceCents; }
    public void setMonthlyPriceCents(Integer monthlyPriceCents) { this.monthlyPriceCents = monthlyPriceCents; }
    public Long getMonthlyGrantMicros() { return monthlyGrantMicros; }
    public void setMonthlyGrantMicros(Long monthlyGrantMicros) { this.monthlyGrantMicros = monthlyGrantMicros; }
    public Integer getCreditDiscountBps() { return creditDiscountBps; }
    public void setCreditDiscountBps(Integer creditDiscountBps) { this.creditDiscountBps = creditDiscountBps; }
    public String getEntitlementsJson() { return entitlementsJson; }
    public void setEntitlementsJson(String entitlementsJson) { this.entitlementsJson = entitlementsJson; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
