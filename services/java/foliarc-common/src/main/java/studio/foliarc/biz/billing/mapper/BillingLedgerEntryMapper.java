package studio.foliarc.biz.billing.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import studio.foliarc.biz.billing.entity.BillingLedgerEntryEntity;

@Mapper
public interface BillingLedgerEntryMapper extends BaseMapper<BillingLedgerEntryEntity> {
}
