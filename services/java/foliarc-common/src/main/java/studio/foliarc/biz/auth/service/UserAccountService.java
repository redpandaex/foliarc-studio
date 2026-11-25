package studio.foliarc.biz.auth.service;

import java.util.Locale;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.auth.dto.AuthDtos.LoginRequest;
import studio.foliarc.biz.auth.dto.AuthDtos.UserProfile;
import studio.foliarc.biz.auth.entity.UserEntity;
import studio.foliarc.biz.auth.mapper.UserMapper;
import studio.foliarc.biz.common.BizException;

@Service
public class UserAccountService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserAccountService(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    public UserEntity authenticate(LoginRequest request) {
        UserEntity user = userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getEmail, normalizeEmail(request.email())));
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw BizException.unauthorized("INVALID_CREDENTIALS", "邮箱或密码错误");
        }
        return user;
    }

    public UserEntity requireUser(UUID userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("USER_NOT_FOUND", "用户不存在");
        }
        return user;
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public static AuthenticatedUser toPrincipal(UserEntity user) {
        return new AuthenticatedUser(user.getId(), user.getEmail(), user.getUsername(), user.getRole());
    }

    public static UserProfile toProfile(UserEntity user) {
        return new UserProfile(user.getId(), user.getEmail(), user.getUsername(), user.getRole(), user.getCreatedAt());
    }
}
