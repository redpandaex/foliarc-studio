package studio.foliarc.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataAccessResourceFailureException;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import studio.foliarc.biz.auth.entity.UserEntity;
import studio.foliarc.biz.auth.mapper.UserMapper;

class AdminBootstrapRunnerTests {

    private final UserMapper users = mock(UserMapper.class);
    private final DefaultApplicationArguments args = new DefaultApplicationArguments();

    @BeforeAll
    static void initializeQueriesForMockedMapper() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "admin-bootstrap");
        TableInfoHelper.initTableInfo(assistant, UserEntity.class);
    }

    @Test
    void promotesTheNormalizedEmailOnlyOnce() {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setRole("AUTHOR");
        when(users.selectOne(any())).thenAnswer(call -> {
            LambdaQueryWrapper<?> query = call.getArgument(0);
            assertThat(query.getSqlSegment()).contains("email =");
            assertThat(query.getParamNameValuePairs().values()).containsExactly("admin@example.test");
            return user;
        });
        when(users.update(isNull(), any())).thenAnswer(call -> {
            LambdaUpdateWrapper<?> update = call.getArgument(1);
            assertThat(update.getSqlSet()).contains("role=");
            assertThat(update.getSqlSegment()).contains("id =", "role <>");
            assertThat(update.getParamNameValuePairs().values()).contains(user.getId(), "ADMIN");
            user.setRole("ADMIN");
            return 1;
        });

        var runner = new AdminBootstrapRunner(users, " ADMIN@Example.TEST ");
        runner.run(args);
        runner.run(args);

        assertThat(user.getRole()).isEqualTo("ADMIN");
        verify(users).update(isNull(), any());
    }

    @Test
    void emptyConfigurationSkipsDatabaseAccess() {
        new AdminBootstrapRunner(users, " ").run(args);

        verifyNoInteractions(users);
    }

    @Test
    void databaseUnavailabilityDoesNotBlockStartup() {
        when(users.selectOne(any())).thenThrow(new DataAccessResourceFailureException("test database unavailable"));

        assertThatCode(() -> new AdminBootstrapRunner(users, "admin@example.test").run(args))
                .doesNotThrowAnyException();
    }
}
