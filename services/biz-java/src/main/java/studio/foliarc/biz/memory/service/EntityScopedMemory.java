package studio.foliarc.biz.memory.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import studio.foliarc.biz.lore.CharacterState;
import studio.foliarc.biz.lore.LoreNames;
import studio.foliarc.biz.lore.dto.LoreDtos.CharacterResponse;
import studio.foliarc.biz.lore.dto.LoreDtos.ForeshadowingResponse;
import studio.foliarc.biz.lore.service.LoreService;
import studio.foliarc.biz.memory.entity.CharacterRelationEntity;
import studio.foliarc.biz.memory.entity.CharacterStateSnapshotEntity;
import studio.foliarc.biz.memory.entity.TimelineEventEntity;
import studio.foliarc.biz.memory.mapper.CharacterRelationMapper;
import studio.foliarc.biz.memory.mapper.CharacterStateSnapshotMapper;
import studio.foliarc.biz.memory.mapper.TimelineEventMapper;
import studio.foliarc.biz.novel.mapper.ChapterSectionMapper;
import studio.foliarc.biz.run.dto.RunDtos.ForeshadowingDirective;

final class EntityScopedMemory {

    private static final String BEHIND_THE_SCENES = "【幕后客观事实（当前视角尚不知情，仅防世界观穿帮，严禁主角凭空知晓！）】";

    private final LoreService lore;
    private final CharacterRelationMapper relations;
    private final TimelineEventMapper timeline;
    private final CharacterStateSnapshotMapper snapshots;
    private final ChapterSectionMapper sections;

    EntityScopedMemory(LoreService lore, CharacterRelationMapper relations, TimelineEventMapper timeline,
                       CharacterStateSnapshotMapper snapshots, ChapterSectionMapper sections) {
        this.lore = lore;
        this.relations = relations;
        this.timeline = timeline;
        this.snapshots = snapshots;
        this.sections = sections;
    }

    record Brief(String activeState, String writerPack, String criticPack, String criticGuard) {}

    private record CastMember(CharacterResponse character, CharacterState state) {}

    private record Fact(String line, String knownBy) {}

    Brief assemble(UUID ownerId, UUID bookId, int chapterNo, long order, String probe, String pov,
                   List<ForeshadowingDirective> directives) {
        List<CharacterResponse> characters = lore.listCharacters(ownerId, bookId, null);
        boolean historical = order > 0 && snapshots != null && sections != null
                && order < sections.findMaxNarrativeOrder(bookId);
        Map<UUID, CharacterState> priorStates = historical ? historicalStates(bookId, order, characters) : Map.of();
        List<CastMember> mentioned = new ArrayList<>();
        List<CastMember> pinned = new ArrayList<>();
        List<CastMember> fallback = new ArrayList<>();
        List<String> deceased = new ArrayList<>();
        for (CharacterResponse character : characters) {
            CharacterState state = historical ? priorStates.get(character.id()) : CharacterState.of(character);
            String lifecycle = state == null ? "" : state.lifecycleStatus();
            if ("DEAD".equals(lifecycle)) {
                deceased.add(character.name());
                continue;
            }
            CastMember member = new CastMember(character, state);
            if (names(character).anyMatch(probe::contains)) {
                mentioned.add(member);
            } else if (!"DORMANT".equals(lifecycle)) {
                if ("CORE_PINNED".equals(lifecycle) || state != null && "PROTAGONIST".equals(state.roleType())) {
                    pinned.add(member);
                } else if (fallback.size() < 6) {
                    fallback.add(member);
                }
            }
        }
        List<CastMember> cast = mentioned.isEmpty() && pinned.isEmpty() ? fallback
                : Stream.concat(mentioned.stream(), pinned.stream()).limit(10).toList();
        Set<String> castNames = cast.stream().map(CastMember::character)
                .flatMap(EntityScopedMemory::names).collect(Collectors.toCollection(LinkedHashSet::new));
        List<ForeshadowingResponse> clues = lore.listForeshadowings(ownerId, bookId, directives.isEmpty() ? "OPEN" : null);
        ForeshadowingDirector.Packs packs = ForeshadowingDirector.assemble(
                bookId, chapterNo, order, castNames, clues, directives, sections);

        StringBuilder active = new StringBuilder();
        appendCast(active, cast);
        List<Fact> relationFacts = relationFacts(bookId, chapterNo, order, castNames);
        List<Fact> timelineFacts = timelineFacts(bookId, chapterNo, order);
        if (pov.isBlank()) {
            appendFacts(active, "【相关人物关系】", relationFacts);
            appendFacts(active, "【相关时间线】", timelineFacts);
        } else {
            Set<String> povNames = povNames(pov, characters);
            Map<Boolean, List<Fact>> partition = Stream.concat(relationFacts.stream(), timelineFacts.stream())
                    .collect(Collectors.partitioningBy(fact -> knows(fact.knownBy(), povNames)));
            appendFacts(active, "【当前视角（" + pov + "）已知事实】", partition.get(true));
            appendFacts(active, BEHIND_THE_SCENES, partition.get(false));
        }
        if (!packs.latentWriter().isBlank()) active.append('\n').append(packs.latentWriter());
        StringBuilder guard = new StringBuilder();
        if (!pov.isBlank()) guard.append("当前视角约束：").append(pov).append('\n');
        if (!deceased.isEmpty()) guard.append("已亡角色红线：").append(String.join("、", deceased)).append('\n');
        return new Brief(active.toString().strip(), packs.writer(), packs.critic(), guard.toString().strip());
    }

    private Map<UUID, CharacterState> historicalStates(UUID bookId, long order, List<CharacterResponse> characters) {
        if (characters.isEmpty()) return Map.of();
        // One row per character, with the most recent state at or before the target section.
        return snapshots.selectList(new QueryWrapper<CharacterStateSnapshotEntity>()
                        .select("DISTINCT ON (character_id) character_id", "snapshot_json")
                        .eq("book_id", bookId)
                        .in("character_id", characters.stream().map(CharacterResponse::id).toList())
                        .le("narrative_order", order)
                        .orderByAsc("character_id")
                        .orderByDesc("narrative_order", "created_at", "id"))
                .stream().filter(row -> row.getSnapshotJson() != null && !row.getSnapshotJson().isBlank())
                .collect(Collectors.toMap(CharacterStateSnapshotEntity::getCharacterId,
                        row -> CharacterState.readSnapshot(row.getSnapshotJson()), (first, ignored) -> first));
    }

    private List<Fact> relationFacts(UUID bookId, int chapterNo, long order, Set<String> castNames) {
        var query = new LambdaQueryWrapper<CharacterRelationEntity>()
                .eq(CharacterRelationEntity::getBookId, bookId)
                .and(w -> w.isNull(CharacterRelationEntity::getSinceChapterNo)
                        .or().le(CharacterRelationEntity::getSinceChapterNo, chapterNo))
                .le(order > 0, CharacterRelationEntity::getNarrativeOrder, order)
                .orderByDesc(CharacterRelationEntity::getUpdatedAt);
        List<CharacterRelationEntity> rows = relations.selectList(query);
        if (rows == null) return List.of();
        return rows.stream().filter(row -> castNames.isEmpty()
                        || castNames.contains(row.getSourceCharacter()) || castNames.contains(row.getTargetCharacter()))
                .limit(8).map(row -> new Fact(
                        row.getSourceCharacter() + " -[" + row.getRelationType() + "]-> " + row.getTargetCharacter()
                                + detail(row.getDescription()),
                        row.getKnownBy())).toList();
    }

    private List<Fact> timelineFacts(UUID bookId, int chapterNo, long order) {
        if (timeline == null) return List.of();
        return timeline.selectList(new LambdaQueryWrapper<TimelineEventEntity>()
                        .eq(TimelineEventEntity::getBookId, bookId)
                        .le(TimelineEventEntity::getChapterNo, chapterNo)
                        .le(order > 0, TimelineEventEntity::getNarrativeOrder, order)
                        .orderByDesc(TimelineEventEntity::getChapterNo, TimelineEventEntity::getNarrativeOrder,
                                TimelineEventEntity::getCreatedAt)
                        .last("LIMIT 8"))
                .stream().map(row -> new Fact("第" + row.getChapterNo() + "章 " + row.getEventTitle()
                        + detail(row.getSummary()), row.getKnownBy())).toList();
    }

    private static Set<String> povNames(String pov, List<CharacterResponse> characters) {
        CharacterResponse canonical = characters.stream().filter(c -> pov.equals(c.name())).findFirst()
                .orElseGet(() -> characters.stream().filter(c -> LoreNames.split(c.aliases()).contains(pov)).findFirst().orElse(null));
        return canonical == null ? Set.of(pov) : names(canonical).collect(Collectors.toSet());
    }

    private static Stream<String> names(CharacterResponse character) {
        return Stream.concat(Stream.of(character.name()), LoreNames.split(character.aliases()).stream())
                .filter(name -> name != null && !name.isBlank());
    }

    private static boolean knows(String knownBy, Set<String> povNames) {
        String access = LoreNames.normalizeKnownBy(knownBy);
        return "PUBLIC".equals(access) || LoreNames.split(access).stream().anyMatch(povNames::contains);
    }

    private static void appendCast(StringBuilder active, List<CastMember> cast) {
        if (cast.isEmpty()) return;
        active.append("【当前活跃人物状态】\n");
        for (CastMember member : cast) {
            active.append("- ").append(member.character().name());
            CharacterState state = member.state();
            if (state != null) {
                List<String> attributes = new ArrayList<>();
                addAttribute(attributes, "境界", state.realmOrLevel());
                addAttribute(attributes, "位置", state.currentLocation());
                addAttribute(attributes, "物品", state.inventory());
                addAttribute(attributes, "状态", state.statusNotes());
                if (!attributes.isEmpty()) active.append("（").append(String.join("；", attributes)).append("）");
            }
            active.append('\n');
        }
    }

    private static void addAttribute(List<String> attributes, String label, String text) {
        if (text != null && !text.isBlank()) attributes.add(label + "：" + text);
    }

    private static void appendFacts(StringBuilder active, String heading, List<Fact> facts) {
        if (facts.isEmpty()) return;
        active.append('\n').append(heading).append('\n');
        facts.forEach(fact -> active.append("- ").append(fact.line()).append('\n'));
    }

    private static String detail(String text) {
        return text == null || text.isBlank() ? "" : "：" + text;
    }
}
