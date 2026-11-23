package studio.inkflow.biz.novel.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import studio.inkflow.biz.common.BizException;
import studio.inkflow.biz.novel.dto.NovelDtos.BookResponse;
import studio.inkflow.biz.novel.dto.NovelDtos.CreateBookRequest;
import studio.inkflow.biz.novel.dto.NovelDtos.UpdateBookRequest;
import studio.inkflow.biz.novel.entity.BookEntity;
import studio.inkflow.biz.novel.mapper.BookMapper;
import studio.inkflow.biz.novel.mapper.ChapterMapper;
import studio.inkflow.biz.novel.mapper.ChapterMapper.BookChapterStats;

@Service
public class BookService {

    private static final Set<String> ALLOWED_STATUSES = Set.of("DRAFT", "ONGOING", "COMPLETED", "ARCHIVED");

    private final BookMapper bookMapper;
    private final ChapterMapper chapterMapper;

    public BookService(BookMapper bookMapper, ChapterMapper chapterMapper) {
        this.bookMapper = bookMapper;
        this.chapterMapper = chapterMapper;
    }

    public List<BookResponse> listBooks(UUID ownerId) {
        List<BookEntity> books = bookMapper.selectList(
                new LambdaQueryWrapper<BookEntity>()
                        .eq(BookEntity::getOwnerId, ownerId)
                        .orderByDesc(BookEntity::getUpdatedAt)
        );
        if (books.isEmpty()) {
            return List.of();
        }
        Map<UUID, BookChapterStats> statsMap = chapterMapper.findStatsByOwnerId(ownerId).stream()
                .collect(Collectors.toMap(BookChapterStats::getBookId, Function.identity(), (a, b) -> a));

        return books.stream()
                .map(b -> toResponse(b, statsMap.get(b.getId())))
                .toList();
    }

    @Transactional
    public BookResponse createBook(UUID ownerId, CreateBookRequest req) {
        BookEntity entity = new BookEntity();
        entity.setId(UUID.randomUUID());
        entity.setOwnerId(ownerId);
        entity.setTitle(req.title().trim());
        entity.setGenre(cleanOptional(req.genre()));
        entity.setSynopsis(cleanOptional(req.synopsis()));
        entity.setStatus("DRAFT");
        entity.setVersion(0L);
        bookMapper.insert(entity);
        return toResponse(entity, null);
    }

    public BookResponse getBook(UUID ownerId, UUID bookId) {
        BookEntity book = requireOwnedBook(ownerId, bookId);
        BookChapterStats stats = chapterMapper.findStatsByOwnerId(ownerId).stream()
                .filter(s -> bookId.equals(s.getBookId()))
                .findFirst()
                .orElse(null);
        return toResponse(book, stats);
    }

    @Transactional
    public BookResponse updateBook(UUID ownerId, UUID bookId, UpdateBookRequest req) {
        BookEntity book = requireOwnedBook(ownerId, bookId);

        String nextStatus = (req.status() == null || req.status().isBlank())
                ? book.getStatus()
                : req.status().trim().toUpperCase();
        if (!ALLOWED_STATUSES.contains(nextStatus)) {
            throw BizException.badRequest("INVALID_BOOK_STATUS", "无效的小说状态: " + nextStatus);
        }

        book.setTitle(req.title().trim());
        book.setGenre(cleanOptional(req.genre()));
        book.setSynopsis(cleanOptional(req.synopsis()));
        book.setStatus(nextStatus);
        book.setVersion(req.version());

        if (bookMapper.updateById(book) == 0) {
            throw BizException.conflict("VERSION_CONFLICT", "书籍已被其他请求修改，请刷新后重试");
        }
        return getBook(ownerId, bookId);
    }

    @Transactional
    public void deleteBook(UUID ownerId, UUID bookId) {
        requireOwnedBook(ownerId, bookId);
        bookMapper.deleteById(bookId);
    }

    public BookEntity requireOwnedBook(UUID ownerId, UUID bookId) {
        BookEntity book = bookMapper.selectById(bookId);
        if (book == null) {
            throw BizException.notFound("BOOK_NOT_FOUND", "小说不存在");
        }
        if (!ownerId.equals(book.getOwnerId())) {
            throw BizException.forbidden("BOOK_ACCESS_DENIED", "无权操作该小说");
        }
        return book;
    }

    private static BookResponse toResponse(BookEntity book, BookChapterStats stats) {
        int chapterCount = stats == null ? 0 : stats.getChapterCount();
        long totalWords = stats == null ? 0L : stats.getTotalWordCount();
        return new BookResponse(
                book.getId(),
                book.getTitle(),
                book.getGenre(),
                book.getSynopsis(),
                book.getStatus(),
                book.getVersion(),
                chapterCount,
                totalWords,
                book.getCreatedAt(),
                book.getUpdatedAt()
        );
    }

    private static String cleanOptional(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
