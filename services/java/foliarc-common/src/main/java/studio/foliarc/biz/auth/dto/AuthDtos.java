package studio.foliarc.biz.auth.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank(message = "邮箱不能为空")
            @Email(message = "邮箱格式不正确")
            @Size(max = 255)
            String email,

            @NotBlank(message = "用户名不能为空")
            @Size(min = 2, max = 64, message = "用户名长度需在 2-64 个字符之间")
            String username,

            @NotBlank(message = "密码不能为空")
            @Size(min = 8, max = 128, message = "密码长度至少 8 位")
            String password
    ) {
    }

    public record LoginRequest(
            @NotBlank(message = "邮箱不能为空")
            @Email(message = "邮箱格式不正确")
            String email,

            @NotBlank(message = "密码不能为空")
            String password
    ) {
    }

    public record UserProfile(
            UUID id,
            String email,
            String username,
            String role,
            OffsetDateTime createdAt
    ) {
    }

    public record AuthResponse(
            String accessToken,
            long expiresIn,
            UserProfile user
    ) {
    }

    public record AuthResult(
            AuthResponse response,
            String refreshToken
    ) {
    }
}
