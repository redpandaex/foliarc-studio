package studio.foliarc.biz.run.mapper;

import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import studio.foliarc.biz.run.entity.AgentRunEntity;

@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRunEntity> {

    @Select("SELECT id FROM agent_runs WHERE id = #{runId} FOR KEY SHARE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    UUID lockForEventInsert(@Param("runId") UUID runId);
}
