package studio.foliarc.biz.outbox.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import studio.foliarc.biz.outbox.entity.OutboxEventEntity;

@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEventEntity> {

    @Select("""
            SELECT * FROM outbox_events
            WHERE status = 'PENDING' AND next_retry_at <= NOW()
            ORDER BY created_at ASC
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<OutboxEventEntity> lockDueEvents(@Param("limit") int limit);
}
