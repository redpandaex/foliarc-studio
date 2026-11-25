package studio.foliarc.biz.ripple.mapper;

import java.util.UUID;

import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import studio.foliarc.biz.ripple.entity.RippleJobEntity;

@Mapper
public interface RippleJobMapper extends BaseMapper<RippleJobEntity> {

    @Select("SELECT id FROM users WHERE id = #{ownerId} FOR NO KEY UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    UUID lockOwnerForScanQuota(@Param("ownerId") UUID ownerId);
}
