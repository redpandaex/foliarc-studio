package studio.foliarc.biz.auth.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import studio.foliarc.biz.auth.entity.UserEntity;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
