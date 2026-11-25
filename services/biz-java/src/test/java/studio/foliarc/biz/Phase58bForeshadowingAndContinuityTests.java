package studio.foliarc.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import reactor.core.publisher.Mono;
import studio.foliarc.biz.billing.service.BillingService;
import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.config.mybatis.PostgresUuidTypeHandler;
import studio.foliarc.biz.gateway.RedisRateLimiterService;
import studio.foliarc.biz.lore.dto.LoreDtos;
import studio.foliarc.biz.lore.entity.CharacterEntity;
import studio.foliarc.biz.lore.entity.ForeshadowingEntity;
import studio.foliarc.biz.lore.mapper.CharacterMapper;
import studio.foliarc.biz.lore.mapper.ForeshadowingMapper;
import studio.foliarc.biz.lore.mapper.PromptTemplateMapper;
import studio.foliarc.biz.lore.mapper.WorldEntryMapper;
import studio.foliarc.biz.lore.service.LoreService;
import studio.foliarc.biz.memory.dto.MemoryProposalDtos.ProposalDecisionRequest;
import studio.foliarc.biz.memory.entity.CharacterRelationEntity;
import studio.foliarc.biz.memory.entity.CharacterStateSnapshotEntity;
import studio.foliarc.biz.memory.entity.MemoryProposalEntity;
import studio.foliarc.biz.memory.entity.SectionSummaryEntity;
import studio.foliarc.biz.memory.entity.StoryRollupSummaryEntity;
import studio.foliarc.biz.memory.entity.TimelineEventEntity;
import studio.foliarc.biz.memory.mapper.ChapterSummaryMapper;
import studio.foliarc.biz.memory.mapper.CharacterRelationMapper;
import studio.foliarc.biz.memory.mapper.CharacterStateSnapshotMapper;
import studio.foliarc.biz.memory.mapper.MemoryProposalMapper;
import studio.foliarc.biz.memory.mapper.SectionSummaryMapper;
import studio.foliarc.biz.memory.mapper.StoryRollupSummaryMapper;
import studio.foliarc.biz.memory.mapper.TimelineEventMapper;
import studio.foliarc.biz.memory.mapper.VolumeSummaryMapper;
import studio.foliarc.biz.memory.service.MemoryProposalService;
import studio.foliarc.biz.memory.service.MemoryService;
import studio.foliarc.biz.memory.service.StoryMemoryContextService;
import studio.foliarc.biz.novel.entity.BookEntity;
import studio.foliarc.biz.novel.entity.ChapterEntity;
import studio.foliarc.biz.novel.entity.ChapterSectionEntity;
import studio.foliarc.biz.novel.mapper.BookMapper;
import studio.foliarc.biz.novel.mapper.ChapterMapper;
import studio.foliarc.biz.novel.mapper.ChapterSectionMapper;
import studio.foliarc.biz.novel.service.BookService;
import studio.foliarc.biz.novel.service.StructureService;
import studio.foliarc.biz.outbox.service.OutboxService;
import studio.foliarc.biz.rag.dto.RagDtos;
import studio.foliarc.biz.run.dto.RunDtos;
import studio.foliarc.biz.run.entity.AgentRunEntity;
import studio.foliarc.biz.run.mapper.AgentCheckpointMapper;
import studio.foliarc.biz.run.mapper.AgentRunMapper;
import studio.foliarc.biz.run.service.AgentRunService;
import studio.foliarc.biz.vip.dto.VipDtos;
import studio.foliarc.biz.vip.service.VipEntitlementService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class Phase58bForeshadowingAndContinuityTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final UUID ownerId = UUID.randomUUID();
    private final UUID bookId = UUID.randomUUID();
    private final BookService books = mock(BookService.class);
    private final ChapterMapper chapters = mock(ChapterMapper.class);
    private final ChapterSectionMapper sections = mock(ChapterSectionMapper.class);
    private final SectionSummaryMapper summaries = mock(SectionSummaryMapper.class);
    private final CharacterRelationMapper relations = mock(CharacterRelationMapper.class);
    private final TimelineEventMapper timeline = mock(TimelineEventMapper.class);
    private final CharacterStateSnapshotMapper snapshots = mock(CharacterStateSnapshotMapper.class);

    @BeforeAll
    static void initializeMapperMetadata() {
        var configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(PostgresUuidTypeHandler.class);
        var assistant = new MapperBuilderAssistant(configuration, "phase58b");
        for (Class<?> entity : List.of(CharacterEntity.class, ForeshadowingEntity.class, CharacterRelationEntity.class,
                TimelineEventEntity.class, CharacterStateSnapshotEntity.class, MemoryProposalEntity.class,
                ChapterSectionEntity.class, SectionSummaryEntity.class, StoryRollupSummaryEntity.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @Test
    void foreshadowingDirectorTwoLayerAntiSpoilerAndExactQuoteRecall() {
        var lore = mock(LoreService.class);
        var service = storyMemory(lore);
        ChapterEntity chapter = chapter(12);
        ChapterSectionEntity current = section(chapter, 2, "林青回到旧宅。");
        String anchor = "玉佩背面有一条极细的裂缝。";
        String before = "甲".repeat(400);
        String after = "乙".repeat(400);
        ChapterSectionEntity planted = section(chapter(4), 1, "片段外开头" + before + anchor + after + "片段外结尾");
        when(sections.selectById(planted.getId())).thenReturn(planted);
        when(lore.listCharacters(ownerId, bookId, null)).thenReturn(List.of(
                character("林青", "青儿", "CORE_PINNED", "", "")));

        var jade = foreshadowing("#F03", "裂纹玉佩", "边缘不平", "裂纹封着一位旧神", planted, anchor, "青儿", null);
        var bell = foreshadowing("#F04", "废寺铜铃", "铃声时有时无", "铜铃藏着失踪掌门", null, "", "", 12);
        when(lore.listForeshadowings(ownerId, bookId, "OPEN")).thenReturn(List.of(jade, bell));
        when(lore.listForeshadowings(ownerId, bookId, null)).thenReturn(List.of(jade, bell));

        var latent = service.buildAdaptiveContext(ownerId, book(), chapter, current, current.getContent(), "续写");
        assertThat(latent.foreshadowingWriterPack()).contains("边缘不平", "铃声时有时无", "【严禁提前点破真相】")
                .doesNotContain(jade.hiddenTruth(), bell.hiddenTruth(), jade.title(), bell.title());
        assertThat(latent.activeStateBrief()).doesNotContain(jade.hiddenTruth(), bell.hiddenTruth());
        assertThat(latent.foreshadowingCriticPack()).contains(jade.hiddenTruth(), bell.hiddenTruth());
        verify(sections, never()).selectById(planted.getId());

        var hint = service.buildAdaptiveContext(ownerId, book(), chapter, current, "", "", null,
                List.of(new RunDtos.ForeshadowingDirective(jade.id(), "HINT", null),
                        new RunDtos.ForeshadowingDirective(bell.id(), "PLANT", null)));
        assertThat(hint.foreshadowingWriterPack()).contains(before + anchor + after, "轻点照应即可", "自然藏入动作/物件细节")
                .doesNotContain(jade.hiddenTruth(), bell.hiddenTruth(), "片段外开头", "片段外结尾");
        assertThat(hint.foreshadowingCriticPack()).contains(jade.hiddenTruth(), bell.hiddenTruth());

        var payoff = service.buildAdaptiveContext(ownerId, book(), chapter, current, "", "", null,
                List.of(new RunDtos.ForeshadowingDirective(jade.id(), "PAYOFF", "PARTIAL_REVEAL")));
        assertThat(payoff.foreshadowingWriterPack()).contains(jade.hiddenTruth(), before + anchor + after, "PARTIAL_REVEAL")
                .doesNotContain(bell.hiddenTruth(), "片段外开头", "片段外结尾");
        assertThat(payoff.foreshadowingCriticPack()).contains(anchor, jade.hiddenTruth(), bell.hiddenTruth());
        verify(sections, times(2)).selectById(planted.getId());
    }

    @Test
    void aliasNormalizationDormantRehydrationAndDeadTombstone() {
        var lore = mock(LoreService.class);
        when(lore.listCharacters(ownerId, bookId, null)).thenReturn(List.of(
                character("林青", "青儿", "CORE_PINNED", "", ""),
                character("顾行舟", "顾三， 血衣客 ", "DORMANT", "金丹期", "双刃"),
                character("被忘道人", "老道", "DORMANT", "", ""),
                character("谢云", "谢长老", "DEAD", "返虚期", "镇魂灯")));
        ChapterEntity chapter = chapter(22);
        var ctx = storyMemory(lore).buildAdaptiveContext(ownerId, book(), chapter, section(chapter, 1, ""),
                "血衣客与谢长老的旧友重逢。", "");

        assertThat(ctx.activeStateBrief()).contains("林青", "顾行舟", "金丹期", "双刃")
                .doesNotContain("被忘道人", "谢云", "谢长老", "返虚期", "镇魂灯");
        assertThat(ctx.criticGuardBrief()).contains("已亡角色红线：谢云").doesNotContain("顾行舟");
    }

    @Test
    void timeTravelSnapshotPreventsFutureLeakAndPovSplitsKnowledge() {
        var lore = mock(LoreService.class);
        var hero = character("林青", "青儿，血衣客", "CORE_PINNED", "元婴期", "星渊神剑");
        var unsnapshotted = character("苏晚", "", "ACTIVE", "大乘期", "未来神器");
        when(lore.listCharacters(ownerId, bookId, null)).thenReturn(List.of(hero, unsnapshotted));
        ChapterEntity chapter = chapter(12);
        ChapterSectionEntity current = section(chapter, 1, "");
        when(sections.findMaxNarrativeOrder(bookId)).thenReturn(60_001L);
        var old = new CharacterStateSnapshotEntity();
        old.setCharacterId(hero.id());
        old.setNarrativeOrder(10_001L);
        old.setSnapshotJson("""
                {"realmOrLevel":"筑基初期","inventory":"青云剑","roleType":"PROTAGONIST","lifecycleStatus":"CORE_PINNED"}
                """);
        when(snapshots.selectList(any())).thenAnswer(call -> {
            QueryWrapper<?> query = call.getArgument(0);
            assertThat(query.getSqlSelect()).contains("DISTINCT ON (character_id)", "snapshot_json");
            assertThat(query.getSqlSegment()).contains("narrative_order <=", "narrative_order DESC");
            assertThat(query.getParamNameValuePairs()).containsValue(12_001L);
            return List.of(old);
        });

        TimelineEventEntity secret = event("长老暗设陷阱", "反派长老");
        TimelineEventEntity publicEvent = event("宗门公布试炼名单", "PUBLIC");
        TimelineEventEntity aliasEvent = event("师父私赠剑谱", "青儿，密使");
        TimelineEventEntity partialName = event("近卫受命", "林青亲卫");
        when(timeline.selectList(any())).thenAnswer(call -> {
            AbstractWrapper<?, ?, ?> query = call.getArgument(0);
            assertThat(query.getSqlSegment()).contains("narrative_order <=");
            assertThat(query.getParamNameValuePairs()).containsValue(12_001L);
            return List.of(secret, publicEvent, aliasEvent, partialName);
        });
        var relation = new CharacterRelationEntity();
        relation.setSourceCharacter("林青");
        relation.setTargetCharacter("反派长老");
        relation.setRelationType("暗中监视");
        relation.setKnownBy("反派长老");
        when(relations.selectList(any())).thenReturn(List.of(relation));

        var ctx = storyMemory(lore).buildAdaptiveContext(ownerId, book(), chapter, current, "林青和苏晚同行。", "", "林青", List.of());
        assertThat(ctx.activeStateBrief()).contains("筑基初期", "青云剑")
                .doesNotContain("元婴期", "星渊神剑", "大乘期", "未来神器");
        String known = "【当前视角（林青）已知事实】";
        String behind = "【幕后客观事实（当前视角尚不知情，仅防世界观穿帮，严禁主角凭空知晓！）】";
        assertThat(ctx.activeStateBrief()).containsSubsequence(known, publicEvent.getEventTitle(), behind, secret.getEventTitle());
        String[] sectionsByKnowledge = ctx.activeStateBrief().split(behind, 2);
        assertThat(sectionsByKnowledge[0]).contains(publicEvent.getEventTitle(), aliasEvent.getEventTitle())
                .doesNotContain(secret.getEventTitle(), partialName.getEventTitle(), "暗中监视");
        assertThat(sectionsByKnowledge[1]).contains(secret.getEventTitle(), partialName.getEventTitle(), "暗中监视")
                .doesNotContain(publicEvent.getEventTitle(), aliasEvent.getEventTitle());
        assertThat(ctx.criticGuardBrief()).contains("当前视角约束：林青");
    }

    @Test
    void fieldLocksHighRiskProposalsAndCleanSourceSectionRollback() {
        var characterMapper = mock(CharacterMapper.class);
        var clueMapper = mock(ForeshadowingMapper.class);
        var proposalMapper = mock(MemoryProposalMapper.class);
        var lore = realLore(characterMapper, clueMapper);
        var proposals = new MemoryProposalService(books, proposalMapper, characterMapper, clueMapper);
        proposals.setChapterSectionMapper(sections);
        lore.setMemoryProposalService(proposals);
        var memory = new MemoryService(books, lore, relations, timeline);
        memory.setCharacterStateSnapshotMapper(snapshots);
        memory.setMemoryProposalService(proposals);
        var story = storyMemory(lore);
        story.setMemoryService(memory);

        BookEntity book = book();
        book.setPrecisionMode("EPIC");
        when(books.getBookByIdUnchecked(bookId)).thenReturn(book);
        ChapterEntity chapter = chapter(280);
        ChapterSectionEntity section = section(chapter, 3, "血衣客推门而入。铜铃响起。");
        section.setSummaryDirty(true);
        when(chapters.selectById(chapter.getId())).thenReturn(chapter);
        when(sections.selectById(section.getId())).thenReturn(section);
        when(sections.selectOne(any())).thenReturn(section(chapter(900), 1, "未来段落"));
        var priorSummary = new SectionSummaryEntity();
        priorSummary.setId(UUID.randomUUID());
        priorSummary.setStatus("STALE");
        when(summaries.selectOne(any())).thenReturn(priorSummary);

        var alias = characterRow("顾行舟", "DORMANT");
        alias.setAliases("顾三，血衣客");
        alias.setPersonality("作者设定的性格");
        alias.setLockedFields("[\"personality\"]");
        var death = characterRow("苏晚", "ACTIVE");
        var core = characterRow("林青", "CORE_PINNED");
        core.setRoleType("PROTAGONIST");
        when(characterMapper.selectList(any())).thenReturn(List.of(alias, death, core));
        var clue = clueRow("#F03", "废寺铜铃", "PLANTED");
        clue.setAuthorLocked(true);
        when(clueMapper.selectOne(argThat(query -> query != null && query.getSqlSegment().contains("UPPER(code)"))))
                .thenReturn(clue);
        when(clueMapper.selectById(clue.getId())).thenReturn(clue);
        when(characterMapper.selectById(death.getId())).thenReturn(death);

        var request = JSON.readValue("""
                {
                  "sectionId": "%s", "contentHash": "%s",
                  "summary": {"summaryText":"血衣客现身。"},
                  "memory": {
                    "characterUpdates": [
                      {"name":"血衣客","aliases":["血衣客","红客"],"personality":"AI 提取的性格","inventoryDelta":"双刃"},
                      {"name":"苏晚","lifecycleStatus":"DEAD","statusNotes":"在阵中牺牲"},
                      {"name":"林青","isHighRisk":true,"roleType":"ANTAGONIST","inventory":"夺来的法印"}
                    ],
                    "foreshadowingSignals": [{"code":"#f03","title":"改写过的标题","action":"RESOLVED"}],
                    "relations": [
                      {"sourceCharacter":"血衣客","targetCharacter":"林青","relationType":"相识","knownBy":"林青，林青"}
                    ],
                    "timelineEvents": [{"eventTitle":"废寺铜铃响起","knownBy":" "}]
                  }
                }
                """.formatted(section.getId(), section.getContentHash()), RagDtos.InternalApplyDistillRequest.class);
        assertThat(story.applySectionDistill(bookId, request).saved()).isTrue();

        var ordered = inOrder(relations, timeline, snapshots);
        ordered.verify(relations).delete(argThat(query -> extractedFromSection(query, "source_section_id", section.getId())));
        ordered.verify(timeline).delete(argThat(query -> extractedFromSection(query, "source_section_id", section.getId())));
        ordered.verify(snapshots).delete(argThat(query -> extractedFromSection(query, "section_id", section.getId())));
        var snapshot = ArgumentCaptor.forClass(CharacterStateSnapshotEntity.class);
        ordered.verify(snapshots).insert(snapshot.capture());
        assertThat(snapshot.getValue().getCharacterId()).isEqualTo(alias.getId());
        assertThat(snapshot.getValue().getSectionId()).isEqualTo(section.getId());
        assertThat(snapshot.getValue().getNarrativeOrder()).isEqualTo(280_003L);
        assertThat(snapshot.getValue().getSource()).isEqualTo("AI_EXTRACTED");
        assertThat(snapshot.getValue().getSnapshotJson()).contains("作者设定的性格", "双刃").doesNotContain("AI 提取的性格");
        ordered.verify(relations).insert(argThat((CharacterRelationEntity row) -> section.getId().equals(row.getSourceSectionId())
                && row.getNarrativeOrder() == 280_003L && "AI_EXTRACTED".equals(row.getSource())
                && "顾行舟".equals(row.getSourceCharacter()) && "林青".equals(row.getKnownBy())));
        ordered.verify(timeline).insert(argThat((TimelineEventEntity row) -> section.getId().equals(row.getSourceSectionId())
                && row.getNarrativeOrder() == 280_003L && "AI_EXTRACTED".equals(row.getSource()) && "PUBLIC".equals(row.getKnownBy())));
        verify(characterMapper, never()).insert(any(CharacterEntity.class));
        assertThat(alias.getLifecycleStatus()).isEqualTo("ACTIVE");
        assertThat(alias.getLastSeenOrder()).isEqualTo(280_003L);
        assertThat(alias.getAliases()).isEqualTo("顾三,血衣客,红客");
        assertThat(death.getLifecycleStatus()).isEqualTo("ACTIVE");
        assertThat(core.getRoleType()).isEqualTo("PROTAGONIST");
        assertThat(clue.getStatus()).isEqualTo("PLANTED");
        verify(clueMapper, never()).updateById(any(ForeshadowingEntity.class));

        var created = ArgumentCaptor.forClass(MemoryProposalEntity.class);
        verify(proposalMapper, times(3)).insert(created.capture());
        assertThat(created.getAllValues()).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo("PENDING");
            assertThat(row.getSectionId()).isEqualTo(section.getId());
            when(proposalMapper.selectById(row.getId())).thenReturn(row);
        });
        assertThat(created.getAllValues()).extracting(MemoryProposalEntity::getProposalType)
                .containsExactly("CHARACTER_DEATH", "CORE_IDENTITY_SHIFT", "FORESHADOWING_RESOLVE");
        var accept = new ProposalDecisionRequest("ACCEPT");
        var reject = new ProposalDecisionRequest("REJECT");
        assertThat(proposals.decide(ownerId, bookId, created.getAllValues().get(0).getId(), accept).status()).isEqualTo("ACCEPTED");
        assertThat(death.getLifecycleStatus()).isEqualTo("DEAD");
        assertThat(death.getStatusNotes()).isEqualTo("在阵中牺牲");
        assertThat(proposals.decide(ownerId, bookId, created.getAllValues().get(1).getId(), reject).status()).isEqualTo("REJECTED");
        assertThat(core.getRoleType()).isEqualTo("PROTAGONIST");
        assertThat(proposals.decide(ownerId, bookId, created.getAllValues().get(2).getId(), accept).status()).isEqualTo("ACCEPTED");
        assertThat(clue.getStatus()).isEqualTo("RESOLVED");
        assertThat(clue.getResolvedSectionId()).isEqualTo(section.getId());
        assertThat(clue.getResolvedChapterNo()).isEqualTo(280);
    }

    @Test
    void legacyOpenTransitionsDueAndVipQuotaUseStableCodes() {
        var clues = mock(ForeshadowingMapper.class);
        var lore = realLore(mock(CharacterMapper.class), clues);
        ForeshadowingEntity legacy = clueRow("#F99", "旧伏笔", "OPEN");
        legacy.setPlantedChapterNo(3);
        legacy.setTargetChapterMin(8);
        legacy.setTargetChapterMax(9);
        when(clues.selectList(any())).thenReturn(List.of(legacy));
        var created = lore.upsertForeshadowing(ownerId, bookId, new LoreDtos.UpsertForeshadowingRequest(
                "黑羽", "窗檐上的黑羽", null, null, "OPEN"));
        assertThat(created.code()).isEqualTo("#F100");
        assertThat(created.status()).isEqualTo("PLANNED");
        assertThat(created.surfaceClue()).isEqualTo("窗檐上的黑羽");
        assertThat(lore.listForeshadowings(ownerId, bookId, "OPEN")).singleElement()
                .extracting(LoreDtos.ForeshadowingResponse::status).isEqualTo("PLANTED");
        verify(clues).selectList(argThat(query -> query != null && query.getSqlSegment().contains("status IN")
                && ((AbstractWrapper<?, ?, ?>) query).getParamNameValuePairs().values()
                        .containsAll(List.of("OPEN", "PLANNED", "PLANTED", "HINTED", "TRIGGERED"))));
        assertThat(lore.dueForeshadowings(ownerId, bookId, 12).dueNow()).extracting(LoreDtos.ForeshadowingResponse::code)
                .containsExactly("#F99");

        when(clues.selectOne(argThat(query -> query != null && query.getSqlSegment().contains("UPPER(code)")))).thenReturn(legacy);
        var renamed = lore.upsertForeshadowing(ownerId, bookId, JSON.readValue("""
                {"code":"#f99","title":"新伏笔名称","status":"OPEN"}
                """, LoreDtos.UpsertForeshadowingRequest.class));
        assertThat(renamed.id()).isEqualTo(legacy.getId());
        assertThat(renamed.title()).isEqualTo("新伏笔名称");
        when(clues.selectById(legacy.getId())).thenReturn(legacy);
        ChapterSectionEntity section = section(chapter(9), 2, "窗檐上的黑羽忽然转动。");
        when(sections.selectById(section.getId())).thenReturn(section);
        var planted = lore.transitionForeshadowing(ownerId, bookId, legacy.getId(),
                new LoreDtos.TransitionForeshadowingRequest("PLANTED", section.getId(), null, null, "黑羽", null));
        assertThat(planted.plantedNarrativeOrder()).isEqualTo(9002L);
        assertThat(planted.plantedSectionId()).isEqualTo(section.getId());
        assertThat(planted.plantedQuote()).isEqualTo("黑羽");
        var hinted = lore.transitionForeshadowing(ownerId, bookId, legacy.getId(),
                new LoreDtos.TransitionForeshadowingRequest("HINTED", section.getId(), null, null, "黑羽转动", "再次照应"));
        JsonNode hint = JSON.readTree(hinted.hintHistoryJson()).get(0);
        assertThat(hint.get("chapterNo").asInt()).isEqualTo(9);
        assertThat(hint.get("sectionNo").asInt()).isEqualTo(2);
        assertThat(hint.get("quote").asString()).isEqualTo("黑羽转动");
        assertThat(hint.get("note").asString()).isEqualTo("再次照应");

        var vip = mock(VipEntitlementService.class);
        var freePlan = mock(VipDtos.ResolvedUserEntitlements.class);
        var entitlements = mock(VipDtos.VipEntitlements.class);
        when(vip.resolveForUser(ownerId)).thenReturn(freePlan);
        when(freePlan.planCode()).thenReturn("FREE");
        when(freePlan.entitlements()).thenReturn(entitlements);
        when(entitlements.maxForeshadowings()).thenReturn(20);
        when(clues.selectCount(any())).thenReturn(20L);
        lore.setVipEntitlementService(vip);
        assertThatThrownBy(() -> lore.upsertForeshadowing(ownerId, bookId,
                new LoreDtos.UpsertForeshadowingRequest("超额伏笔", "", null, null, "OPEN")))
                .isInstanceOfSatisfying(BizException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(error.getCode()).isEqualTo("VIP_QUOTA_EXCEEDED");
                });
        lore.upsertForeshadowing(ownerId, bookId, JSON.readValue("""
                {"id":"%s","title":"额度已满时仍可更新"}
                """.formatted(legacy.getId()), LoreDtos.UpsertForeshadowingRequest.class));
        assertThat(legacy.getTitle()).isEqualTo("额度已满时仍可更新");
    }

    @Test
    void startAndResumeForwardDirectorPacksAndPersistRunPov() {
        var runs = mock(AgentRunMapper.class);
        var lore = mock(LoreService.class);
        var story = storyMemory(lore);
        BookEntity book = book();
        ChapterEntity chapter = chapter(12);
        ChapterSectionEntity section = section(chapter, 1, "");
        section.setPovCharacter("苏晚");
        when(chapters.selectById(chapter.getId())).thenReturn(chapter);
        when(sections.selectById(section.getId())).thenReturn(section);
        when(books.requireOwnedBook(ownerId, bookId)).thenReturn(book);
        var clue = foreshadowing("#F01", "瓷盒", "盒盖裂开", "瓷盒是空的", null, "", "", null);
        when(lore.listForeshadowings(ownerId, bookId, null)).thenReturn(List.of(clue));
        List<String> paths = new ArrayList<>();
        List<JsonNode> bodies = new ArrayList<>();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            var wire = new MockClientHttpRequest(request.method(), request.url());
            return request.writeTo(wire, ExchangeStrategies.withDefaults())
                    .then(Mono.defer(wire::getBodyAsString)).map(body -> {
                        paths.add(request.url().getPath());
                        bodies.add(JSON.readTree(body));
                        return ClientResponse.create(HttpStatus.ACCEPTED).build();
                    });
        }).build();
        var service = new AgentRunService(runs, mock(AgentCheckpointMapper.class), books, chapters, client,
                mock(StringRedisTemplate.class), mock(RedisRateLimiterService.class), mock(BillingService.class),
                CircuitBreaker.ofDefaults("director"), Bulkhead.ofDefaults("director"), story);
        service.setChapterSectionMapper(sections);

        var started = service.startRun(ownerId, new RunDtos.StartRunRequest(bookId, chapter.getId(), "MULTI_AGENT",
                "CONTINUE", null, "", null, true, section.getId(), " 林青 ",
                List.of(new RunDtos.ForeshadowingDirective(clue.id(), "PLANT", null))));
        var run = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(runs).insert(run.capture());
        assertThat(run.getValue().getPovCharacter()).isEqualTo("林青");
        assertThat(run.getValue().getForeshadowingDirectivesJson()).contains(clue.id().toString(), "PLANT");
        run.getValue().setStatus("PAUSED");
        when(runs.selectById(started.id())).thenReturn(run.getValue());
        service.resumeRun(ownerId, started.id(), new RunDtos.ResumeRunRequest("继续"));

        assertThat(paths).containsExactly("/internal/v1/runs/start", "/internal/v1/runs/" + started.id() + "/resume");
        assertThat(bodies).hasSize(2).allSatisfy(body -> {
            assertThat(body.get("pov_character").asString()).isEqualTo("林青");
            assertThat(body.get("foreshadowing_writer_pack").asString()).contains("PLANT", "盒盖裂开").doesNotContain("瓷盒是空的");
            assertThat(body.get("foreshadowing_critic_pack").asString()).contains("瓷盒是空的");
            assertThat(body.get("critic_guard_brief").asString()).contains("当前视角约束：林青");
        });
    }

    private StoryMemoryContextService storyMemory(LoreService lore) {
        var service = new StoryMemoryContextService(books, mock(BookMapper.class), chapters, mock(ChapterSummaryMapper.class),
                mock(VolumeSummaryMapper.class), lore, relations, mock(OutboxService.class));
        service.setChapterSectionMapper(sections);
        service.setSectionSummaryMapper(summaries);
        service.setStoryRollupSummaryMapper(mock(StoryRollupSummaryMapper.class));
        service.setCharacterStateSnapshotMapper(snapshots);
        service.setTimelineEventMapper(timeline);
        return service;
    }

    private LoreService realLore(CharacterMapper characters, ForeshadowingMapper clues) {
        var lore = new LoreService(books, characters, mock(WorldEntryMapper.class), clues, chapters, mock(PromptTemplateMapper.class));
        lore.setChapterSectionMapper(sections);
        return lore;
    }

    private BookEntity book() {
        var book = new BookEntity();
        book.setId(bookId);
        book.setOwnerId(ownerId);
        book.setCharterSource("AUTHOR_DEFINED");
        book.setAuthorCharter("仙侠故事");
        return book;
    }

    private ChapterEntity chapter(int chapterNo) {
        var chapter = new ChapterEntity();
        chapter.setId(UUID.randomUUID());
        chapter.setBookId(bookId);
        chapter.setChapterNo(chapterNo);
        chapter.setTitle("第" + chapterNo + "章");
        return chapter;
    }

    private ChapterSectionEntity section(ChapterEntity chapter, int sectionNo, String content) {
        var section = new ChapterSectionEntity();
        section.setId(UUID.randomUUID());
        section.setBookId(bookId);
        section.setChapterId(chapter.getId());
        section.setChapterNo(chapter.getChapterNo());
        section.setSectionNo(sectionNo);
        section.setNarrativeOrder(StructureService.narrativeOrder(chapter.getChapterNo(), sectionNo));
        section.setContent(content);
        section.setContentHash(StoryMemoryContextService.sha256Hex(content));
        return section;
    }

    private LoreDtos.CharacterResponse character(String name, String aliases, String lifecycle, String realm, String inventory) {
        return new LoreDtos.CharacterResponse(UUID.randomUUID(), bookId, name,
                "CORE_PINNED".equals(lifecycle) ? "PROTAGONIST" : "SUPPORTING", "", realm, "", "", inventory,
                null, aliases, lifecycle, 60_001L, "", List.of());
    }

    private LoreDtos.ForeshadowingResponse foreshadowing(String code, String title, String surface, String truth,
                                                         ChapterSectionEntity planted, String quote, String cast, Integer due) {
        return new LoreDtos.ForeshadowingResponse(UUID.randomUUID(), bookId, title, truth,
                planted == null ? null : planted.getChapterNo(), null, "PLANTED", null, code, "SUBPLOT", "HIDE_TRUTH",
                surface, truth, planted == null ? null : planted.getId(), planted == null ? null : planted.getNarrativeOrder(),
                quote, null, due, null, cast, "[]", "AUTHOR", false);
    }

    private CharacterEntity characterRow(String name, String lifecycle) {
        var row = new CharacterEntity();
        row.setId(UUID.randomUUID());
        row.setBookId(bookId);
        row.setName(name);
        row.setLifecycleStatus(lifecycle);
        row.setRoleType("SUPPORTING");
        return row;
    }

    private ForeshadowingEntity clueRow(String code, String title, String status) {
        var row = new ForeshadowingEntity();
        row.setId(UUID.randomUUID());
        row.setBookId(bookId);
        row.setCode(code);
        row.setTitle(title);
        row.setStatus(status);
        return row;
    }

    private static TimelineEventEntity event(String title, String knownBy) {
        var row = new TimelineEventEntity();
        row.setChapterNo(11);
        row.setEventTitle(title);
        row.setKnownBy(knownBy);
        return row;
    }

    private boolean extractedFromSection(Wrapper<?> query, String sectionColumn, UUID sectionId) {
        if (query == null) return false;
        String sql = query.getSqlSegment();
        var parameters = ((AbstractWrapper<?, ?, ?>) query).getParamNameValuePairs().values();
        return sql.contains(sectionColumn + " =") && sql.contains("source =") && sql.contains("book_id =")
                && parameters.containsAll(List.of(bookId, sectionId, "AI_EXTRACTED")) && !parameters.contains("AUTHOR");
    }
}
