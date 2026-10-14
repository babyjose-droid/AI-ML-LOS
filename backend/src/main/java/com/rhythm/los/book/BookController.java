package com.rhythm.los.book;

import com.rhythm.los.common.ApiException;
import com.rhythm.los.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Loan book: LMS feed (API, CSV, demo), portfolio summary, loans and the early-warning watchlist. */
@RestController
@RequestMapping("/api/book")
public class BookController {
    static final String VIEW = "hasAnyRole('ADMIN','OPERATIONS','CREDIT_OFFICER','CREDIT_MANAGER','CRO','COMPLIANCE')";
    static final String FEED = "hasAnyRole('ADMIN','OPERATIONS')";

    private final LoanBookService book;

    public BookController(LoanBookService book) { this.book = book; }

    public record FeedRequest(String feedDate, List<LoanBookService.FeedRow> rows) {}

    @PostMapping("/feed")
    @PreAuthorize(FEED)
    public LmsFeed feed(@RequestBody FeedRequest r) {
        return book.ingest(r.feedDate() == null ? null : LoanBookService.date(r.feedDate()), r.rows(), "API", CurrentUser.get());
    }

    @PostMapping(value = "/feed/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(FEED)
    public LmsFeed csv(@RequestPart("file") MultipartFile file, @RequestParam(required = false) String feedDate) throws Exception {
        if (file.getSize() > 20L * 1024 * 1024) throw ApiException.unprocessable("FEED_TOO_LARGE", "The CSV must be under 20 MB");
        List<LoanBookService.FeedRow> rows = LoanBookService.parseCsv(new String(file.getBytes(), StandardCharsets.UTF_8));
        return book.ingest(feedDate == null || feedDate.isBlank() ? null : LoanBookService.date(feedDate), rows, "CSV", CurrentUser.get());
    }

    @PostMapping("/feed/demo")
    @PreAuthorize(FEED)
    public LmsFeed demo() { return book.demoFeed(CurrentUser.get()); }

    @GetMapping("/feeds")
    @PreAuthorize(VIEW)
    public List<LmsFeed> feeds() { return book.feeds(); }

    @GetMapping("/summary")
    @PreAuthorize(VIEW)
    public Map<String, Object> summary() { return book.summary(CurrentUser.get()); }

    @GetMapping("/loans")
    @PreAuthorize(VIEW)
    public List<LiveLoan> loans(@RequestParam(required = false) String classification, @RequestParam(defaultValue = "false") boolean watch,
                                @RequestParam(required = false) String q) {
        return book.list(CurrentUser.get(), classification, watch, q);
    }

    @GetMapping("/loans/{id}")
    @PreAuthorize(VIEW)
    public Map<String, Object> loan(@PathVariable Long id) { return book.detail(id, CurrentUser.get()); }
}
