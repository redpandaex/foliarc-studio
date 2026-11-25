package studio.foliarc.biz.billing.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

@TableName("billing_accounts")
public class BillingAccountEntity {

    @TableId(type = IdType.INPUT)
    private UUID userId;

    private Long balanceMicros;
    private Long frozenMicros;
    private Long monthlyBudgetMicros;
    private Long monthlySpentMicros;
    private String budgetMonth;

    @Version
    private Long version;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public Long getBalanceMicros() { return balanceMicros; }
    public void setBalanceMicros(Long balanceMicros) { this.balanceMicros = balanceMicros; }
    public Long getFrozenMicros() { return frozenMicros; }
    public void setFrozenMicros(Long frozenMicros) { this.frozenMicros = frozenMicros; }
    public Long getMonthlyBudgetMicros() { return monthlyBudgetMicros; }
    public void setMonthlyBudgetMicros(Long monthlyBudgetMicros) { this.monthlyBudgetMicros = monthlyBudgetMicros; }
    public Long getMonthlySpentMicros() { return monthlySpentMicros; }
    public void setMonthlySpentMicros(Long monthlySpentMicros) { this.monthlySpentMicros = monthlySpentMicros; }
    public String getBudgetMonth() { return budgetMonth; }
    public void setBudgetMonth(String budgetMonth) { this.budgetMonth = budgetMonth; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
