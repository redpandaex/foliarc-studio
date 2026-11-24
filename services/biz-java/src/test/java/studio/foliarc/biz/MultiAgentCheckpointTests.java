package studio.foliarc.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import studio.foliarc.biz.billing.service.BillingService;
import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.gateway.RedisRateLimiterService;
import studio.foliarc.biz.novel.entity.ChapterEntity;
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
        RedisRateLimiterService rateLimiter = mock(RedisRateLimiterService.class);
        BillingService billingService = mock(BillingService.class);

        AgentRunService service = new AgentRunService(
                runMapper, checkpointMapper, bookService, chapterMapper, webClient, redis,
                rateLimiter, billingService, CircuitBreaker.ofDefaults("test"), Bulkhead.ofDefaults("test")
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

    @Test
    void buildPreviousChaptersContextIncludesImmediatePreviousChapterAndEarlierRecap() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentCheckpointMapper checkpointMapper = mock(AgentCheckpointMapper.class);
        BookService bookService = mock(BookService.class);
        ChapterMapper chapterMapper = mock(ChapterMapper.class);
        WebClient webClient = mock(WebClient.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisRateLimiterService rateLimiter = mock(RedisRateLimiterService.class);
        BillingService billingService = mock(BillingService.class);

        AgentRunService service = new AgentRunService(
                runMapper, checkpointMapper, bookService, chapterMapper, webClient, redis,
                rateLimiter, billingService, CircuitBreaker.ofDefaults("test2"), Bulkhead.ofDefaults("test2")
        );

        UUID bookId = UUID.randomUUID();
        ChapterEntity ch1 = new ChapterEntity();
        ch1.setBookId(bookId);
        ch1.setChapterNo(1);
        ch1.setTitle("雨夜断剑");
        ch1.setStatus("DRAFT");
        ch1.setContent("林青在寒潭边捡起残缺青铜令，远处传来急促马蹄声。");

        ChapterEntity ch2 = new ChapterEntity();
        ch2.setBookId(bookId);
        ch2.setChapterNo(2);
        ch2.setTitle("追兵将至");
        ch2.setStatus("DRAFT");
        ch2.setContent("黑衣首领冷笑一声，挥刀斩向寒潭石桥，林青纵身跃入迷雾。");

        ChapterEntity ch3 = new ChapterEntity();
        ch3.setBookId(bookId);
        ch3.setChapterNo(3);
        ch3.setTitle("迷雾古寺");

        when(chapterMapper.selectList(any())).thenReturn(List.of(ch1, ch2));

        String ctx = service.buildPreviousChaptersContext(bookId, ch3);
        assertThat(ctx).contains("更早章节前情提要");
        assertThat(ctx).contains("第 1 章《雨夜断剑》");
        assertThat(ctx).contains("残缺青铜令");
        assertThat(ctx).contains("上一章（第 2 章《追兵将至》）正文衔接");
        assertThat(ctx).contains("纵身跃入迷雾");
    }
}
