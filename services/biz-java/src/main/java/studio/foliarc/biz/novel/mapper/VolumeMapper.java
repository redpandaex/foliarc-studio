package studio.foliarc.biz.novel.mapper;

import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import studio.foliarc.biz.novel.entity.VolumeEntity;

@Mapper
public interface VolumeMapper extends BaseMapper<VolumeEntity> {

    @Select("SELECT COALESCE(MAX(volume_no), 0) FROM volumes WHERE book_id = #{bookId}")
    int findMaxVolumeNo(@Param("bookId") UUID bookId);
}
