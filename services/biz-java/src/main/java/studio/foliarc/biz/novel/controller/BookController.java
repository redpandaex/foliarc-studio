package studio.foliarc.biz.novel.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.novel.dto.NovelDtos;
import studio.foliarc.biz.novel.service.BookService;

@RestController
@RequestMapping("/api/v1/books")
public class BookController {

    private final BookService books;

    public BookController(BookService books) {
        this.books = books;
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
}
