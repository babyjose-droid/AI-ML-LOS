package com.rhythm.los.memo;

import com.rhythm.los.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/applications/{id}/memo")
public class MemoController {
    private final CreditMemoService memos;

    public MemoController(CreditMemoService memos) { this.memos = memos; }

    @GetMapping
    public Map<String, Object> view(@PathVariable Long id) { return memos.view(id, CurrentUser.get()); }

    @PutMapping
    public Map<String, Object> edit(@PathVariable Long id, @RequestBody CreditMemoService.EditRequest r) { return memos.edit(id, r, CurrentUser.get()); }

    @PostMapping("/sign")
    public Map<String, Object> sign(@PathVariable Long id) { return memos.sign(id, CurrentUser.get()); }
}
