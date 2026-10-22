package com.rhythm.los.notify;

import com.rhythm.los.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** The signed-in officer's alerts and the phones they get push notifications on. */
@RestController
@RequestMapping("/api/me")
public class NotifyController {
    private final NotifyService service;

    public NotifyController(NotifyService service) { this.service = service; }

    public record Device(String token, String platform) {}
    public record Read(List<Long> ids) {}

    @PostMapping("/push-token")
    public Map<String, Object> register(@RequestBody Device d) {
        service.register(CurrentUser.get(), d.token(), d.platform());
        return Map.of("registered", true);
    }

    @DeleteMapping("/push-token")
    public Map<String, Object> unregister(@RequestParam String token) {
        service.unregister(CurrentUser.get(), token);
        return Map.of("removed", true);
    }

    @GetMapping("/notifications")
    public Map<String, Object> list() { return service.list(CurrentUser.get()); }

    @PostMapping("/notifications/read")
    public Map<String, Object> read(@RequestBody(required = false) Read r) {
        service.read(CurrentUser.get(), r == null ? null : r.ids());
        return Map.of("ok", true);
    }
}
