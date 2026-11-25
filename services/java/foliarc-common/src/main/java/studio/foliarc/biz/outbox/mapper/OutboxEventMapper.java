package studio.foliarc.biz.outbox.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import studio.foliarc.biz.outbox.entity.OutboxEventEntity;

@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEventEntity> {

    @Override
    @Insert("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, routing_key,
                                       payload_json, idempotency_key, status, retry_count, next_retry_at)
            VALUES (#{id}, #{aggregateType}, #{aggregateId}, #{eventType}, #{routingKey},
                    #{payloadJson}, #{idempotencyKey}, #{status}, #{retryCount}, #{nextRetryAt})
            ON CONFLICT (idempotency_key) DO NOTHING
            """)
    int insert(OutboxEventEntity event);

    @Select("""
            SELECT * FROM outbox_events
            WHERE status = 'PENDING' AND next_retry_at <= NOW()
            ORDER BY created_at ASC
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<OutboxEventEntity> lockDueEvents(@Param("limit") int limit);
}
