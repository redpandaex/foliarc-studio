package studio.foliarc.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import reactor.core.publisher.Mono;
import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.billing.service.BillingService;
import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.config.mybatis.PostgresUuidTypeHandler;
import studio.foliarc.biz.gateway.RedisRateLimiterService;
import studio.foliarc.biz.genre.GenreDtos.CharacterFieldDef;
import studio.foliarc.biz.genre.GenreDtos.GenreProfileResponse;
import studio.foliarc.biz.genre.GenreProfileRegistry;
import studio.foliarc.biz.lore.CharacterState;
import studio.foliarc.biz.lore.dto.LoreDtos;
import studio.foliarc.biz.lore.entity.CharacterEntity;
import studio.foliarc.biz.lore.mapper.CharacterMapper;
import studio.foliarc.biz.lore.mapper.ForeshadowingMapper;
import studio.foliarc.biz.lore.mapper.PromptTemplateMapper;
import studio.foliarc.biz.lore.mapper.WorldEntryMapper;
import studio.foliarc.biz.lore.service.LoreService;
import studio.foliarc.biz.memory.entity.CharacterStateSnapshotEntity;
import studio.foliarc.biz.memory.mapper.ChapterSummaryMapper;
import studio.foliarc.biz.memory.mapper.CharacterRelationMapper;
import studio.foliarc.biz.memory.mapper.CharacterStateSnapshotMapper;
import studio.foliarc.biz.memory.mapper.SectionSummaryMapper;
import studio.foliarc.biz.memory.mapper.StoryRollupSummaryMapper;
import studio.foliarc.biz.memory.mapper.VolumeSummaryMapper;
import studio.foliarc.biz.memory.service.StoryMemoryContextService;
import studio.foliarc.biz.novel.dto.NovelDtos;
import studio.foliarc.biz.novel.entity.BookEntity;
import studio.foliarc.biz.novel.entity.ChapterEntity;
import studio.foliarc.biz.novel.entity.ChapterSectionEntity;
import studio.foliarc.biz.novel.mapper.BookMapper;
import studio.foliarc.biz.novel.mapper.ChapterMapper;
import studio.foliarc.biz.novel.mapper.ChapterSectionMapper;
import studio.foliarc.biz.novel.service.BookService;
import studio.foliarc.biz.outbox.service.OutboxService;
import studio.foliarc.biz.rag.controller.AdminKnowledgeController;
import studio.foliarc.biz.rag.controller.LibraryController;
import studio.foliarc.biz.rag.dto.RagDtos.LibraryDocumentResponse;
import studio.foliarc.biz.rag.entity.BookKnowledgeBindingEntity;
import studio.foliarc.biz.rag.entity.KnowledgeDocumentEntity;
import studio.foliarc.biz.rag.mapper.BookKnowledgeBindingMapper;
import studio.foliarc.biz.rag.mapper.DocumentChunkMapper;
import studio.foliarc.biz.rag.mapper.KnowledgeDocumentMapper;
import studio.foliarc.biz.rag.mapper.KnowledgeGrantMapper;
import studio.foliarc.biz.rag.service.KnowledgeDocumentService;
import studio.foliarc.biz.run.dto.RunDtos;
import studio.foliarc.biz.run.entity.AgentRunEntity;
import studio.foliarc.biz.run.mapper.AgentCheckpointMapper;
import studio.foliarc.biz.run.mapper.AgentRunMapper;
import studio.foliarc.biz.run.service.AgentRunService;
import studio.foliarc.biz.storage.MinioStorageService;
import studio.foliarc.biz.style.dto.StyleAnchorDtos.UpsertStyleAnchorRequest;
import studio.foliarc.biz.style.entity.StyleAnchorEntity;
import studio.foliarc.biz.style.mapper.StyleAnchorMapper;
import studio.foliarc.biz.style.service.StyleAnchorService;
import studio.foliarc.biz.vip.dto.VipDtos;
import studio.foliarc.biz.vip.service.VipEntitlementService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class Phase58cGenreProfileAndStyleAnchorTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String XIANXIA = "XIANXIA_FANTASY";
    private static final String HORROR = "HORROR_SUPERNATURAL";
    private static final String CLASSICAL = "CLASSICAL_HISTORICAL";
    private static final String MYSTERY = "MYSTERY_THRILLER";
    private static final String SCIFI = "SCIFI_APOCALYPSE";
    private static final String URBAN = "URBAN_REALISM";

    private final UUID ownerId = UUID.randomUUID();
    private final UUID bookId = UUID.randomUUID();
    private final BookService books = mock(BookService.class);
    private final ChapterMapper chapters = mock(ChapterMapper.class);

    @BeforeAll
    static void initializeMapperMetadata() {
        var configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(PostgresUuidTypeHandler.class);
        var assistant = new MapperBuilderAssistant(configuration, "phase58c");
        TableInfoHelper.initTableInfo(assistant, StyleAnchorEntity.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocumentEntity.class);
    }

    @Test
    void registryDefinesSixProfilesAndResolvesExplicitOrLegacyGenres() {
        var registry = new GenreProfileRegistry();
        assertThat(registry.listAll()).extracting(GenreProfileResponse::code)
                .containsExactly(XIANXIA, HORROR, CLASSICAL, MYSTERY, SCIFI, URBAN);
        assertThat(registry.listAll()).allSatisfy(profile -> {
            assertThat(profile.characterFieldSchema()).hasSize(4).allSatisfy(field -> {
                assertThat(field.key()).isNotBlank();
                assertThat(field.label()).isNotBlank();
                assertThat(field.placeholder()).isNotBlank();
            });
            assertThat(profile.recommendedSubTags()).isNotEmpty();
            assertThat(profile.stylePrinciples()).isNotBlank();
            assertThat(profile.antiClicheRules()).isNotEmpty();
            assertThat(profile.defaultBeatPattern()).isNotBlank();
            assertThat(profile.criticRubric()).hasSize(4);
            assertThat(profile.summaryFocus()).isNotBlank();
        });
        assertThat(registry.getRequired(HORROR).characterFieldSchema()).extracting(CharacterFieldDef::key)
                .containsExactly("sanity", "knownRules", "taboosTriggered", "survivalStatus");
        assertThat(registry.resolveGenreCode(" classical_historical ", "规则怪谈")).isEqualTo(CLASSICAL);
        Map.of("东方玄幻", XIANXIA, "都市怪谈", HORROR, "明清世情", CLASSICAL,
                "刑侦探案", MYSTERY, "赛博废土", SCIFI, "现实商战", URBAN)
                .forEach((genre, code) -> assertThat(registry.resolveGenreCode(null, genre)).isEqualTo(code));
        assertThat(registry.resolveGenreCode(" ", null)).isEqualTo(XIANXIA);
        assertThat(registry.normalizeSubGenreTags(" 民俗恐怖，明清世情,民俗恐怖、 ")).isEqualTo("民俗恐怖,明清世情");
        assertThatThrownBy(() -> registry.resolveGenreCode("unknown", "灵异"))
                .isInstanceOfSatisfying(BizException.class, error -> assertBizError(error, HttpStatus.BAD_REQUEST, "INVALID_GENRE_CODE"));
        assertThatThrownBy(() -> registry.getRequired("unknown"))
                .isInstanceOfSatisfying(BizException.class, error -> assertBizError(error, HttpStatus.NOT_FOUND, "GENRE_NOT_FOUND"));
    }

    @Test
    void bookCreateAndUpdatePersistGenreMetadataWithLegacyRequests() {
        var mapper = mock(BookMapper.class);
        var service = new BookService(mapper, chapters);
        var created = service.createBook(ownerId, JSON.readValue("""
                {"title":"灯下","genre":" ","genreCode":" horror_supernatural ","subGenreTags":" 民俗恐怖,规则怪谈，民俗恐怖 "}
                """, NovelDtos.CreateBookRequest.class));
        var inserted = ArgumentCaptor.forClass(BookEntity.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getGenreCode()).isEqualTo(HORROR);
        assertThat(created.genreCode()).isEqualTo(HORROR);
        assertThat(created.genre()).isEqualTo("恐怖 / 灵异 / 规则怪谈");
        assertThat(created.subGenreTags()).isEqualTo("民俗恐怖,规则怪谈");

        when(mapper.selectById(created.id())).thenReturn(inserted.getValue());
        when(mapper.updateById(any(BookEntity.class))).thenReturn(1);
        var updated = service.updateBook(ownerId, created.id(), new NovelDtos.UpdateBookRequest(
                "灯下", null, "", "ONGOING", 0L, CLASSICAL, " 明清世情, 民俗恐怖 "));
        assertThat(updated.genreCode()).isEqualTo(CLASSICAL);
        assertThat(updated.genre()).isEqualTo("古典 / 历史 / 世情志怪");
        assertThat(updated.subGenreTags()).isEqualTo("明清世情,民俗恐怖");
        var legacyUpdate = service.updateBook(ownerId, created.id(),
                new NovelDtos.UpdateBookRequest("灯下", "刑侦故事", "", "ONGOING", 0L));
        assertThat(legacyUpdate.genreCode()).isEqualTo(MYSTERY);
        assertThat(legacyUpdate.subGenreTags()).isEqualTo("明清世情,民俗恐怖");
        var titleUpdate = service.updateBook(ownerId, created.id(),
                new NovelDtos.UpdateBookRequest("灯下人", null, null, null, 0L));
        assertThat(titleUpdate.genre()).isEqualTo("刑侦故事");
        assertThat(titleUpdate.genreCode()).isEqualTo(MYSTERY);
        assertThat(titleUpdate.subGenreTags()).isEqualTo("明清世情,民俗恐怖");

        var legacyCreate = service.createBook(ownerId, new NovelDtos.CreateBookRequest("城墙", "末世废土", null));
        assertThat(legacyCreate.genre()).isEqualTo("末世废土");
        assertThat(legacyCreate.genreCode()).isEqualTo(SCIFI);
        assertThat(legacyCreate.subGenreTags()).isEmpty();
    }

    @Test
    void lorePersistsAndMergesDynamicFieldsWhileRespectingLocks() {
        BookEntity book = book(HORROR, "规则怪谈");
        when(books.requireOwnedBook(ownerId, bookId)).thenReturn(book);
        var characters = mock(CharacterMapper.class);
        var lore = lore(characters);
        var created = lore.upsertCharacter(ownerId, bookId, characterRequest("""
                {"name":"沈影","aliases":["阿影"],"lockedFields":["sanity","customAttributes.ward"],
                 "customAttributes":{"sanity":" 濒临崩溃 ","knownRules":"午夜不照镜","ward":"朱砂"}}
                """));
        var captured = ArgumentCaptor.forClass(CharacterEntity.class);
        verify(characters).insert(captured.capture());
        CharacterEntity row = captured.getValue();
        assertThat(JSON.readTree(row.getCustomAttributes()).get("sanity").asString()).isEqualTo("濒临崩溃");
        assertThat(created.realmOrLevel()).isEmpty();
        assertThat(created.aliases()).isEqualTo("阿影");
        when(characters.selectList(any())).thenReturn(List.of(row));
        when(characters.selectById(row.getId())).thenReturn(row);

        var extracted = lore.upsertAgentCharacter(ownerId, bookId, characterRequest("""
                {"name":"阿影","inventoryDelta":"火柴",
                 "custom_attributes":{"sanity":"十分平静","taboosTriggered":"敲钟三次","ward":"白纸"}}
                """));
        assertThat(extracted.customAttributes()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "sanity", "濒临崩溃", "knownRules", "午夜不照镜", "taboosTriggered", "敲钟三次", "ward", "朱砂"));
        assertThat(extracted.inventory()).isEqualTo("火柴");
        lore.updateCharacterLocks(ownerId, bookId, row.getId(), new LoreDtos.UpdateCharacterLocksRequest(List.of("customAttributes")));
        var locked = lore.upsertAgentCharacter(ownerId, bookId, characterRequest("""
                {"name":"沈影","statusNotes":"退回廊下","customAttributes":{"knownRules":"禁止出门","survivalStatus":"遭到污染"}}
                """));
        assertThat(locked.customAttributes()).isEqualTo(extracted.customAttributes());
        assertThat(locked.statusNotes()).isEqualTo("退回廊下");
        assertThat(story(lore).buildFiveLayerContext(ownerId, book, null, "沈影望向铜镜", "").activeStateBrief())
                .containsSubsequence("理智/精神状态：濒临崩溃", "已知规则：午夜不照镜", "已触发禁忌：敲钟三次");
    }

    @Test
    void xianxiaSynchronizesRealmInBothDirectionsAndSharesItsLock() {
        when(books.requireOwnedBook(ownerId, bookId)).thenReturn(book(XIANXIA, ""));
        var characters = mock(CharacterMapper.class);
        var lore = lore(characters);
        var created = lore.upsertCharacter(ownerId, bookId,
                new LoreDtos.UpsertCharacterRequest("陆川", null, null, " 筑基初期 ", null, null, null));
        assertThat(created.realmOrLevel()).isEqualTo("筑基初期");
        assertThat(created.customAttributes()).containsEntry("realm", "筑基初期");
        var captured = ArgumentCaptor.forClass(CharacterEntity.class);
        verify(characters).insert(captured.capture());
        when(characters.selectList(any())).thenReturn(List.of(captured.getValue()));

        var extracted = lore.upsertAgentCharacter(ownerId, bookId, characterRequest("""
                {"name":"陆川","customAttributes":{"realm":"筑基后期","cultivationArt":"御剑术"}}
                """));
        assertThat(extracted.realmOrLevel()).isEqualTo("筑基后期");
        assertThat(extracted.customAttributes()).containsEntry("realm", "筑基后期");
        lore.upsertCharacter(ownerId, bookId, characterRequest("""
                {"name":"陆川","realmOrLevel":"金丹期","lockedFields":["realm"]}
                """));
        var locked = lore.upsertAgentCharacter(ownerId, bookId, characterRequest("""
                {"name":"陆川","realmOrLevel":"元婴期","customAttributes":{"realm":"元婴期"}}
                """));
        assertThat(locked.realmOrLevel()).isEqualTo("金丹期");
        assertThat(locked.customAttributes()).containsEntry("realm", "金丹期").containsEntry("cultivationArt", "御剑术");
    }

    @Test
    void historicalContextUsesOnlyTheDynamicAttributesInItsSnapshot() {
        var lore = mock(LoreService.class);
        var character = new CharacterEntity();
        character.setId(UUID.randomUUID());
        character.setCustomAttributes("{\"sanity\":\"起疑\"}");
        var history = new CharacterStateSnapshotEntity();
        history.setCharacterId(character.getId());
        history.setSnapshotJson(CharacterState.captureSnapshot(character));
        when(lore.listCharacters(ownerId, bookId, null)).thenReturn(List.of(
                new LoreDtos.CharacterResponse(character.getId(), bookId, "沈影", "PROTAGONIST", "", "", "", "", "",
                        null, "", "CORE_PINNED", 12_002L, "", List.of(), Map.of("sanity", "已经崩溃"))));
        var sections = mock(ChapterSectionMapper.class);
        var snapshots = mock(CharacterStateSnapshotMapper.class);
        when(sections.findMaxNarrativeOrder(bookId)).thenReturn(12_002L);
        when(snapshots.selectList(any())).thenReturn(List.of(history));
        var story = story(lore);
        story.setChapterSectionMapper(sections);
        story.setCharacterStateSnapshotMapper(snapshots);

        var update = story.buildAdaptiveContext(ownerId, book(HORROR, ""), null, section(UUID.randomUUID()), "沈影", "");
        assertThat(update.activeStateBrief()).contains("理智/精神状态：起疑").doesNotContain("已经崩溃");
    }

    @Test
    void styleAnchorsEnforceVipQuotaWhenCreatingOrEnablingAndAllowDisablingAndDeleting() {
        var mapper = mock(StyleAnchorMapper.class);
        var service = new StyleAnchorService(books, mapper);
        var vip = mock(VipEntitlementService.class);
        var entitlements = mock(VipDtos.VipEntitlements.class);
        when(entitlements.maxStyleAnchors()).thenReturn(1);
        when(vip.resolveForUser(ownerId)).thenReturn(new VipDtos.ResolvedUserEntitlements(
                "BASIC", "基础版", "ACTIVE", null, null, 0, 0, entitlements));
        service.setVipEntitlementService(vip);
        var enabled = anchor("旧段", "门外无声。", 2, true);
        var disabled = anchor("备用", "纸灯微动。", 3, false);
        when(mapper.selectList(any())).thenReturn(List.of(enabled));
        when(mapper.selectById(enabled.getId())).thenReturn(enabled);
        when(mapper.selectById(disabled.getId())).thenReturn(disabled);

        for (UUID id : new UUID[] {null, disabled.getId()}) {
            assertThatThrownBy(() -> service.upsertAnchor(ownerId, bookId,
                    new UpsertStyleAnchorRequest(id, "", "灯亮了。", null, true)))
                    .isInstanceOfSatisfying(BizException.class, error -> {
                        assertBizError(error, HttpStatus.FORBIDDEN, "VIP_QUOTA_EXCEEDED");
                        assertThat(error.getMessage()).contains("升级");
                    });
        }
        assertThat(service.upsertAnchor(ownerId, bookId,
                new UpsertStyleAnchorRequest(enabled.getId(), "修订", "门掩着。", null, null)).sortOrder()).isEqualTo(2);
        assertThat(service.upsertAnchor(ownerId, bookId,
                new UpsertStyleAnchorRequest(null, "另存", "窗掩着。", null, false)).enabled()).isFalse();
        var toggled = service.upsertAnchor(ownerId, bookId,
                new UpsertStyleAnchorRequest(enabled.getId(), null, "门掩着。", null, false));
        assertThat(toggled.enabled()).isFalse();
        assertThat(toggled.title()).isEqualTo("修订");
        when(mapper.selectList(any())).thenReturn(List.of());
        assertThat(service.upsertAnchor(ownerId, bookId,
                new UpsertStyleAnchorRequest(disabled.getId(), "启用备用", "纸灯微动。", null, true)).enabled()).isTrue();
        service.deleteAnchor(ownerId, bookId, disabled.getId());
        verify(mapper).deleteById(disabled.getId());
    }

    @Test
    void styleAnchorsUseTheTrimmedExcerptBudgetAndOrderedEnabledQuery() {
        var mapper = mock(StyleAnchorMapper.class);
        var service = new StyleAnchorService(books, mapper);
        var first = anchor("第一段", " " + "甲".repeat(600) + " ", 1, true);
        when(mapper.selectList(any())).thenReturn(List.of(first));
        var atLimit = service.upsertAnchor(ownerId, bookId,
                new UpsertStyleAnchorRequest(null, "第二段", "  " + "乙".repeat(300) + " ", null, null));
        assertThat(atLimit.excerptText()).hasSize(300);
        assertThat(atLimit.enabled()).isTrue();
        assertThatThrownBy(() -> service.upsertAnchor(ownerId, bookId,
                new UpsertStyleAnchorRequest(null, "", "乙".repeat(301), null, null)))
                .isInstanceOfSatisfying(BizException.class, error -> assertBizError(error, HttpStatus.BAD_REQUEST, "STYLE_ANCHOR_TOO_LONG"));
        assertThat(service.upsertAnchor(ownerId, bookId,
                new UpsertStyleAnchorRequest(null, "", "丙".repeat(1_000), null, false)).enabled()).isFalse();

        var oversized = anchor("第二段", "乙".repeat(400), 2, true);
        when(mapper.selectList(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<?> query = invocation.getArgument(0);
            assertThat(query.getSqlSegment()).contains("enabled =", "sort_order ASC", "created_at ASC");
            assertThat(query.getParamNameValuePairs()).containsValue(bookId).containsValue(true);
            return List.of(first, oversized);
        });
        assertThat(service.buildStyleAnchorsText(bookId)).isEqualTo("甲".repeat(600) + "\n\n" + "乙".repeat(300));
    }

    @Test
    void standardizedRagMatchingUsesExactCaseInsensitiveCodesAndTags() {
        assertThat(KnowledgeDocumentService.matchesBookGenre(null, " ", URBAN, "", null)).isTrue();
        assertThat(KnowledgeDocumentService.matchesBookGenre("all," + HORROR, "", URBAN, "", null)).isTrue();
        assertThat(KnowledgeDocumentService.matchesBookGenre(CLASSICAL + ", horror_supernatural ", "", HORROR, "", null)).isTrue();
        assertThat(KnowledgeDocumentService.matchesBookGenre(HORROR, "民俗恐怖", CLASSICAL, "明清世情，民俗恐怖", "")).isTrue();
        assertThat(KnowledgeDocumentService.matchesBookGenre("", "志怪", CLASSICAL, "", "历史 / 志怪")).isTrue();
        assertThat(KnowledgeDocumentService.matchesBookGenre(HORROR, "民俗恐怖", URBAN, "商战", "都市现实")).isFalse();
        assertThat(KnowledgeDocumentService.matchesBookGenre("SMALL," + HORROR + "_EXTRA", "", HORROR, "", "")).isFalse();
        assertThat(KnowledgeDocumentService.matchesBookGenre("", "恐怖", URBAN, "民俗恐怖", "都市")).isFalse();
        assertThat(KnowledgeDocumentService.genreMatches("玄幻", "东方玄幻")).isTrue();
    }

    @Test
    void bookLibraryAndRagUseTheSameGenreDefaultsAndManualBindings() {
        var documents = mock(KnowledgeDocumentMapper.class);
        var bindings = mock(BookKnowledgeBindingMapper.class);
        var service = knowledge(documents, bindings);
        when(books.requireOwnedBook(ownerId, bookId)).thenReturn(book(CLASSICAL, "民俗恐怖"));
        var crossGenre = global(HORROR, "民俗恐怖");
        var mismatch = global(HORROR, "");
        var manuallyEnabled = global(URBAN, "");
        var manuallyDisabled = global(CLASSICAL, "");
        when(documents.selectList(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<?> query = invocation.getArgument(0);
            query.getSqlSegment();
            return query.getParamNameValuePairs().containsValue("GLOBAL")
                    ? List.of(crossGenre, mismatch, manuallyEnabled, manuallyDisabled) : List.of();
        });
        when(bindings.selectList(any())).thenReturn(List.of(binding(manuallyEnabled, true), binding(manuallyDisabled, false)));

        var library = service.listAccessibleLibrary(ownerId, bookId);
        assertThat(library).extracting(LibraryDocumentResponse::boundToBook).containsExactly(true, false, true, false);
        assertThat(library).extracting(LibraryDocumentResponse::genreCodes).containsExactly(HORROR, HORROR, URBAN, CLASSICAL);
        assertThat(service.resolveAccessibleDocScopes(ownerId, bookId).globalDocIds())
                .containsExactly(crossGenre.getId().toString(), manuallyEnabled.getId().toString());
        assertThat(service.setBookBinding(ownerId, bookId, mismatch.getId(), true).genreCodes()).isEqualTo(HORROR);
    }

    @Test
    void personalAndAdminUploadsPersistNormalizedGenreCodes() {
        var documents = mock(KnowledgeDocumentMapper.class);
        var service = knowledge(documents, mock(BookKnowledgeBindingMapper.class));
        var file = new MockMultipartFile("file", "notes.txt", "text/plain", new byte[] {1, 2, 3});
        var author = new AuthenticatedUser(ownerId, "author@example.test", "author", "AUTHOR");
        var admin = new AuthenticatedUser(UUID.randomUUID(), "admin@example.test", "admin", "ADMIN");

        var personal = new LibraryController(service).uploadPersonalDocument(
                author, file, "WORLDBOOK", null, null, " horror_supernatural ， all, ALL ");
        var platform = new AdminKnowledgeController(service).uploadPlatformDocument(
                admin, file, "WORLDBOOK", null, "明清官制", " classical_historical , xianxia_fantasy ", true);
        var captured = ArgumentCaptor.forClass(KnowledgeDocumentEntity.class);
        verify(documents, times(2)).insert(captured.capture());
        assertThat(captured.getAllValues()).extracting(KnowledgeDocumentEntity::getGenreCodes)
                .containsExactly(HORROR + ",ALL", CLASSICAL + "," + XIANXIA);
        assertThat(personal.genreCodes()).isEqualTo(HORROR + ",ALL");
        assertThat(platform.genreCodes()).isEqualTo(CLASSICAL + "," + XIANXIA);
    }

    @Test
    void agentStartAndResumeSendGenreAndStyleAnchorsInTheWireJson() {
        var runs = mock(AgentRunMapper.class);
        when(books.requireOwnedBook(ownerId, bookId)).thenReturn(book(CLASSICAL, "明清世情,民俗恐怖"));
        var anchorMapper = mock(StyleAnchorMapper.class);
        var first = anchor("开场", "石阶露重，门环冷。", 1, true);
        var second = anchor("收束", "轿子出了巷，才听见雨。", 2, true);
        when(anchorMapper.selectList(any())).thenReturn(List.of(first, second));
        var story = story(mock(LoreService.class));
        story.setStyleAnchorService(new StyleAnchorService(books, anchorMapper));
        List<String> paths = new ArrayList<>();
        List<JsonNode> bodies = new ArrayList<>();
        var service = agentRuns(runs, captureAgentRequests(paths, bodies), story);

        var started = service.startRun(ownerId, new RunDtos.StartRunRequest(
                bookId, null, "MULTI_AGENT", "CONTINUE", null, "", "", true));
        var run = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(runs).insert(run.capture());
        run.getValue().setStatus("PAUSED");
        when(runs.selectById(started.id())).thenReturn(run.getValue());
        service.resumeRun(ownerId, started.id(), new RunDtos.ResumeRunRequest("继续"));

        assertThat(paths).containsExactly("/internal/v1/runs/start", "/internal/v1/runs/" + started.id() + "/resume");
        assertThat(bodies).hasSize(2).allSatisfy(body -> {
            assertThat(body.get("genre_code").asString()).isEqualTo(CLASSICAL);
            assertThat(body.get("sub_genre_tags").asString()).isEqualTo("明清世情,民俗恐怖");
            assertThat(body.get("style_anchors_text").asString()).containsSubsequence(first.getExcerptText(), second.getExcerptText());
        });
    }

    @Test
    void legacyAgentRunSendsDefaultGenreAndEmptyContextFields() {
        when(books.requireOwnedBook(ownerId, bookId)).thenReturn(book(null, null));
        List<JsonNode> bodies = new ArrayList<>();
        var service = agentRuns(mock(AgentRunMapper.class), captureAgentRequests(new ArrayList<>(), bodies), null);
        service.startRun(ownerId, new RunDtos.StartRunRequest(bookId, null, null, null, null, null, null, null));

        assertThat(bodies).singleElement().satisfies(body -> {
            assertThat(body.get("genre_code").asString()).isEqualTo(XIANXIA);
            assertThat(body.get("sub_genre_tags").asString()).isEmpty();
            assertThat(body.get("style_anchors_text").asString()).isEmpty();
        });
    }

    @Test
    void sectionWorkerPayloadIncludesGenreAndSubGenreTags() {
        var story = story(mock(LoreService.class));
        var sections = mock(ChapterSectionMapper.class);
        var section = section(UUID.randomUUID());
        var chapter = new ChapterEntity();
        chapter.setId(section.getChapterId());
        chapter.setBookId(bookId);
        story.setChapterSectionMapper(sections);
        story.setSectionSummaryMapper(mock(SectionSummaryMapper.class));
        story.setStoryRollupSummaryMapper(mock(StoryRollupSummaryMapper.class));
        when(sections.selectById(section.getId())).thenReturn(section);
        when(chapters.selectById(chapter.getId())).thenReturn(chapter);
        when(books.getBookByIdUnchecked(bookId)).thenReturn(book(HORROR, "规则怪谈"));

        var payload = story.loadSectionForWorker(section.getId());
        assertThat(payload.genreCode()).isEqualTo(HORROR);
        assertThat(payload.subGenreTags()).isEqualTo("规则怪谈");
    }

    private LoreService lore(CharacterMapper characters) {
        return new LoreService(books, characters, mock(WorldEntryMapper.class), mock(ForeshadowingMapper.class),
                chapters, mock(PromptTemplateMapper.class));
    }

    private StoryMemoryContextService story(LoreService lore) {
        return new StoryMemoryContextService(books, mock(BookMapper.class), chapters, mock(ChapterSummaryMapper.class),
                mock(VolumeSummaryMapper.class), lore, mock(CharacterRelationMapper.class), mock(OutboxService.class));
    }

    private KnowledgeDocumentService knowledge(KnowledgeDocumentMapper documents, BookKnowledgeBindingMapper bindings) {
        return new KnowledgeDocumentService(books, chapters, documents, mock(DocumentChunkMapper.class),
                mock(KnowledgeGrantMapper.class), bindings, mock(MinioStorageService.class), mock(OutboxService.class), null);
    }

    private AgentRunService agentRuns(AgentRunMapper runs, WebClient client, StoryMemoryContextService story) {
        return new AgentRunService(runs, mock(AgentCheckpointMapper.class), books, chapters, client,
                mock(StringRedisTemplate.class), mock(RedisRateLimiterService.class), mock(BillingService.class),
                CircuitBreaker.ofDefaults("phase58c"), Bulkhead.ofDefaults("phase58c"), story);
    }

    private static WebClient captureAgentRequests(List<String> paths, List<JsonNode> bodies) {
        return WebClient.builder().exchangeFunction(request -> {
            var wire = new MockClientHttpRequest(request.method(), request.url());
            return request.writeTo(wire, ExchangeStrategies.withDefaults())
                    .then(Mono.defer(wire::getBodyAsString)).map(body -> {
                        paths.add(request.url().getPath());
                        bodies.add(JSON.readTree(body));
                        return ClientResponse.create(HttpStatus.ACCEPTED).build();
                    });
        }).build();
    }

    private static LoreDtos.UpsertCharacterRequest characterRequest(String json) {
        return JSON.readValue(json, LoreDtos.UpsertCharacterRequest.class);
    }

    private BookEntity book(String genreCode, String tags) {
        var book = new BookEntity();
        book.setId(bookId);
        book.setOwnerId(ownerId);
        book.setTitle("灯下");
        book.setGenreCode(genreCode);
        book.setSubGenreTags(tags);
        return book;
    }

    private ChapterSectionEntity section(UUID chapterId) {
        var section = new ChapterSectionEntity();
        section.setId(UUID.randomUUID());
        section.setBookId(bookId);
        section.setChapterId(chapterId);
        section.setChapterNo(12);
        section.setSectionNo(1);
        section.setNarrativeOrder(12_001L);
        section.setContent("走廊上响起了第三声钟。");
        return section;
    }

    private StyleAnchorEntity anchor(String title, String excerpt, int order, boolean enabled) {
        var anchor = new StyleAnchorEntity();
        anchor.setId(UUID.randomUUID());
        anchor.setBookId(bookId);
        anchor.setOwnerId(ownerId);
        anchor.setTitle(title);
        anchor.setExcerptText(excerpt);
        anchor.setSortOrder(order);
        anchor.setEnabled(enabled);
        return anchor;
    }

    private static KnowledgeDocumentEntity global(String codes, String tags) {
        var doc = new KnowledgeDocumentEntity();
        doc.setId(UUID.randomUUID());
        doc.setScope("GLOBAL");
        doc.setEnabled(true);
        doc.setStatus("READY");
        doc.setGenreCodes(codes);
        doc.setGenreTags(tags);
        return doc;
    }

    private BookKnowledgeBindingEntity binding(KnowledgeDocumentEntity doc, boolean enabled) {
        var binding = new BookKnowledgeBindingEntity();
        binding.setBookId(bookId);
        binding.setDocumentId(doc.getId());
        binding.setEnabled(enabled);
        return binding;
    }

    private static void assertBizError(BizException error, HttpStatus status, String code) {
        assertThat(error.getStatus()).isEqualTo(status);
        assertThat(error.getCode()).isEqualTo(code);
    }
}
