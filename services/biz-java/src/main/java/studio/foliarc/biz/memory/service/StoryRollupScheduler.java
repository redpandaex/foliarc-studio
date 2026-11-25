package studio.foliarc.biz.memory.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import studio.foliarc.biz.memory.entity.SectionSummaryEntity;
import studio.foliarc.biz.memory.entity.StoryRollupSummaryEntity;
import studio.foliarc.biz.memory.mapper.SectionSummaryMapper;
import studio.foliarc.biz.memory.mapper.StoryRollupSummaryMapper;
import studio.foliarc.biz.novel.entity.BookEntity;
import studio.foliarc.biz.novel.entity.VolumeEntity;
import studio.foliarc.biz.outbox.RabbitMqTopologyConfig;
import studio.foliarc.biz.outbox.service.OutboxService;

final class StoryRollupScheduler {

    private static final int SECTIONS_PER_VOLUME_SUMMARY = 10;
    private static final int VOLUME_SUMMARIES_PER_ARC = 10;
    private static final int ARCS_PER_EPOCH = 5;
    // The worker skips the rollup stage for tiers outside 1–3 and still processes the charter flag.
    private static final int CHARTER_ONLY = -1;

    private final SectionSummaryMapper sections;
    private final StoryRollupSummaryMapper rollups;
    private final OutboxService outbox;

    StoryRollupScheduler(SectionSummaryMapper sections, StoryRollupSummaryMapper rollups, OutboxService outbox) {
        this.sections = sections;
        this.rollups = rollups;
        this.outbox = outbox;
    }

    boolean afterDistill(BookEntity book, long sectionOrder, VolumeEntity volume, long totalReady, boolean refreshCharter) {
        List<StoryRollupSummaryEntity> stale = rollups.selectList(inTier(book.getId(), 1)
                .eq(StoryRollupSummaryEntity::getStatus, "STALE")
                .le(StoryRollupSummaryEntity::getStartOrder, sectionOrder)
                .ge(StoryRollupSummaryEntity::getEndOrder, sectionOrder)
                .orderByAsc(StoryRollupSummaryEntity::getSeqNo));
        boolean queued = false;
        for (StoryRollupSummaryEntity row : stale) {
            List<SectionSummaryEntity> inputs = sections.selectList(readySections(book.getId())
                    .between(SectionSummaryEntity::getNarrativeOrder, row.getStartOrder(), row.getEndOrder())
                    .orderByAsc(SectionSummaryEntity::getNarrativeOrder));
            if (!inputs.isEmpty()) {
                enqueue(book, 1, row.getSeqNo(), row.getVolumeId(), row.getStartOrder(), row.getEndOrder(),
                        row.getTitle(), sectionFingerprint(inputs), refreshCharter && !queued);
                queued = true;
            }
        }
        if (totalReady >= SECTIONS_PER_VOLUME_SUMMARY || isCompleted(volume)) {
            queued = enqueueNextVolume(book, volume, refreshCharter && !queued) || queued;
        }
        if (!queued && refreshCharter) {
            int lastCount = book.getCharterChapterCount() == null ? 0 : book.getCharterChapterCount();
            long milestone = lastCount == 0 && totalReady < 3 ? 1
                    : lastCount < 3 && totalReady >= 3 ? 3 : (long) lastCount + SECTIONS_PER_VOLUME_SUMMARY;
            enqueue(book, CHARTER_ONLY, 0, null, 0, 0, "", String.valueOf(milestone), true);
            queued = true;
        }
        return queued;
    }

    void afterRollupSaved(BookEntity book, StoryRollupSummaryEntity saved) {
        int tier = saved.getTierLevel();
        if (tier == 1) {
            enqueueNextVolume(book, null, false);
        }
        if (tier >= 3) return;

        // The worker groups prior-tier nodes by their position in the sequence, including migrated rows.
        int groupSize = tier == 1 ? VOLUME_SUMMARIES_PER_ARC : ARCS_PER_EPOCH;
        long position = rollups.selectCount(inTier(book.getId(), tier).le(StoryRollupSummaryEntity::getSeqNo, saved.getSeqNo()));
        if (position == 0) return;
        long groupStart = ((position - 1) / groupSize) * groupSize;
        List<StoryRollupSummaryEntity> inputs = rollups.selectList(inTier(book.getId(), tier)
                .orderByAsc(StoryRollupSummaryEntity::getSeqNo)
                .last("LIMIT " + groupSize + " OFFSET " + groupStart));
        if (inputs.size() != groupSize || inputs.stream().anyMatch(row -> !"READY".equals(row.getStatus()))) return;
        String fingerprint = inputs.stream().map(row -> row.getId() + ":" + row.getSourceHash() + ":" + row.getUpdatedAt())
                .collect(Collectors.joining("\n"));
        int seqNo = Math.toIntExact(groupStart / groupSize + 1);
        enqueue(book, tier + 1, seqNo, null, inputs.getFirst().getStartOrder(), inputs.getLast().getEndOrder(),
                "", fingerprint, false);
    }

    private boolean enqueueNextVolume(BookEntity book, VolumeEntity volume, boolean refreshCharter) {
        StoryRollupSummaryEntity last = rollups.selectOne(inTier(book.getId(), 1)
                .select(StoryRollupSummaryEntity::getSeqNo, StoryRollupSummaryEntity::getEndOrder)
                .orderByDesc(StoryRollupSummaryEntity::getSeqNo).last("LIMIT 1"));
        long afterOrder = last == null ? 0L : last.getEndOrder();
        List<SectionSummaryEntity> inputs = sections.selectList(readySections(book.getId())
                .gt(SectionSummaryEntity::getNarrativeOrder, afterOrder)
                .orderByAsc(SectionSummaryEntity::getNarrativeOrder).last("LIMIT " + SECTIONS_PER_VOLUME_SUMMARY));

        UUID volumeId = null;
        String title = "";
        if (isCompleted(volume) && inputs.size() >= 3) {
            List<Long> volumeOrders = sections.readyOrdersInVolume(volume.getId(), afterOrder);
            List<Long> inputOrders = inputs.stream().map(SectionSummaryEntity::getNarrativeOrder).toList();
            if (volumeOrders.size() >= 3 && volumeOrders.size() <= inputs.size()
                    && volumeOrders.equals(inputOrders.subList(0, volumeOrders.size()))) {
                inputs = inputs.subList(0, volumeOrders.size());
                volumeId = volume.getId();
                title = volume.getTitle();
            }
        }
        if (inputs.size() < SECTIONS_PER_VOLUME_SUMMARY && volumeId == null) return false;
        int seqNo = last == null ? 1 : last.getSeqNo() + 1;
        enqueue(book, 1, seqNo, volumeId, inputs.getFirst().getNarrativeOrder(), inputs.getLast().getNarrativeOrder(),
                title, sectionFingerprint(inputs), refreshCharter);
        return true;
    }

    private static LambdaQueryWrapper<StoryRollupSummaryEntity> inTier(UUID bookId, int tier) {
        return new LambdaQueryWrapper<StoryRollupSummaryEntity>()
                .eq(StoryRollupSummaryEntity::getBookId, bookId)
                .eq(StoryRollupSummaryEntity::getTierLevel, tier);
    }

    private static LambdaQueryWrapper<SectionSummaryEntity> readySections(UUID bookId) {
        return new LambdaQueryWrapper<SectionSummaryEntity>()
                .select(SectionSummaryEntity::getNarrativeOrder, SectionSummaryEntity::getContentHash,
                        SectionSummaryEntity::getUpdatedAt)
                .eq(SectionSummaryEntity::getBookId, bookId)
                .eq(SectionSummaryEntity::getStatus, "READY");
    }

    private static boolean isCompleted(VolumeEntity volume) {
        return volume != null && "COMPLETED".equals(volume.getStatus());
    }

    private static String sectionFingerprint(List<SectionSummaryEntity> inputs) {
        return inputs.stream().map(row -> row.getNarrativeOrder() + ":" + row.getContentHash() + ":" + row.getUpdatedAt())
                .collect(Collectors.joining("\n"));
    }

    private void enqueue(
            BookEntity book, int tier, int seqNo, UUID volumeId, long startOrder, long endOrder,
            String title, String fingerprint, boolean refreshCharter
    ) {
        String key = "MEMORY_ROLLUP:" + book.getId() + ":" + tier + ":" + seqNo + ":"
                + StoryMemoryContextService.sha256Hex(startOrder + ":" + endOrder + "\n" + fingerprint);
        Map<String, Object> payload = new HashMap<>();
        payload.put("book_id", book.getId().toString());
        payload.put("owner_id", book.getOwnerId().toString());
        payload.put("tier_level", tier);
        payload.put("seq_no", seqNo);
        payload.put("volume_id", volumeId == null ? null : volumeId.toString());
        payload.put("start_order", startOrder);
        payload.put("end_order", endOrder);
        payload.put("title", title);
        payload.put("refresh_auto_charter", refreshCharter);
        payload.put("idempotency_key", key);
        outbox.enqueue("BOOK", book.getId(), "MEMORY_ROLLUP", RabbitMqTopologyConfig.RK_ROLLUP, key, payload);
    }
}
