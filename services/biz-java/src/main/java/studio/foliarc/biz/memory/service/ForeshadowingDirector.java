package studio.foliarc.biz.memory.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.lore.LoreNames;
import studio.foliarc.biz.lore.dto.LoreDtos.ForeshadowingResponse;
import studio.foliarc.biz.lore.service.LoreService;
import studio.foliarc.biz.novel.entity.ChapterSectionEntity;
import studio.foliarc.biz.novel.mapper.ChapterSectionMapper;
import studio.foliarc.biz.run.dto.RunDtos.ForeshadowingDirective;

final class ForeshadowingDirector {

    private static final String DIRECTIVES_HEADING = "【本章强制伏笔导演包（最高优先级）】";
    private static final String LATENT_HEADING = "【潜伏伏笔】";
    private static final Set<String> DIRECTIVE_MODES = Set.of("PLANT", "HINT", "PAYOFF");
    private static final Set<String> RESOLVE_TYPES = Set.of("FULL_RESOLVE", "PARTIAL_REVEAL");

    private ForeshadowingDirector() {
    }

    record Packs(String writer, String critic, String latentWriter) {}

    static Packs assemble(UUID bookId, int chapterNo, long currentOrder, Set<String> castNames,
                          List<ForeshadowingResponse> clues, List<ForeshadowingDirective> directives,
                          ChapterSectionMapper sections) {
        StringBuilder writer = new StringBuilder();
        StringBuilder critic = new StringBuilder();
        Set<UUID> directed = new LinkedHashSet<>();
        if (!directives.isEmpty()) {
            writer.append(DIRECTIVES_HEADING).append('\n');
            critic.append(DIRECTIVES_HEADING).append('\n');
        }
        for (ForeshadowingDirective directive : directives) {
            if (directive == null || directive.foreshadowingId() == null) {
                throw BizException.badRequest("INVALID_FORESHADOWING_DIRECTIVE", "导演指令必须指定伏笔");
            }
            String mode = safe(directive.mode()).strip().toUpperCase(Locale.ROOT);
            if (!DIRECTIVE_MODES.contains(mode)) {
                throw BizException.badRequest("INVALID_FORESHADOWING_DIRECTIVE", "无效导演指令: " + mode);
            }
            ForeshadowingResponse clue = clues.stream()
                    .filter(row -> directive.foreshadowingId().equals(row.id()))
                    .findFirst().orElseThrow(() -> BizException.notFound("FORESHADOWING_NOT_FOUND", "伏笔记录不存在"));
            directed.add(clue.id());
            String passage = "PLANT".equals(mode) ? "" : recall(bookId, clue, sections);
            String resolveType = "PAYOFF".equals(mode) ? resolveType(directive) : "";
            appendDirective(writer, clue, mode, resolveType, passage, false);
            appendDirective(critic, clue, mode, resolveType, passage, true);
        }

        List<ForeshadowingResponse> active = clues.stream()
                .filter(clue -> LoreService.isActiveForeshadowing(clue.status()) && !directed.contains(clue.id()))
                .filter(clue -> clue.plantedChapterNo() == null || clue.plantedChapterNo() <= chapterNo)
                .filter(clue -> currentOrder <= 0 || clue.plantedNarrativeOrder() == null || clue.plantedNarrativeOrder() <= currentOrder)
                .toList();
        Set<ForeshadowingResponse> latent = active.stream()
                .filter(clue -> LoreNames.split(clue.involvedCharacters()).stream().anyMatch(castNames::contains))
                .limit(4).collect(Collectors.toCollection(LinkedHashSet::new));
        active.stream().filter(clue -> !latent.contains(clue) && LoreService.isDue(clue, chapterNo))
                .sorted(LoreService.duePriority()).limit(4).forEach(latent::add);
        if (latent.isEmpty()) {
            // Backfilled clues may have neither a cast list nor a planned payoff window.
            active.stream().filter(clue -> clue.plantedChapterNo() != null
                    && safe(clue.involvedCharacters()).isBlank() && clue.targetChapterMin() == null && clue.targetChapterMax() == null)
                    .limit(4).forEach(latent::add);
        }

        StringBuilder latentWriter = new StringBuilder();
        if (!latent.isEmpty()) {
            latentWriter.append(LATENT_HEADING).append('\n');
            critic.append(LATENT_HEADING).append('\n');
        }
        for (ForeshadowingResponse clue : latent) {
            boolean hide = clue.revealPolicy() == null || "HIDE_TRUTH".equals(clue.revealPolicy());
            latentWriter.append("- ");
            if (!hide) latentWriter.append(label(clue, false)).append("｜");
            latentWriter.append(clue.writerSurface(clue.surfaceClue()));
            if (hide) latentWriter.append(" 【严禁提前点破真相】");
            latentWriter.append('\n');
            critic.append("- ").append(label(clue, true)).append("｜").append(safe(clue.surfaceClue()));
            if (hide) {
                if (!safe(clue.hiddenTruth()).isBlank()) critic.append("｜底牌：").append(clue.hiddenTruth());
                critic.append(" 【检查是否提前点破真相】");
            }
            critic.append('\n');
        }
        writer.append(latentWriter);
        String legacyTitles = latent.stream().filter(clue -> safe(clue.code()).isBlank() && safe(clue.hiddenTruth()).isBlank())
                .map(ForeshadowingResponse::title).filter(title -> !safe(title).isBlank()).collect(Collectors.joining("、"));
        if (!legacyTitles.isBlank()) latentWriter.append("伏笔索引：").append(legacyTitles);
        return new Packs(writer.toString().strip(), critic.toString().strip(), latentWriter.toString().strip());
    }

    private static void appendDirective(StringBuilder out, ForeshadowingResponse clue, String mode,
                                        String resolveType, String passage, boolean critic) {
        boolean payoff = "PAYOFF".equals(mode);
        boolean seeTruth = critic || payoff;
        out.append("- ").append(mode).append(" ").append(label(clue, seeTruth)).append('\n');
        if (payoff) out.append("  回收方式：").append(resolveType).append('\n');
        out.append("  表面线索：").append(seeTruth ? safe(clue.surfaceClue()) : clue.writerSurface(clue.surfaceClue())).append('\n');
        if (seeTruth && !safe(clue.hiddenTruth()).isBlank()) out.append("  底牌：").append(clue.hiddenTruth()).append('\n');
        if (!passage.isBlank()) {
            out.append("  当年埋设原文：\n").append(seeTruth ? passage : clue.writerSurface(passage)).append('\n');
        }
        if (critic && !"PLANT".equals(mode) && !safe(clue.plantedQuote()).isBlank()) {
            out.append("  埋设原句：").append(clue.plantedQuote()).append('\n');
        }
        String instruction = switch (mode) {
            case "PLANT" -> "自然藏入动作/物件细节，严禁生硬说教或暴露底牌";
            case "HINT" -> "轻点照应即可，严禁提前挑破真相";
            default -> "按回收方式对照埋设原文，确保时间、物件、称谓和细节一致";
        };
        out.append("  ").append(instruction).append('\n');
    }

    private static String label(ForeshadowingResponse clue, boolean seeTruth) {
        return Stream.of(safe(clue.code()), seeTruth ? safe(clue.title()) : clue.writerSurface(clue.title()))
                .filter(part -> !part.isBlank()).collect(Collectors.joining(" "));
    }

    private static String resolveType(ForeshadowingDirective directive) {
        String value = safe(directive.resolveType()).strip().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) return "FULL_RESOLVE";
        if (!RESOLVE_TYPES.contains(value)) {
            throw BizException.badRequest("INVALID_FORESHADOWING_RESOLVE_TYPE", "无效伏笔回收方式: " + value);
        }
        return value;
    }

    private static String recall(UUID bookId, ForeshadowingResponse clue, ChapterSectionMapper sections) {
        if (clue.plantedSectionId() == null || sections == null) return "";
        ChapterSectionEntity section = sections.selectById(clue.plantedSectionId());
        if (section == null || !bookId.equals(section.getBookId())) return "";
        String content = safe(section.getContent());
        String quote = safe(clue.plantedQuote());
        int at = quote.isBlank() ? -1 : content.indexOf(quote);
        if (at < 0) {
            return content.substring(0, content.offsetByCodePoints(0, Math.min(600, content.codePointCount(0, content.length()))));
        }
        int afterQuote = at + quote.length();
        int start = content.offsetByCodePoints(at, -Math.min(400, content.codePointCount(0, at)));
        int end = content.offsetByCodePoints(afterQuote, Math.min(400, content.codePointCount(afterQuote, content.length())));
        return content.substring(start, end);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
