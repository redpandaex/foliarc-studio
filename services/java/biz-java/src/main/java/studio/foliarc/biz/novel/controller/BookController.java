package studio.foliarc.biz.novel.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriUtils;

import jakarta.validation.Valid;
import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.memory.service.StoryMemoryContextService;
import studio.foliarc.biz.novel.dto.NovelDtos;
import studio.foliarc.biz.novel.service.BookExportService;
import studio.foliarc.biz.novel.service.BookService;

@RestController
@RequestMapping("/api/v1/books")
public class BookController {

    private final BookService books;
    private final StoryMemoryContextService memoryContextService;
    private final BookExportService bookExports;

    public BookController(BookService books, StoryMemoryContextService memoryContextService, BookExportService bookExports) {
        this.books = books;
        this.memoryContextService = memoryContextService;
        this.bookExports = bookExports;
    }

    @GetMapping
    public List<NovelDtos.BookResponse> listBooks(@AuthenticationPrincipal AuthenticatedUser actor) {
        return books.listBooks(actor.userId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NovelDtos.BookResponse createBook(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody NovelDtos.CreateBookRequest body
    ) {
        return books.createBook(actor.userId(), body);
    }

    @GetMapping("/{bookId}")
    public NovelDtos.BookResponse getBook(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID bookId
    ) {
        return books.getBook(actor.userId(), bookId);
    }

    @GetMapping("/{bookId}/export")
    public ResponseEntity<byte[]> exportBook(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID bookId,
                                             @RequestParam(defaultValue = "epub") String format) {
        var file = bookExports.exportBook(actor.userId(), bookId, format);
        String extension = file.extension();
        String filename = UriUtils.encode(file.title(), StandardCharsets.UTF_8) + "." + extension;
        return ResponseEntity.ok().contentType(file.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"book." + extension
                        + "\"; filename*=UTF-8''" + filename)
                .body(file.content());
    }

    @PatchMapping("/{bookId}")
    public NovelDtos.BookResponse updateBook(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID bookId,
            @Valid @RequestBody NovelDtos.UpdateBookRequest body
    ) {
        return books.updateBook(actor.userId(), bookId, body);
    }

    @DeleteMapping("/{bookId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBook(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID bookId
    ) {
        books.deleteBook(actor.userId(), bookId);
    }

    @GetMapping("/{bookId}/charter")
    public NovelDtos.BookCharterResponse getCharter(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID bookId
    ) {
        return memoryContextService.getBookCharter(actor.userId(), bookId);
    }

    @PutMapping("/{bookId}/charter")
    public NovelDtos.BookCharterResponse updateCharter(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID bookId,
            @Valid @RequestBody NovelDtos.UpdateBookCharterRequest body
    ) {
        return memoryContextService.saveAuthorCharter(actor.userId(), bookId, body.charter());
    }
}
