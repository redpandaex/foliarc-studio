package studio.foliarc.admin.auth;

import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import studio.foliarc.biz.auth.entity.UserEntity;
import studio.foliarc.biz.auth.mapper.UserMapper;
import studio.foliarc.biz.auth.service.UserAccountService;

@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final UserMapper userMapper;
    private final String bootstrapEmail;

    public AdminBootstrapRunner(UserMapper userMapper, @Value("${foliarc.admin.bootstrap-email:}") String bootstrapEmail) {
        this.userMapper = userMapper;
        this.bootstrapEmail = bootstrapEmail;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (bootstrapEmail.isBlank()) {
            return;
        }

        try {
            UserEntity user = userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery()
                    .eq(UserEntity::getEmail, UserAccountService.normalizeEmail(bootstrapEmail)));
            if (user == null || "ADMIN".equals(user.getRole())) {
                return;
            }

            int changed = userMapper.update(null, Wrappers.<UserEntity>lambdaUpdate()
                    .eq(UserEntity::getId, user.getId())
                    .ne(UserEntity::getRole, "ADMIN")
                    .set(UserEntity::getRole, "ADMIN")
                    .set(UserEntity::getUpdatedAt, OffsetDateTime.now()));
            if (changed > 0) {
                log.info("Promoted bootstrap user {} to ADMIN", user.getId());
            }
        } catch (DataAccessException ex) {
            log.warn("Admin bootstrap could not access the user database ({})", ex.getClass().getSimpleName());
        }
    }
}
