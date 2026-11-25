package studio.foliarc.biz.novel.mapper;

import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import studio.foliarc.biz.novel.entity.BookEntity;

@Mapper
public interface BookMapper extends BaseMapper<BookEntity> {

    @Select("SELECT id FROM books WHERE id = #{bookId} FOR NO KEY UPDATE")
    UUID lockForStructure(@Param("bookId") UUID bookId);
}
