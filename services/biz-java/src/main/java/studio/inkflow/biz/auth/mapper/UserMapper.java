package studio.inkflow.biz.auth.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import studio.inkflow.biz.auth.entity.UserEntity;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
