package studio.inkflow.biz.novel.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import studio.inkflow.biz.novel.entity.BookEntity;

@Mapper
public interface BookMapper extends BaseMapper<BookEntity> {
}
