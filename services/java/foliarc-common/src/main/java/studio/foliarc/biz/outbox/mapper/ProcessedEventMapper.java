package studio.foliarc.biz.outbox.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import studio.foliarc.biz.outbox.entity.ProcessedEventEntity;

@Mapper
public interface ProcessedEventMapper extends BaseMapper<ProcessedEventEntity> {

    @Insert("""
            INSERT INTO processed_events (idempotency_key, consumer_name, processed_at)
            VALUES (#{idempotencyKey}, #{consumerName}, NOW())
            ON CONFLICT (idempotency_key) DO NOTHING
            """)
    int tryClaim(@Param("idempotencyKey") String idempotencyKey, @Param("consumerName") String consumerName);
}
