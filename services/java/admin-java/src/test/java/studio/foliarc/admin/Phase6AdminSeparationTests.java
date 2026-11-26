package studio.foliarc.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.util.ClassUtils;
import org.springframework.web.client.RestClient;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nimbusds.jwt.SignedJWT;
import com.zaxxer.hikari.HikariDataSource;

import studio.foliarc.biz.audit.entity.AdminAuditLogEntity;
import studio.foliarc.biz.audit.mapper.AdminAuditLogMapper;
import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.auth.JwtTokenService;
import studio.foliarc.biz.auth.entity.UserEntity;
import studio.foliarc.biz.auth.mapper.UserMapper;
import studio.foliarc.biz.billing.entity.GuardrailAuditLogEntity;
import studio.foliarc.biz.billing.mapper.GuardrailAuditLogMapper;
import studio.foliarc.biz.config.mybatis.PostgresUuidTypeHandler;
import studio.foliarc.biz.lore.entity.PromptTemplateEntity;
import studio.foliarc.biz.lore.mapper.PromptTemplateMapper;
import studio.foliarc.biz.novel.entity.BookEntity;
import studio.foliarc.biz.novel.mapper.BookMapper;
import studio.foliarc.biz.outbox.entity.OutboxEventEntity;
import studio.foliarc.biz.outbox.mapper.OutboxEventMapper;
import studio.foliarc.biz.rag.entity.KnowledgeDocumentEntity;
import studio.foliarc.biz.rag.entity.KnowledgeGrantEntity;
import studio.foliarc.biz.rag.mapper.BookKnowledgeBindingMapper;
import studio.foliarc.biz.rag.mapper.KnowledgeDocumentMapper;
import studio.foliarc.biz.rag.mapper.KnowledgeGrantMapper;
import studio.foliarc.biz.run.entity.AgentCheckpointEntity;
import studio.foliarc.biz.run.entity.AgentRunEntity;
import studio.foliarc.biz.run.entity.AgentRunEventEntity;
import studio.foliarc.biz.run.mapper.AgentCheckpointMapper;
import studio.foliarc.biz.run.mapper.AgentRunEventMapper;
import studio.foliarc.biz.run.mapper.AgentRunMapper;
import studio.foliarc.biz.storage.MinioStorageService;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"management.health.db.enabled=false", "management.health.redis.enabled=false"})
@AutoConfigureMockMvc
class Phase6AdminSeparationTests {

    private static final AuthenticatedUser ADMIN =
            new AuthenticatedUser(UUID.randomUUID(), "admin@example.test", "operator", "ADMIN");
    private static final AuthenticatedUser AUTHOR =
            new AuthenticatedUser(UUID.randomUUID(), "author@example.test", "author", "AUTHOR");
    private static final String PASSWORD = "phase6-test-password";

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper json;
    @Autowired private ApplicationContext context;
    @Autowired private Environment environment;
    @Autowired private HikariDataSource dataSource;
    @Autowired private AgentStub agent;

    @MockitoBean private UserMapper userMapper;
    @MockitoBean private OutboxEventMapper outboxMapper;
    @MockitoBean private AgentRunMapper runMapper;
    @MockitoBean private AgentRunEventMapper eventMapper;
    @MockitoBean private AgentCheckpointMapper checkpointMapper;
    @MockitoBean private GuardrailAuditLogMapper guardrailMapper;
    @MockitoBean private PromptTemplateMapper promptMapper;
    @MockitoBean private KnowledgeDocumentMapper documentMapper;
    @MockitoBean private KnowledgeGrantMapper grantMapper;
    @MockitoBean private BookKnowledgeBindingMapper bindingMapper;
    @MockitoBean private BookMapper bookMapper;
    @MockitoBean private AdminAuditLogMapper auditMapper;
    @MockitoBean private MinioStorageService minioStorage;
    @MockitoBean private PlatformTransactionManager transactionManager;

    @BeforeAll
    static void initializeMetadataForMockedMappers() {
        var configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(PostgresUuidTypeHandler.class);
        var assistant = new MapperBuilderAssistant(configuration, "phase6-admin");
        for (Class<?> type : List.of(OutboxEventEntity.class, AgentRunEventEntity.class, AgentCheckpointEntity.class,
                GuardrailAuditLogEntity.class, PromptTemplateEntity.class, KnowledgeDocumentEntity.class)) {
            TableInfoHelper.initTableInfo(assistant, type);
        }
    }

    @BeforeEach
    void mockTransaction() {
        agent.server().reset();
        when(transactionManager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
    }

    @Test
    void adminRuntimeHasAnIndependentPoolAndPublicHealthEndpoints() throws Exception {
        assertThat(dataSource.getMaximumPoolSize()).isEqualTo(6);
        assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("foliarc.outbox.enabled")).isEqualTo("false");
        assertThat(context.containsBean("flyway")).isFalse();
        assertThat(context.containsBean("outboxRelayScheduler")).isFalse();
        assertThat(ClassUtils.isPresent("studio.foliarc.biz.BizJavaApplication", getClass().getClassLoader())).isFalse();

        mvc.perform(get("/api/v1/admin/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.service").value("admin-java"))
                .andExpect(jsonPath("$.version").value("0.6.0"))
                .andExpect(jsonPath("$.port").value(8081));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void protectedRoutesRejectWebTokensEvenWhenTheUserIsAnAdmin() throws Exception {
        when(userMapper.selectById(ADMIN.userId())).thenReturn(user(ADMIN));
        for (var principal : List.of(AUTHOR, ADMIN)) {
            String webToken = jwtTokenService.issueAccessToken(principal);
            mvc.perform(get("/api/v1/admin/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + webToken))
                    .andExpect(status().isUnauthorized());
        }
        String wrongRole = jwtTokenService.issueAccessToken(AUTHOR, JwtTokenService.AUD_ADMIN, 7200);
        mvc.perform(get("/api/v1/admin/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongRole))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/auth/me").header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ADMIN.userId().toString()))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void loginRequiresAnAdminAndIssuesASeparateTwoHourToken() throws Exception {
        when(userMapper.selectOne(any())).thenReturn(user(AUTHOR), user(ADMIN));
        mvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(AUTHOR)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));

        var result = mvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresIn").value(7200))
                .andExpect(jsonPath("$.user.email").value(ADMIN.email()))
                .andExpect(jsonPath("$.user.createdAt").exists())
                .andReturn();
        String token = json.readTree(result.getResponse().getContentAsString()).get("accessToken").asString();
        var claims = SignedJWT.parse(token).getJWTClaimsSet();
        assertThat(claims.getAudience()).containsExactly(JwtTokenService.AUD_ADMIN);
        assertThat(claims.getExpirationTime().toInstant().getEpochSecond() - claims.getIssueTime().toInstant().getEpochSecond())
                .isEqualTo(7200);
        assertThat(jwtTokenService.parseAccessToken(token)).isEmpty();
    }

    @Test
    void retryOutboxPersistsTheResetAndRecordsTheOperatorAndState() throws Exception {
        UUID eventId = UUID.randomUUID();
        var event = new OutboxEventEntity();
        event.setId(eventId);
        event.setStatus("FAILED");
        event.setRetryCount(6);
        event.setLastError("broker unavailable");
        event.setPublishedAt(OffsetDateTime.now().minusHours(1));
        event.setNextRetryAt(OffsetDateTime.now().plusHours(1));
        when(outboxMapper.selectOne(any())).thenAnswer(call -> {
            LambdaQueryWrapper<?> query = call.getArgument(0);
            assertThat(query.getSqlSegment()).contains("FOR UPDATE");
            assertThat(query.getParamNameValuePairs().values()).contains(eventId);
            return event;
        });
        when(outboxMapper.update(isNull(), any())).thenAnswer(call -> {
            LambdaUpdateWrapper<?> update = call.getArgument(1);
            assertThat(update.getSqlSet()).contains("status=", "retry_count=", "last_error=", "published_at=", "next_retry_at=");
            assertThat(update.getParamNameValuePairs().values()).contains("PENDING", 0, null);
            return 1;
        });

        mvc.perform(post("/api/v1/admin/jobs/outbox/{eventId}/retry", eventId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer()).header("X-Trace-Id", "retry-202")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.10");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.retryCount").value(0))
                .andExpect(jsonPath("$.lastError").doesNotExist())
                .andExpect(jsonPath("$.publishedAt").doesNotExist());

        var captured = ArgumentCaptor.forClass(AdminAuditLogEntity.class);
        verify(auditMapper).insert(captured.capture());
        var audit = captured.getValue();
        assertThat(audit.getOperatorId()).isEqualTo(ADMIN.userId());
        assertThat(audit.getOperatorName()).isEqualTo(ADMIN.username());
        assertThat(audit.getAction()).isEqualTo("JOB_RETRY");
        assertThat(audit.getTargetType()).isEqualTo("OUTBOX_EVENT");
        assertThat(audit.getTargetId()).isEqualTo(eventId.toString());
        assertThat(audit.getTraceId()).isEqualTo("retry-202");
        assertThat(audit.getClientIp()).isEqualTo("192.0.2.10");
        assertThat(json.readTree(audit.getBeforeJson()).get("status").asString()).isEqualTo("FAILED");
        assertThat(json.readTree(audit.getAfterJson()).get("status").asString()).isEqualTo("PENDING");
        assertThat(event.getNextRetryAt()).isBefore(OffsetDateTime.now().plusSeconds(1));
    }

    @Test
    void runDetailReturnsOrderedEventsCheckpointsAndRunScopedGuardrails() throws Exception {
        UUID runId = UUID.randomUUID();
        var run = new AgentRunEntity();
        run.setId(runId);
        var first = new AgentRunEventEntity();
        first.setRunId(runId);
        first.setSeq(2);
        first.setEventType("route_decision");
        var second = new AgentRunEventEntity();
        second.setRunId(runId);
        second.setSeq(5);
        second.setEventType("tool_call");
        var checkpoint = new AgentCheckpointEntity();
        checkpoint.setRunId(runId);
        checkpoint.setStepNo(3);
        var guardrail = new GuardrailAuditLogEntity();
        guardrail.setRunId(runId);
        guardrail.setAction("BLOCK");
        when(runMapper.selectById(runId)).thenReturn(run);
        when(eventMapper.selectList(any())).thenAnswer(call -> {
            assertRunQuery(call.getArgument(0), runId, "ORDER BY seq ASC");
            return List.of(first, second);
        });
        when(checkpointMapper.selectList(any())).thenAnswer(call -> {
            assertRunQuery(call.getArgument(0), runId, "ORDER BY step_no ASC");
            return List.of(checkpoint);
        });
        when(guardrailMapper.selectList(any())).thenAnswer(call -> {
            assertRunQuery(call.getArgument(0), runId, "ORDER BY created_at ASC");
            return List.of(guardrail);
        });

        mvc.perform(get("/api/v1/admin/traces/runs/{runId}", runId).header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.id").value(runId.toString()))
                .andExpect(jsonPath("$.events[0].seq").value(2))
                .andExpect(jsonPath("$.events[1].seq").value(5))
                .andExpect(jsonPath("$.checkpoints[0].stepNo").value(3))
                .andExpect(jsonPath("$.guardrailLogs[0].action").value("BLOCK"));
    }

    @Test
    void promptsPublishTheNextVersionAndActivateItWithAuditEntries() throws Exception {
        var prior = new PromptTemplateEntity();
        prior.setId(UUID.randomUUID());
        prior.setName("writer");
        prior.setVersion(4);
        prior.setStage("ACTIVE");
        var saved = new AtomicReference<PromptTemplateEntity>();
        when(promptMapper.selectOne(any())).thenReturn(prior);
        when(promptMapper.insert(any(PromptTemplateEntity.class))).thenAnswer(call -> {
            saved.set(call.getArgument(0));
            return 1;
        });
        when(promptMapper.selectById(any())).thenAnswer(call -> saved.get());
        when(promptMapper.update(isNull(), any())).thenAnswer(call -> {
            LambdaUpdateWrapper<?> update = call.getArgument(1);
            assertThat(update.getSqlSet()).startsWith("stage=");
            assertThat(update.getSqlSegment()).contains("name =", "id <>", "version <", "OR stage =");
            assertThat(update.getParamNameValuePairs().values()).contains("writer", saved.get().getId(), 5, "ACTIVE", "ARCHIVED");
            prior.setStage("ARCHIVED");
            return 1;
        });

        mvc.perform(post("/api/v1/admin/prompts").header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"writer","stage":"DRAFT","description":"Next prompt","templateBody":"Write the next scene."}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.stage").value("DRAFT"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
        mvc.perform(post("/api/v1/admin/prompts/{promptId}/activate", saved.get().getId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("ACTIVE"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(prior.getStage()).isEqualTo("ARCHIVED");
        verify(promptMapper, times(2)).lockPromptName("writer");
        verify(promptMapper).updateById(saved.get());
        var captured = ArgumentCaptor.forClass(AdminAuditLogEntity.class);
        verify(auditMapper, times(2)).insert(captured.capture());
        assertThat(captured.getAllValues()).extracting(AdminAuditLogEntity::getAction)
                .containsExactly("PROMPT_PUBLISH", "PROMPT_ACTIVATE");
        var activation = captured.getAllValues().getLast();
        assertThat(activation.getTargetId()).isEqualTo(saved.get().getId().toString());
        assertThat(json.readTree(activation.getBeforeJson()).get("stage").asString()).isEqualTo("DRAFT");
        assertThat(json.readTree(activation.getAfterJson()).get("stage").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void platformUploadKeepsGenreNormalizationAndRecordsAnAuditEntry() throws Exception {
        when(minioStorage.getDefaultBucket()).thenReturn("phase6-test-docs");
        var file = new MockMultipartFile("file", "notes.txt", "text/plain", new byte[]{'x'});
        mvc.perform(multipart("/api/v1/admin/knowledge/documents").file(file)
                        .param("genreCodes", " classical_historical , xianxia_fantasy ")
                        .param("publicToAll", "false")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.genreCodes").value("CLASSICAL_HISTORICAL,XIANXIA_FANTASY"))
                .andExpect(jsonPath("$.enabled").value(false));
        var document = ArgumentCaptor.forClass(KnowledgeDocumentEntity.class);
        verify(documentMapper).insert(document.capture());
        var audit = ArgumentCaptor.forClass(AdminAuditLogEntity.class);
        verify(auditMapper).insert(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("KNOWLEDGE_UPLOAD");
        assertThat(audit.getValue().getTargetId()).isEqualTo(document.getValue().getId().toString());
    }

    @Test
    void ragDebugSearchPassesResolvedDocumentScopesToAgent() throws Exception {
        var book = new BookEntity();
        book.setId(UUID.randomUUID());
        book.setOwnerId(AUTHOR.userId());
        when(bookMapper.selectById(book.getId())).thenReturn(book);
        when(bindingMapper.selectList(any())).thenReturn(List.of());
        var personal = readyDocument("USER");
        var assigned = readyDocument("GLOBAL");
        var global = readyDocument("GLOBAL");
        var grant = new KnowledgeGrantEntity();
        grant.setDocumentId(assigned.getId());
        when(grantMapper.selectList(any())).thenReturn(List.of(grant));
        when(documentMapper.selectList(any())).thenReturn(List.of(personal), List.of(assigned), List.of(global));

        agent.server().expect(requestTo("http://agent.example.test/internal/v1/rag/search"))
                .andExpect(MockRestRequestMatchers.jsonPath("$.owner_id").value(AUTHOR.userId().toString()))
                .andExpect(MockRestRequestMatchers.jsonPath("$.book_id").value(book.getId().toString()))
                .andExpect(MockRestRequestMatchers.jsonPath("$.user_doc_ids[0]").value(personal.getId().toString()))
                .andExpect(MockRestRequestMatchers.jsonPath("$.assigned_doc_ids[0]").value(assigned.getId().toString()))
                .andExpect(MockRestRequestMatchers.jsonPath("$.global_doc_ids[0]").value(global.getId().toString()))
                .andExpect(MockRestRequestMatchers.jsonPath("$.max_chapter_no").value(4))
                .andRespond(withSuccess("{\"hits\":[{\"scope\":\"GLOBAL\"}]}", MediaType.APPLICATION_JSON));
        String body = json.writeValueAsString(Map.of(
                "ownerId", AUTHOR.userId(), "bookId", book.getId(), "query", "lore", "topK", 4, "maxChapterNo", 4));
        mvc.perform(post("/api/v1/admin/knowledge/debug-search").header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits[0].scope").value("GLOBAL"));
        agent.server().verify();
    }

    @Test
    void retryDocumentEnqueuesANewIngestionVersionAndAuditsTheChange() throws Exception {
        UUID docId = UUID.randomUUID();
        var doc = new KnowledgeDocumentEntity();
        doc.setId(docId);
        doc.setOwnerId(AUTHOR.userId());
        doc.setScope("USER");
        doc.setSourceType("UPLOAD");
        doc.setDocVersion(2);
        doc.setStatus("FAILED");
        doc.setProgress(45);
        doc.setErrorMessage("parser failed");
        when(documentMapper.selectOne(any())).thenReturn(doc);
        mvc.perform(post("/api/v1/admin/jobs/documents/{docId}/retry", docId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.docVersion").value(3))
                .andExpect(jsonPath("$.progress").value(0))
                .andExpect(jsonPath("$.errorMessage").doesNotExist());
        var outbox = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxMapper).insert(outbox.capture());
        assertThat(outbox.getValue().getIdempotencyKey()).isEqualTo("DOC_INGEST:" + docId + ":v3");
        assertThat(outbox.getValue().getStatus()).isEqualTo("PENDING");
        var audit = ArgumentCaptor.forClass(AdminAuditLogEntity.class);
        verify(auditMapper).insert(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("DOC_REPROCESS");
        assertThat(json.readTree(audit.getValue().getBeforeJson()).get("docVersion").asInt()).isEqualTo(2);
    }

    @Test
    void goldenEvalRunnerProxiesToAgentAndRecordsAuditSummary() throws Exception {
        agent.server().expect(requestTo("http://agent.example.test/internal/v1/evals/run"))
                .andRespond(withSuccess("""
                        {"total_cases":30,"passed_cases":30,"overall_pass_rate":1.0,"duration_ms":420,"category_scores":{"GENRE_ROUTING":1.0},"cases":[]}
                        """, MediaType.APPLICATION_JSON));
        mvc.perform(post("/api/v1/admin/evals/run").header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_cases").value(30))
                .andExpect(jsonPath("$.passed_cases").value(30))
                .andExpect(jsonPath("$.overall_pass_rate").value(1.0));
        agent.server().verify();

        var audit = ArgumentCaptor.forClass(AdminAuditLogEntity.class);
        verify(auditMapper).insert(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("EVAL_RUN");
        assertThat(audit.getValue().getTargetType()).isEqualTo("GOLDEN_EVAL");
        assertThat(json.readTree(audit.getValue().getAfterJson()).get("passedCases").asInt()).isEqualTo(30);
    }

    private static void assertRunQuery(LambdaQueryWrapper<?> query, UUID runId, String orderBy) {
        assertThat(query.getSqlSegment()).contains("run_id =", orderBy);
        assertThat(query.getParamNameValuePairs().values()).contains(runId);
    }

    private static KnowledgeDocumentEntity readyDocument(String scope) {
        var document = new KnowledgeDocumentEntity();
        document.setId(UUID.randomUUID());
        document.setScope(scope);
        document.setStatus("READY");
        return document;
    }

    private UserEntity user(AuthenticatedUser principal) {
        var user = new UserEntity();
        user.setId(principal.userId());
        user.setEmail(principal.email());
        user.setUsername(principal.username());
        user.setRole(principal.role());
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setCreatedAt(OffsetDateTime.parse("2026-11-25T00:00:00Z"));
        return user;
    }

    private String loginBody(AuthenticatedUser principal) {
        return json.writeValueAsString(Map.of("email", principal.email(), "password", PASSWORD));
    }

    private String adminBearer() {
        return "Bearer " + jwtTokenService.issueAccessToken(ADMIN, JwtTokenService.AUD_ADMIN, 7200);
    }

    private record AgentStub(RestClient client, MockRestServiceServer server) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class StubAgentConfig {

        @Bean
        AgentStub agentStub() {
            RestClient.Builder builder = RestClient.builder().baseUrl("http://agent.example.test");
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            return new AgentStub(builder.build(), server);
        }

        @Bean
        @Primary
        RestClient stubAgentClient(AgentStub agent) {
            return agent.client();
        }
    }
}
