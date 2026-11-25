package studio.foliarc.admin.service;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import studio.foliarc.biz.billing.ModelPricing;
import studio.foliarc.biz.billing.dto.BillingDtos;
import studio.foliarc.biz.billing.entity.BillingLedgerEntryEntity;
import studio.foliarc.biz.billing.mapper.BillingLedgerEntryMapper;
import studio.foliarc.biz.billing.service.BillingService;

@Service
public class AdminReportingService {

    private final JdbcClient jdbc;
    private final BillingLedgerEntryMapper ledgerMapper;

    public AdminReportingService(JdbcClient jdbc, BillingLedgerEntryMapper ledgerMapper) {
        this.jdbc = jdbc;
        this.ledgerMapper = ledgerMapper;
    }

    public Overview overview() {
        return jdbc.sql("""
                SELECT (SELECT COUNT(*) FROM users) AS total_users,
                       (SELECT COUNT(*) FROM books) AS total_books,
                       (SELECT COUNT(*) FROM agent_runs) AS total_runs,
                       (SELECT COUNT(*) FROM outbox_events WHERE status = 'FAILED') AS failed_outbox_events,
                       (SELECT COUNT(*) FROM knowledge_documents
                        WHERE deleted_at IS NULL AND status IN ('QUEUED', 'PARSING', 'CHUNKING', 'EMBEDDING'))
                           AS pending_documents,
                       (SELECT COUNT(*) FROM ripple_jobs) AS total_ripple_jobs,
                       (SELECT COUNT(*) FROM memory_proposals WHERE status = 'PENDING') AS pending_proposals,
                       (SELECT COUNT(*) FROM guardrail_audit_logs WHERE action = 'BLOCK') AS guardrail_blocks_count
                """).query(Overview.class).single();
    }

    public List<AdminUser> latestUsers() {
        return jdbc.sql("""
                SELECT u.id, u.email, u.username, u.role, u.created_at,
                       COALESCE(s.plan_code, 'FREE') AS plan_code,
                       COALESCE(b.balance_micros, 0) AS balance_micros
                FROM users u
                LEFT JOIN user_subscriptions s ON s.user_id = u.id AND UPPER(s.status) = 'ACTIVE'
                     AND (s.expires_at IS NULL OR s.expires_at > CURRENT_TIMESTAMP)
                LEFT JOIN billing_accounts b ON b.user_id = u.id
                ORDER BY u.created_at DESC, u.id DESC
                LIMIT 50
                """).query((rs, rowNum) -> new AdminUser(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("username"),
                rs.getString("role"),
                rs.getString("plan_code"),
                ModelPricing.toCredits(rs.getLong("balance_micros")),
                rs.getObject("created_at", OffsetDateTime.class)
        )).list();
    }

    public List<RippleJobRow> recentRippleJobs(int limit) {
        return jdbc.sql("""
                SELECT j.id, j.book_id, j.owner_id AS user_id, j.source_chapter_no,
                       j.range_start_chapter AS range_start_chapter_no, j.range_end_chapter AS range_end_chapter_no,
                       j.status, j.candidate_section_count AS total_candidates, j.created_at, j.updated_at,
                       (SELECT COUNT(*) FROM ripple_impacts i WHERE i.job_id = j.id AND i.patch_status = 'APPLIED')
                           AS patched_count
                FROM ripple_jobs j
                ORDER BY j.created_at DESC, j.id DESC
                LIMIT :limit
                """).param("limit", limit).query(RippleJobRow.class).list();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BillingOverview billingOverview() {
        AccountTotals accounts = jdbc.sql("""
                SELECT COUNT(*) AS total_accounts,
                       COALESCE(SUM(balance_micros), 0) AS balance_micros,
                       COALESCE(SUM(monthly_spent_micros) FILTER (WHERE budget_month = :month), 0) AS spent_micros
                FROM billing_accounts
                """).param("month", YearMonth.now().toString()).query(AccountTotals.class).single();

        List<BillingDtos.ModelCostStat> models = jdbc.sql("""
                SELECT COALESCE(NULLIF(BTRIM(model_alias), ''), 'writer-main') AS model,
                       COUNT(*) AS runs, SUM(amount_micros) AS spent_micros,
                       SUM(input_tokens) AS input_tokens, SUM(cached_tokens) AS cached_tokens,
                       SUM(output_tokens) AS output_tokens, SUM(cache_saved_micros) AS cache_saved_micros
                FROM billing_ledger_entries
                WHERE entry_type = 'SETTLE'
                GROUP BY 1
                ORDER BY spent_micros DESC, model ASC
                """).query((rs, rowNum) -> {
            long spentMicros = rs.getLong("spent_micros");
            return new BillingDtos.ModelCostStat(
                    rs.getString("model"), rs.getLong("runs"), spentMicros, ModelPricing.toCredits(spentMicros),
                    rs.getLong("input_tokens"), rs.getLong("cached_tokens"), rs.getLong("output_tokens"),
                    rs.getLong("cache_saved_micros"));
        }).list();
        long cacheSavedMicros = models.stream().mapToLong(BillingDtos.ModelCostStat::totalCacheSavedMicros).sum();

        List<AdminLedgerEntry> recentLedger = ledgerMapper.selectList(Wrappers.<BillingLedgerEntryEntity>lambdaQuery()
                        .orderByDesc(BillingLedgerEntryEntity::getCreatedAt, BillingLedgerEntryEntity::getId)
                        .last("LIMIT 50"))
                .stream().map(entry -> new AdminLedgerEntry(entry.getUserId(), BillingService.toLedgerResponse(entry)))
                .toList();
        return new BillingOverview(accounts.totalAccounts(), ModelPricing.toCredits(accounts.balanceMicros()),
                ModelPricing.toCredits(accounts.spentMicros()), ModelPricing.toCredits(cacheSavedMicros),
                models, recentLedger);
    }

    public record Overview(long totalUsers, long totalBooks, long totalRuns, long failedOutboxEvents,
                           long pendingDocuments, long totalRippleJobs, long pendingProposals,
                           long guardrailBlocksCount) {}

    public record AdminUser(UUID id, String email, String username, String role, String planCode,
                            double balanceCredits, OffsetDateTime createdAt) {}

    public record RippleJobRow(UUID id, UUID bookId, UUID userId, int sourceChapterNo, int rangeStartChapterNo,
                               int rangeEndChapterNo, String status, int totalCandidates, long patchedCount,
                               OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    public record BillingOverview(long totalAccounts, double totalBalanceCredits, double totalMonthlySpentCredits,
                                  double totalCacheSavedCredits, List<BillingDtos.ModelCostStat> modelBreakdown,
                                  List<AdminLedgerEntry> recentLedger) {}

    public record AdminLedgerEntry(UUID userId, @JsonUnwrapped BillingDtos.LedgerEntryResponse entry) {}

    private record AccountTotals(long totalAccounts, long balanceMicros, long spentMicros) {}
}
