package studio.foliarc.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.auth.JwtTokenService;
import studio.foliarc.biz.run.entity.AgentRunEventEntity;
import studio.foliarc.biz.run.mapper.AgentRunEventMapper;
import studio.foliarc.biz.run.mapper.AgentRunMapper;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "foliarc.outbox.enabled=false",
        "foliarc.agent.internal-token=phase6-internal-test"
})
@AutoConfigureMockMvc
class Phase6BizSeparationTests {

    private static final String INTERNAL_TOKEN = "phase6-internal-test";
    private static final AuthenticatedUser ADMIN =
            new AuthenticatedUser(UUID.randomUUID(), "admin@example.test", "admin", "ADMIN");

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private ObjectMapper json;
    @Autowired private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @MockitoBean private AgentRunMapper runMapper;
    @MockitoBean private AgentRunEventMapper eventMapper;
    @MockitoBean private PlatformTransactionManager transactionManager;

    @BeforeEach
    void mockTransaction() {
        when(transactionManager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
    }

    @Test
    void authorAppContainsNoAdminControllersOrRoutesAndRejectsAdminTokens() throws Exception {
        ClassLoader classLoader = getClass().getClassLoader();
        assertThat(ClassUtils.isPresent("studio.foliarc.biz.rag.controller.AdminKnowledgeController", classLoader)).isFalse();
        assertThat(ClassUtils.isPresent("studio.foliarc.biz.vip.controller.AdminVipController", classLoader)).isFalse();
        assertThat(ClassUtils.isPresent("studio.foliarc.admin.AdminJavaApplication", classLoader)).isFalse();
        assertThat(requestMappingHandlerMapping.getHandlerMethods().keySet())
                .noneMatch(mapping -> mapping.getPatternValues().stream().anyMatch(path -> path.startsWith("/api/v1/admin")));

        mvc.perform(get("/api/v1/admin/knowledge/documents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenService.issueAccessToken(ADMIN)))
                .andExpect(status().isNotFound());
        String adminToken = jwtTokenService.issueAccessToken(ADMIN, JwtTokenService.AUD_ADMIN, 7200);
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void runCallbackRequiresTheInternalTokenAndPersistsStructuredEvents() throws Exception {
        UUID runId = UUID.randomUUID();
        String path = "/internal/v1/runs/" + runId + "/events";
        String payload = "{\"tool\":\"rag.search\"}";
        String body = json.writeValueAsString(Map.of("events", List.of(
                Map.of("seq", 3, "event_type", "tool_call", "agent_name", "researcher", "trace_id", "trace-3",
                        "latency_ms", 125, "payload_json", payload, "extra_field", "forward-compatible"),
                Map.of("event_type", "interrupt"),
                Map.of("event_type", "text_delta", "payload_json", "{\"text\":\"fragment\"}")
        )));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_INTERNAL_TOKEN"));
        mvc.perform(post(path).header("X-Internal-Token", "incorrect").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        verifyNoInteractions(runMapper, eventMapper);

        when(runMapper.lockForEventInsert(runId)).thenReturn(runId);
        mvc.perform(post(path).header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        var captured = ArgumentCaptor.forClass(AgentRunEventEntity.class);
        verify(eventMapper, times(2)).insert(captured.capture());
        var toolCall = captured.getAllValues().getFirst();
        assertThat(toolCall.getId()).isNotNull();
        assertThat(toolCall.getRunId()).isEqualTo(runId);
        assertThat(toolCall.getSeq()).isEqualTo(3);
        assertThat(toolCall.getEventType()).isEqualTo("tool_call");
        assertThat(toolCall.getAgentName()).isEqualTo("researcher");
        assertThat(toolCall.getTraceId()).isEqualTo("trace-3");
        assertThat(toolCall.getLatencyMs()).isEqualTo(125);
        assertThat(toolCall.getPayloadJson()).isEqualTo(payload);
        var interrupt = captured.getAllValues().getLast();
        assertThat(interrupt.getEventType()).isEqualTo("interrupt");
        assertThat(interrupt.getSeq()).isZero();
        assertThat(interrupt.getAgentName()).isEqualTo("writer");
        assertThat(interrupt.getTraceId()).isEmpty();
        assertThat(interrupt.getLatencyMs()).isZero();
        assertThat(interrupt.getPayloadJson()).isEqualTo("{}");
    }

    @Test
    void unknownRunAndEmptyBatchAreAcceptedWithoutWrites() throws Exception {
        UUID runId = UUID.randomUUID();
        String path = "/internal/v1/runs/" + runId + "/events";
        for (String body : List.of("{}", "{\"events\":[]}", "{\"events\":[{\"event_type\":\"usage\"}]}")) {
            mvc.perform(post(path).header("X-Internal-Token", INTERNAL_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated());
        }
        verify(runMapper).lockForEventInsert(runId);
        verifyNoInteractions(eventMapper);
    }

    @Test
    void invalidPayloadRollsBackTheBatch() throws Exception {
        UUID runId = UUID.randomUUID();
        when(runMapper.lockForEventInsert(runId)).thenReturn(runId);
        String body = json.writeValueAsString(Map.of("events", List.of(
                Map.of("event_type", "tool_call"),
                Map.of("event_type", "tool_result", "payload_json", "{} {}")
        )));

        mvc.perform(post("/internal/v1/runs/{runId}/events", runId).header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RUN_EVENT_PAYLOAD"));
        verify(eventMapper).insert(any(AgentRunEventEntity.class));
        verify(transactionManager).rollback(any());
    }
}
