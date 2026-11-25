package studio.foliarc.biz.lore.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import studio.foliarc.biz.lore.entity.PromptTemplateEntity;

@Mapper
public interface PromptTemplateMapper extends BaseMapper<PromptTemplateEntity> {

    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('foliarc:prompt:' || #{name}, 0))")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    int lockPromptName(@Param("name") String name);
}
