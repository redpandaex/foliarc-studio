package studio.inkflow.biz.novel.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import studio.inkflow.biz.common.BizException;
import studio.inkflow.biz.novel.dto.NovelDtos;
import studio.inkflow.biz.novel.entity.ChapterEntity;
import studio.inkflow.biz.novel.entity.ChapterVersionEntity;
import studio.inkflow.biz.novel.mapper.ChapterMapper;
import studio.inkflow.biz.novel.mapper.ChapterVersionMapper;

@Service
public class ChapterService {

    private static final Set<String> CHAPTER_STATES = Set.of("DRAFT", "REVIEWING", "PUBLISHED");

    private final BookService bookService;
    private final ChapterMapper chapterMapper;
    private final ChapterVersionMapper versionMapper;

    public ChapterService(
            BookService bookService,
            ChapterMapper chapterMapper,
            ChapterVersionMapper versionMapper
    ) {
        this.bookService = bookService;
        this.chapterMapper = chapterMapper;
        this.versionMapper = versionMapper;
    }

    public List<NovelDtos.ChapterSummaryResponse> listChapters(UUID ownerId, UUID bookId) {
        bookService.requireOwnedBook(ownerId, bookId);
        var query = new LambdaQueryWrapper<ChapterEntity>()
                .select(
                        ChapterEntity::getId,
                        ChapterEntity::getBookId,
                        ChapterEntity::getChapterNo,
                        ChapterEntity::getTitle,
                        ChapterEntity::getWordCount,
                        ChapterEntity::getStatus,
                        ChapterEntity::getVersion,
                        ChapterEntity::getCreatedAt,
                        ChapterEntity::getUpdatedAt
                )
                .eq(ChapterEntity::getBookId, bookId)
                .orderByAsc(ChapterEntity::getChapterNo);
        return chapterMapper.selectList(query).stream().map(this::mapSummary).toList();
    }

    @Transactional
    public NovelDtos.ChapterDetailResponse createChapter(
            UUID ownerId,
            UUID bookId,
            NovelDtos.CreateChapterRequest input
    ) {
        bookService.requireOwnedBook(ownerId, bookId);
        int seq = input.chapterNo() != null ? input.chapterNo() : chapterMapper.findMaxChapterNo(bookId) + 1;
        String text = input.content() == null ? "" : input.content();

        ChapterEntity entity = new ChapterEntity();
        entity.setId(UUID.randomUUID());
        entity.setBookId(bookId);
        entity.setChapterNo(seq);
        entity.setTitle(input.title().trim());
        entity.setContent(text);
        entity.setWordCount(WordCounter.count(text));
        entity.setStatus("DRAFT");
        entity.setVersion(0L);
        chapterMapper.insert(entity);

        if (!text.isBlank()) {
            saveSnapshotRow(entity.getId(), text, "初始草稿", ownerId);
        }
        return mapDetail(entity);
    }

    public NovelDtos.ChapterDetailResponse getChapter(UUID ownerId, UUID bookId, UUID chapterId) {
        return mapDetail(loadOwnedChapter(ownerId, bookId, chapterId));
    }

    @Transactional
    public NovelDtos.ChapterDetailResponse updateChapter(
            UUID ownerId,
            UUID bookId,
            UUID chapterId,
            NovelDtos.UpdateChapterRequest input
    ) {
        ChapterEntity row = loadOwnedChapter(ownerId, bookId, chapterId);
        String targetState = (input.status() == null || input.status().isBlank())
                ? row.getStatus()
                : input.status().trim().toUpperCase();
        if (!CHAPTER_STATES.contains(targetState)) {
            throw BizException.badRequest("INVALID_CHAPTER_STATUS", "不支持的章节状态: " + targetState);
        }

        row.setTitle(input.title().trim());
        row.setContent(input.content());
        row.setWordCount(WordCounter.count(input.content()));
        row.setStatus(targetState);
        row.setVersion(input.version());

        if (chapterMapper.updateById(row) == 0) {
            throw BizException.conflict("VERSION_CONFLICT", "章节已被其他窗口更新，请刷新最新版本后重试");
        }

        if (Boolean.TRUE.equals(input.createSnapshot())) {
            String note = (input.changeSummary() == null || input.changeSummary().isBlank())
                    ? "保存并创建快照"
                    : input.changeSummary().trim();
            saveSnapshotRow(chapterId, input.content(), note, ownerId);
        }

        ChapterEntity latest = chapterMapper.selectById(chapterId);
        return mapDetail(latest != null ? latest : row);
    }

    @Transactional
    public void deleteChapter(UUID ownerId, UUID bookId, UUID chapterId) {
        loadOwnedChapter(ownerId, bookId, chapterId);
        chapterMapper.deleteById(chapterId);
    }

    public List<NovelDtos.ChapterVersionResponse> listVersions(UUID ownerId, UUID bookId, UUID chapterId) {
        loadOwnedChapter(ownerId, bookId, chapterId);
        var query = new LambdaQueryWrapper<ChapterVersionEntity>()
                .eq(ChapterVersionEntity::getChapterId, chapterId)
                .orderByDesc(ChapterVersionEntity::getVersionNo);
        return versionMapper.selectList(query).stream().map(this::mapVersion).toList();
    }

    @Transactional
    public NovelDtos.ChapterVersionResponse createSnapshot(
            UUID ownerId,
            UUID bookId,
            UUID chapterId,
            NovelDtos.CreateSnapshotRequest input
    ) {
        ChapterEntity row = loadOwnedChapter(ownerId, bookId, chapterId);
        String note = (input == null || input.changeSummary() == null || input.changeSummary().isBlank())
                ? "手动快照"
                : input.changeSummary().trim();
        return mapVersion(saveSnapshotRow(chapterId, row.getContent(), note, ownerId));
    }

    @Transactional
    public NovelDtos.ChapterDetailResponse rollbackToVersion(
            UUID ownerId,
            UUID bookId,
            UUID chapterId,
            UUID versionId,
            NovelDtos.RollbackChapterRequest input
    ) {
        ChapterEntity row = loadOwnedChapter(ownerId, bookId, chapterId);
        ChapterVersionEntity target = versionMapper.selectById(versionId);
        if (target == null || !chapterId.equals(target.getChapterId())) {
            throw BizException.notFound("CHAPTER_VERSION_NOT_FOUND", "历史快照不存在");
        }

        row.setContent(target.getContent());
        row.setWordCount(WordCounter.count(target.getContent()));
        row.setVersion(input.version());
        if (chapterMapper.updateById(row) == 0) {
            throw BizException.conflict("VERSION_CONFLICT", "章节版本已变更，请刷新后再回滚");
        }

        saveSnapshotRow(chapterId, target.getContent(), "回滚至 v" + target.getVersionNo(), ownerId);
        ChapterEntity latest = chapterMapper.selectById(chapterId);
        return mapDetail(latest != null ? latest : row);
    }

    private ChapterEntity loadOwnedChapter(UUID ownerId, UUID bookId, UUID chapterId) {
        bookService.requireOwnedBook(ownerId, bookId);
        ChapterEntity row = chapterMapper.selectById(chapterId);
        if (row == null || !bookId.equals(row.getBookId())) {
            throw BizException.notFound("CHAPTER_NOT_FOUND", "章节不存在");
        }
        return row;
    }

    private ChapterVersionEntity saveSnapshotRow(UUID chapterId, String content, String note, UUID actorId) {
        ChapterVersionEntity ver = new ChapterVersionEntity();
        ver.setId(UUID.randomUUID());
        ver.setChapterId(chapterId);
        ver.setVersionNo(versionMapper.findMaxVersionNo(chapterId) + 1);
        ver.setContent(content);
        ver.setChangeSummary(note);
        ver.setCreatedBy(actorId);
        versionMapper.insert(ver);
        return ver;
    }

    private NovelDtos.ChapterSummaryResponse mapSummary(ChapterEntity c) {
        return new NovelDtos.ChapterSummaryResponse(
                c.getId(), c.getBookId(), c.getChapterNo(), c.getTitle(),
                c.getWordCount(), c.getStatus(), c.getVersion(), c.getCreatedAt(), c.getUpdatedAt()
        );
    }

    private NovelDtos.ChapterDetailResponse mapDetail(ChapterEntity c) {
        return new NovelDtos.ChapterDetailResponse(
                c.getId(), c.getBookId(), c.getChapterNo(), c.getTitle(), c.getContent(),
                c.getWordCount(), c.getStatus(), c.getVersion(), c.getCreatedAt(), c.getUpdatedAt()
        );
    }

    private NovelDtos.ChapterVersionResponse mapVersion(ChapterVersionEntity v) {
        return new NovelDtos.ChapterVersionResponse(
                v.getId(), v.getChapterId(), v.getVersionNo(),
                v.getContent(), v.getChangeSummary(), v.getCreatedBy(), v.getCreatedAt()
        );
    }
}
