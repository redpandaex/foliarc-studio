package studio.foliarc.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.novel.mapper.ChapterMapper;
import studio.foliarc.biz.novel.service.BookService;
import studio.foliarc.biz.run.dto.RunDtos;
import studio.foliarc.biz.run.entity.AgentCheckpointEntity;
import studio.foliarc.biz.run.entity.AgentRunEntity;
import studio.foliarc.biz.run.mapper.AgentCheckpointMapper;
import studio.foliarc.biz.run.mapper.AgentRunMapper;
import studio.foliarc.biz.run.service.AgentRunService;

class MultiAgentCheckpointTests {

    @Test
    void saveCheckpointPersistsBlackboardStateAndRejectsApproveWhenNotPaused() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentCheckpointMapper checkpointMapper = mock(AgentCheckpointMapper.class);
        BookService bookService = mock(BookService.class);
        ChapterMapper chapterMapper = mock(ChapterMapper.class);
        WebClient webClient = mock(WebClient.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        AgentRunService service = new AgentRunService(
                runMapper, checkpointMapper, bookService, chapterMapper, webClient, redis
        );

        UUID ownerId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        AgentRunEntity run = new AgentRunEntity();
        run.setId(runId);
        run.setOwnerId(ownerId);
        run.setStatus("RUNNING");
        when(runMapper.selectById(runId)).thenReturn(run);

        RunDtos.CheckpointResponse saved = service.saveCheckpoint(
                runId,
                new RunDtos.InternalSaveCheckpointRequest(2, "DIRECTOR", "INTERRUPTED", "{\"outline\":\"beat1\"}")
        );

        ArgumentCaptor<AgentCheckpointEntity> captor = ArgumentCaptor.forClass(AgentCheckpointEntity.class);
        verify(checkpointMapper).insert(captor.capture());
        assertThat(captor.getValue().getNodeName()).isEqualTo("DIRECTOR");
        assertThat(captor.getValue().getStatus()).isEqualTo("INTERRUPTED");
        assertThat(saved.stepNo()).isEqualTo(2);

        assertThatThrownBy(() -> service.approveRun(ownerId, runId, new RunDtos.ApproveRunRequest(true, "edited", "")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未处于等待人工审批状态");
    }
}
