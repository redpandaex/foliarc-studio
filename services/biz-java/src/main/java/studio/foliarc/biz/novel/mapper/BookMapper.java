package studio.foliarc.biz.novel.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import studio.foliarc.biz.novel.entity.BookEntity;

@Mapper
public interface BookMapper extends BaseMapper<BookEntity> {
}
