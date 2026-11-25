package studio.foliarc.biz.audit.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import studio.foliarc.biz.audit.entity.AdminAuditLogEntity;

@Mapper
public interface AdminAuditLogMapper extends BaseMapper<AdminAuditLogEntity> {
}
