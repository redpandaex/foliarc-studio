package studio.foliarc.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.ResultSet;
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
import org.mockito.ArgumentMatchers;
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
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
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

import studio.foliarc.admin.service.AdminReportingService.AdminUser;
import studio.foliarc.admin.service.AdminReportingService.DocumentGrantRow;
import studio.foliarc.biz.audit.entity.AdminAuditLogEntity;
import studio.foliarc.biz.audit.mapper.AdminAuditLogMapper;
import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.auth.JwtTokenService;
import studio.foliarc.biz.auth.entity.UserEntity;
import studio.foliarc.biz.auth.mapper.UserMapper;
import studio.foliarc.biz.billing.dto.BillingDtos;
import studio.foliarc.biz.billing.entity.GuardrailAuditLogEntity;
import studio.foliarc.biz.billing.mapper.GuardrailAuditLogMapper;
import studio.foliarc.biz.billing.service.BillingService;
import studio.foliarc.biz.common.TraceContext;
import studio.foliarc.biz.config.mybatis.PostgresUuidTypeHandler;
import studio.foliarc.biz.lore.entity.PromptTemplateEntity;
import studio.foliarc.biz.lore.mapper.PromptTemplateMapper;
import studio.foliarc.biz.novel.entity.BookEntity;
import studio.foliarc.biz.novel.mapper.BookMapper;
import studio.foliarc.biz.novel.mapper.ChapterMapper;
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

@SpringBootTest(properties = {"management.health.db.enabled=false", "management.health.redis.enabled=false",
        "foliarc.admin.bootstrap-email="})
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
    @MockitoBean private ChapterMapper chapterMapper;
    @MockitoBean private JdbcClient jdbc;
    @MockitoBean private BillingService billingService;
    @MockitoBean private AdminAuditLogMapper auditMapper;
    @MockitoBean private MinioStorageService minioStorage;
    @MockitoBean private PlatformTransactionManager transactionManager;

    @BeforeAll
    static void initializeMetadataForMockedMappers() {
        var configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(PostgresUuidTypeHandler.class);
        var assistant = new MapperBuilderAssistant(configuration, "phase6-admin");
        for (Class<?> type : List.of(OutboxEventEntity.class, AgentRunEntity.class, AgentRunEventEntity.class,
                AgentCheckpointEntity.class, GuardrailAuditLogEntity.class, PromptTemplateEntity.class,
                KnowledgeDocumentEntity.class, UserEntity.class, BookEntity.class)) {
            TableInfoHelper.initTableInfo(assistant, type);
        }
    }

    @BeforeEach
    void mockTransaction() {
        agent.server().reset();
        when(transactionManager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(userMapper.selectById(ADMIN.userId())).thenReturn(user(ADMIN));
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

    @Test
    void listsUsersWithLiteralSearchRoleLimitAndBookCount() throws Exception {
        var statement = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.query(ArgumentMatchers.<RowMapper<AdminUser>>any())).thenAnswer(call -> {
            ResultSet row = mock(ResultSet.class);
            when(row.getObject("id", UUID.class)).thenReturn(AUTHOR.userId());
            when(row.getString("email")).thenReturn(AUTHOR.email());
            when(row.getString("username")).thenReturn(AUTHOR.username());
            when(row.getString("role")).thenReturn("AUTHOR");
            when(row.getString("plan_code")).thenReturn("FREE");
            when(row.getLong("book_count")).thenReturn(4L);
            when(row.getLong("balance_micros")).thenReturn(12_500_000L);
            return mappedRows(List.of(call.<RowMapper<AdminUser>>getArgument(0).mapRow(row, 0)));
        });

        mvc.perform(get("/api/v1/admin/users").header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .param("q", " AuThOr% ").param("role", "AUTHOR").param("limit", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(AUTHOR.userId().toString()))
                .andExpect(jsonPath("$[0].email").value(AUTHOR.email()))
                .andExpect(jsonPath("$[0].bookCount").value(4))
                .andExpect(jsonPath("$[0].balanceCredits").value(12.5));

        verify(jdbc).sql(argThat(sql -> sql.contains("STRPOS(LOWER(u.email), LOWER(:q))")
                && sql.contains("STRPOS(LOWER(u.username), LOWER(:q))") && sql.contains("u.role = :role")
                && sql.contains("WHERE owner_id = u.id") && sql.contains("LIMIT :limit")));
        verify(statement).param("q", "AuThOr%");
        verify(statement).param("role", "AUTHOR");
        verify(statement).param("limit", 12);
    }

    @Test
    void roleChangeRecordsBeforeAfterAndMakesDemotionEffectiveForAnIssuedAdminToken() throws Exception {
        var targetAdmin = new AuthenticatedUser(AUTHOR.userId(), AUTHOR.email(), AUTHOR.username(), "ADMIN");
        var target = user(targetAdmin);
        String targetToken = "Bearer " + jwtTokenService.issueAccessToken(targetAdmin, JwtTokenService.AUD_ADMIN, 7200);
        when(userMapper.selectOne(any())).thenAnswer(call -> {
            LambdaQueryWrapper<?> query = call.getArgument(0);
            assertThat(query.getSqlSegment()).contains("FOR NO KEY UPDATE");
            assertThat(query.getParamNameValuePairs().values()).contains(target.getId());
            return target;
        });
        when(userMapper.selectById(target.getId())).thenReturn(target);
        when(userMapper.updateById(any(UserEntity.class))).thenAnswer(call -> {
            UserEntity update = call.getArgument(0);
            target.setRole(update.getRole());
            return 1;
        });

        var response = mvc.perform(put("/api/v1/admin/users/{userId}/role", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"AUTHOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(target.getId().toString()))
                .andExpect(jsonPath("$.email").value(target.getEmail()))
                .andExpect(jsonPath("$.username").value(target.getUsername()))
                .andExpect(jsonPath("$.role").value("AUTHOR"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn().getResponse();

        var update = ArgumentCaptor.forClass(UserEntity.class);
        verify(userMapper).updateById(update.capture());
        assertThat(update.getValue().getId()).isEqualTo(target.getId());
        assertThat(update.getValue().getRole()).isEqualTo("AUTHOR");
        var audit = ArgumentCaptor.forClass(AdminAuditLogEntity.class);
        verify(auditMapper).insert(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("USER_ROLE_CHANGE");
        assertThat(audit.getValue().getTargetId()).isEqualTo(target.getId().toString());
        assertThat(json.readTree(audit.getValue().getBeforeJson()).get("role").asString()).isEqualTo("ADMIN");
        assertThat(json.readTree(audit.getValue().getAfterJson()).get("role").asString()).isEqualTo("AUTHOR");
        assertThat(audit.getValue().getTraceId()).matches("[0-9a-f]{32}")
                .isEqualTo(response.getHeader(TraceContext.HEADER_NAME));

        mvc.perform(get("/api/v1/admin/auth/me").header(HttpHeaders.AUTHORIZATION, targetToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsInvalidRoleAndSelfDemotionBeforeAnyMutation() throws Exception {
        mvc.perform(put("/api/v1/admin/users/{userId}/role", AUTHOR.userId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OWNER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ROLE"));
        mvc.perform(put("/api/v1/admin/users/{userId}/role", ADMIN.userId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"AUTHOR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_DEMOTE_SELF"));

        verify(userMapper, never()).updateById(any(UserEntity.class));
        verifyNoInteractions(auditMapper);
    }

    @Test
    void grantsCreditsWithinAdminRangeAndAuditsReasonAndNewBalance() throws Exception {
        when(userMapper.selectById(AUTHOR.userId())).thenReturn(user(AUTHOR));
        var summary = new BillingDtos.AccountSummary(AUTHOR.userId(), 5_010_000L, 5.01,
                0L, 0.0, 100_000_000L, 100.0, 0L, 0.0, "2026-11", 0.0);
        when(billingService.topUp(eq(AUTHOR.userId()), eq(0.01), anyString())).thenReturn(summary);

        mvc.perform(post("/api/v1/admin/users/{userId}/credits", AUTHOR.userId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .header(TraceContext.HEADER_NAME, "admin-credit-grant")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountCredits\":0.01,\"reason\":\"活动奖励\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(AUTHOR.userId().toString()))
                .andExpect(jsonPath("$.balanceCredits").value(5.01));

        var requestId = ArgumentCaptor.forClass(String.class);
        verify(billingService).topUp(eq(AUTHOR.userId()), eq(0.01), requestId.capture());
        assertThat(requestId.getValue()).matches("admin-grant-[0-9a-f-]{36}");
        var audit = ArgumentCaptor.forClass(AdminAuditLogEntity.class);
        verify(auditMapper).insert(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("USER_CREDIT_GRANT");
        assertThat(audit.getValue().getTargetId()).isEqualTo(AUTHOR.userId().toString());
        assertThat(audit.getValue().getTraceId()).isEqualTo("admin-credit-grant");
        var after = json.readTree(audit.getValue().getAfterJson());
        assertThat(after.get("reason").asString()).isEqualTo("活动奖励");
        assertThat(after.get("balanceCredits").asDouble()).isEqualTo(5.01);
    }

    @Test
    void rejectsCreditAmountsOutsideTheAdminRangeAndUnknownUsers() throws Exception {
        for (String amount : List.of("0", "100000.01")) {
            mvc.perform(post("/api/v1/admin/users/{userId}/credits", AUTHOR.userId())
                            .header(HttpHeaders.AUTHORIZATION, adminBearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amountCredits\":" + amount + ",\"reason\":\"活动奖励\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details.amountCredits").exists());
        }
        mvc.perform(post("/api/v1/admin/users/{userId}/credits", AUTHOR.userId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountCredits\":1,\"reason\":\"活动奖励\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        verifyNoInteractions(billingService, auditMapper);
    }

    @Test
    void listsUserBooksInUpdateOrderWithChapterStats() throws Exception {
        var latest = new BookEntity();
        latest.setId(UUID.randomUUID());
        latest.setTitle("新卷");
        latest.setGenreCode("XIANXIA_FANTASY");
        latest.setUpdatedAt(OffsetDateTime.parse("2026-11-26T00:00:00Z"));
        var older = new BookEntity();
        older.setId(UUID.randomUUID());
        older.setTitle("旧卷");
        older.setUpdatedAt(latest.getUpdatedAt().minusDays(1));
        when(bookMapper.selectList(any())).thenAnswer(call -> {
            LambdaQueryWrapper<?> query = call.getArgument(0);
            assertThat(query.getSqlSegment()).contains("owner_id =", "ORDER BY updated_at DESC");
            assertThat(query.getParamNameValuePairs().values()).contains(AUTHOR.userId());
            return List.of(latest, older);
        });
        var stats = new ChapterMapper.BookChapterStats();
        stats.setBookId(latest.getId());
        stats.setChapterCount(5);
        stats.setTotalWordCount(12_345);
        when(chapterMapper.findStatsByOwnerId(AUTHOR.userId())).thenReturn(List.of(stats));

        mvc.perform(get("/api/v1/admin/users/{userId}/books", AUTHOR.userId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(latest.getId().toString()))
                .andExpect(jsonPath("$[0].title").value(latest.getTitle()))
                .andExpect(jsonPath("$[0].genreCode").value("XIANXIA_FANTASY"))
                .andExpect(jsonPath("$[0].chapterCount").value(5))
                .andExpect(jsonPath("$[0].totalWordCount").value(12_345))
                .andExpect(jsonPath("$[0].updatedAt").exists())
                .andExpect(jsonPath("$[1].id").value(older.getId().toString()))
                .andExpect(jsonPath("$[1].chapterCount").value(0));
    }

    @Test
    void listsActiveDocumentGrantsWithGranteeIdentityInCreationOrder() throws Exception {
        UUID documentId = UUID.randomUUID();
        var now = OffsetDateTime.parse("2026-11-26T00:00:00Z");
        var grant = new DocumentGrantRow(UUID.randomUUID(), documentId, AUTHOR.userId(), AUTHOR.email(),
                AUTHOR.username(), "READ", now);
        var older = new DocumentGrantRow(UUID.randomUUID(), documentId, ADMIN.userId(), ADMIN.email(),
                ADMIN.username(), "READ", now.minusDays(1));
        var statement = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
        when(jdbc.sql(anyString())).thenReturn(statement);
        var rows = mappedRows(List.of(grant, older));
        when(statement.query(DocumentGrantRow.class)).thenReturn(rows);

        mvc.perform(get("/api/v1/admin/knowledge/documents/{docId}/grants", documentId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].grantId").value(grant.grantId().toString()))
                .andExpect(jsonPath("$[0].documentId").value(documentId.toString()))
                .andExpect(jsonPath("$[0].userId").value(AUTHOR.userId().toString()))
                .andExpect(jsonPath("$[0].email").value(AUTHOR.email()))
                .andExpect(jsonPath("$[0].username").value(AUTHOR.username()))
                .andExpect(jsonPath("$[0].permission").value("READ"))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[1].grantId").value(older.grantId().toString()));
        verify(jdbc).sql(argThat(sql -> sql.contains("g.document_id = :docId") && sql.contains("JOIN users")
                && sql.contains("d.deleted_at IS NULL") && sql.contains("ORDER BY g.created_at DESC")));
        verify(statement).param("docId", documentId);
    }

    @Test
    void traceIdFiltersRunsByDistinctEventRunIdsAlongsideStatusAndLimit() throws Exception {
        UUID runId = UUID.randomUUID();
        var event = new AgentRunEventEntity();
        event.setRunId(runId);
        var run = new AgentRunEntity();
        run.setId(runId);
        run.setStatus("PAUSED");
        when(eventMapper.selectList(any())).thenAnswer(call -> {
            LambdaQueryWrapper<?> query = call.getArgument(0);
            assertThat(query.getSqlSelect()).isEqualTo("run_id");
            assertThat(query.getSqlSegment()).contains("trace_id =", "GROUP BY run_id");
            assertThat(query.getParamNameValuePairs().values()).containsExactly("trace-6.5");
            return List.of(event);
        });
        when(runMapper.selectList(any())).thenAnswer(call -> {
            LambdaQueryWrapper<?> query = call.getArgument(0);
            assertThat(query.getSqlSegment()).contains("status =", "id IN", "LIMIT 5");
            assertThat(query.getParamNameValuePairs().values()).containsExactlyInAnyOrder("PAUSED", runId);
            return List.of(run);
        });

        mvc.perform(get("/api/v1/admin/traces/runs").header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .param("traceId", "trace-6.5").param("status", " paused ").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(runId.toString()))
                .andExpect(jsonPath("$[0].status").value("PAUSED"));
    }

    @Test
    void unknownTraceIdReturnsAnEmptyListWithoutQueryingRuns() throws Exception {
        mvc.perform(get("/api/v1/admin/traces/runs").header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .param("traceId", "missing-trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        verify(eventMapper).selectList(any());
        verifyNoInteractions(runMapper);
    }

    private static <T> JdbcClient.MappedQuerySpec<T> mappedRows(List<T> rows) {
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<T> spec = mock(JdbcClient.MappedQuerySpec.class);
        when(spec.list()).thenReturn(rows);
        return spec;
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
