package studio.foliarc.biz.billing;

import java.util.Map;

/**
 * Integer micro-credit pricing model (1 Credit = 1,000,000 micros) eliminating floating-point drift.
 * Explicitly models Prompt Cache hit discounts (~90% off cached input tokens) and sandbox calls.
 */
public final class ModelPricing {

    public static final long MICROS_PER_CREDIT = 1_000_000L;
    public static final long SANDBOX_CALL_MICROS = 5_000L;

    public record Rate(long inputPer1K, long cachedPer1K, long outputPer1K) {}

    private static final Map<String, Rate> RATES = Map.of(
            "writer-main", new Rate(2_000L, 200L, 8_000L),
            "critic-reasoner", new Rate(4_000L, 1_000L, 16_000L),
            "fast-router", new Rate(1_000L, 100L, 3_000L)
    );

    private ModelPricing() {}

    public static Rate rateFor(String modelAlias) {
        if (modelAlias == null) {
            return RATES.get("writer-main");
        }
        return RATES.getOrDefault(modelAlias.trim(), RATES.get("writer-main"));
    }

    public static long estimatePreFreezeMicros(String mode, String modelAlias) {
        long base = "MULTI_AGENT".equalsIgnoreCase(mode) ? 350_000L : 150_000L;
        if ("critic-reasoner".equalsIgnoreCase(modelAlias)) {
            base += 150_000L;
        }
        return base;
    }

    public static long estimatePromptTokens(String mode, String chapterContext, String instruction) {
        int chars = (chapterContext == null ? 0 : chapterContext.length())
                + (instruction == null ? 0 : instruction.length());
        long base = Math.max(800L, chars / 2L + 600L);
        return "MULTI_AGENT".equalsIgnoreCase(mode) ? base * 3L : base;
    }

    public static CostBreakdown calculateActualCost(
            String modelAlias,
            int inputTokens,
            int cachedTokens,
            int outputTokens,
            int sandboxCalls
    ) {
        Rate r = rateFor(modelAlias);
        int safeInput = Math.max(0, inputTokens);
        int safeCached = Math.max(0, Math.min(cachedTokens, safeInput));
        int uncachedInput = safeInput - safeCached;
        int safeOutput = Math.max(0, outputTokens);
        int safeSandbox = Math.max(0, sandboxCalls);

        long inputCost = (uncachedInput * r.inputPer1K() + 999L) / 1000L;
        long cachedCost = (safeCached * r.cachedPer1K() + 999L) / 1000L;
        long outputCost = (safeOutput * r.outputPer1K() + 999L) / 1000L;
        long sandboxCost = safeSandbox * SANDBOX_CALL_MICROS;

        long uncachedHypothetical = (safeCached * r.inputPer1K() + 999L) / 1000L;
        long cacheSaved = Math.max(0L, uncachedHypothetical - cachedCost);
        long total = inputCost + cachedCost + outputCost + sandboxCost;
        return new CostBreakdown(total, cacheSaved);
    }

    public static double toCredits(long micros) {
        return Math.round((micros / (double) MICROS_PER_CREDIT) * 10000.0) / 10000.0;
    }

    public record CostBreakdown(long totalMicros, long cacheSavedMicros) {}
}
