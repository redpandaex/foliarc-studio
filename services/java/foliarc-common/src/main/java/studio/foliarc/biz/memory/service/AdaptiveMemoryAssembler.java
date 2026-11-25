package studio.foliarc.biz.memory.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import studio.foliarc.biz.memory.entity.SectionSummaryEntity;
import studio.foliarc.biz.memory.entity.StoryRollupSummaryEntity;
import studio.foliarc.biz.memory.mapper.StoryRollupSummaryMapper;

final class AdaptiveMemoryAssembler {

    private static final int TOTAL_BUDGET = 4_100;
    private static final int RECENT_BUDGET = 1_600;

    private AdaptiveMemoryAssembler() {}

    static String assemble(
            StoryRollupSummaryMapper mapper, UUID bookId, long currentOrder, String precisionMode,
            String arcGoal, List<SectionSummaryEntity> recent
    ) {
        String recentBlock = renderRecent(recent);
        int tier1Limit = switch (precisionMode) {
            case "EPIC" -> 5;
            case "EXPEDITION" -> 8;
            default -> 28;
        };
        long recentStart = recent.stream().mapToLong(SectionSummaryEntity::getNarrativeOrder).min().orElse(currentOrder);
        List<StoryRollupSummaryEntity> tier1 = loadTier(mapper, bookId, 1, recentStart, tier1Limit);
        long tier1Start = startOf(tier1, recentStart);
        List<StoryRollupSummaryEntity> tier2 = "PRECISE".equals(precisionMode)
                ? List.of() : loadTier(mapper, bookId, 2, tier1Start, 4);
        List<StoryRollupSummaryEntity> tier3 = "EPIC".equals(precisionMode)
                ? loadTier(mapper, bookId, 3, startOf(tier2, tier1Start), 10) : List.of();

        String volume = arcGoal == null || arcGoal.isBlank()
                ? "" : clip("【当前卷纲】\n" + arcGoal.trim(), Math.min(250, remaining(recentBlock)));
        String rolls1 = renderTier(tier1, "【近卷卷摘】", Math.min(1_000, remaining(recentBlock, volume)));
        String rolls2 = renderTier(tier2, "【前篇篇摘】", Math.min(800, remaining(recentBlock, volume, rolls1)));
        String rolls3 = renderTier(tier3, "【纪元编年史】", Math.min(700, remaining(recentBlock, volume, rolls1, rolls2)));
        return join(volume, rolls3, rolls2, rolls1, recentBlock);
    }

    static String assembleLegacy(String older, List<String> recentInOrder) {
        String recent = renderRecentLines("【近期章节摘要】", recentInOrder);
        return join(clip(older, remaining(recent)), recent);
    }

    private static List<StoryRollupSummaryEntity> loadTier(
            StoryRollupSummaryMapper mapper, UUID bookId, int tier, long cutoff, int limit
    ) {
        if (mapper == null) return List.of();
        return mapper.selectList(new LambdaQueryWrapper<StoryRollupSummaryEntity>()
                .select(StoryRollupSummaryEntity::getStartOrder, StoryRollupSummaryEntity::getEndOrder,
                        StoryRollupSummaryEntity::getSeqNo, StoryRollupSummaryEntity::getSummaryText)
                .eq(StoryRollupSummaryEntity::getBookId, bookId)
                .eq(StoryRollupSummaryEntity::getTierLevel, tier)
                .eq(StoryRollupSummaryEntity::getStatus, "READY")
                .lt(StoryRollupSummaryEntity::getEndOrder, cutoff)
                .orderByDesc(StoryRollupSummaryEntity::getEndOrder)
                .last("LIMIT " + limit));
    }

    private static long startOf(List<StoryRollupSummaryEntity> tier, long fallback) {
        return tier.stream().mapToLong(StoryRollupSummaryEntity::getStartOrder).min().orElse(fallback);
    }

    private static String renderRecent(List<SectionSummaryEntity> recent) {
        if (recent.isEmpty()) return "";
        List<SectionSummaryEntity> ordered = recent.stream()
                .sorted(Comparator.comparing(SectionSummaryEntity::getNarrativeOrder)).toList();
        List<String> labelled = ordered.stream()
                .map(s -> "- 第 " + s.getChapterNo() + " 章 / 第 " + s.getSectionNo() + " 节：" + s.getSummaryText())
                .toList();
        String detailed = "【近期小节摘要】\n" + String.join("\n", labelled);
        if (detailed.length() <= RECENT_BUDGET) return detailed;

        // Spend the recent budget on complete summary bodies before adding labels or older rollups.
        List<String> bodies = ordered.stream().map(SectionSummaryEntity::getSummaryText).toList();
        return renderRecentLines("【近期小节摘要】", bodies);
    }

    private static String renderRecentLines(String heading, List<String> inOrder) {
        if (inOrder.isEmpty()) return "";
        String all = String.join("\n", inOrder);
        if (all.length() + heading.length() + 1 <= TOTAL_BUDGET) return heading + "\n" + all;
        if (all.length() <= TOTAL_BUDGET) return all;

        // An oversized legacy window can exceed the entire prompt. Preserve complete entries from newest to oldest.
        List<String> fitting = new ArrayList<>();
        int left = TOTAL_BUDGET;
        for (String line : inOrder.reversed()) {
            int length = line.length() + (fitting.isEmpty() ? 0 : 1);
            if (length > left) break;
            fitting.add(line);
            left -= length;
        }
        return String.join("\n", fitting.reversed());
    }

    private static String renderTier(List<StoryRollupSummaryEntity> rows, String heading, int budget) {
        int left = budget - heading.length() - 1;
        if (rows.isEmpty() || left <= 0) return "";
        List<String> entries = new ArrayList<>();
        for (StoryRollupSummaryEntity row : rows) {
            String label = "- " + row.getSeqNo() + "：";
            if (left <= label.length()) break;
            int remainingRows = rows.size() - entries.size();
            int share = Math.max(label.length() + 1, left / remainingRows - 1);
            String line = label + clip(row.getSummaryText().trim(), Math.min(share, left) - label.length());
            entries.add(line);
            left -= line.length() + 1;
        }
        return entries.isEmpty() ? "" : heading + "\n" + String.join("\n", entries.reversed());
    }

    private static int remaining(String... parts) {
        int left = TOTAL_BUDGET;
        for (String part : parts) {
            if (!part.isEmpty()) left -= part.length() + 2;
        }
        return Math.max(0, left);
    }

    private static String clip(String text, int maxChars) {
        if (maxChars <= 0) return "";
        if (text.length() <= maxChars) return text;
        int end = maxChars - 1;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "…";
    }

    private static String join(String... parts) {
        return String.join("\n\n", Arrays.stream(parts).filter(part -> !part.isEmpty()).toList());
    }
}
