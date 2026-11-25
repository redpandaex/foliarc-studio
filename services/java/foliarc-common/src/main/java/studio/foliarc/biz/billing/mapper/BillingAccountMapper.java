package studio.foliarc.biz.billing.mapper;

import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import studio.foliarc.biz.billing.entity.BillingAccountEntity;

@Mapper
public interface BillingAccountMapper extends BaseMapper<BillingAccountEntity> {

    @Insert("""
            INSERT INTO billing_accounts (user_id, balance_micros, monthly_budget_micros, budget_month)
            VALUES (#{userId}, #{balanceMicros}, #{monthlyBudgetMicros}, #{budgetMonth})
            ON CONFLICT (user_id) DO NOTHING
            """)
    int insertIfAbsent(BillingAccountEntity account);

    @Select("SELECT * FROM billing_accounts WHERE user_id = #{userId} FOR NO KEY UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    BillingAccountEntity selectForUpdate(@Param("userId") UUID userId);
}
