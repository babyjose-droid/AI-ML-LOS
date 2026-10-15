package com.rhythm.los.template;

import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.org.Role;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/** The lender's wording: sanction letter and KFS text per language, and customer messages. */
@Service
public class TemplateService {
    static final Set<Role> EDITORS = EnumSet.of(Role.ADMIN, Role.COMPLIANCE);

    private final MessageTemplateRepository repo;
    private final AuditService audit;

    public TemplateService(MessageTemplateRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    /** The message in the language asked for, else English, rendered with the details; the fallback when none is set. */
    public String render(String code, String language, Map<String, String> vars, String fallback) {
        Optional<MessageTemplate> t = repo.findFirstByChannelAndCodeAndLanguageAndActiveTrueOrderByVersionDesc("SMS", code, language == null ? "en" : language);
        if (t.isEmpty() && !"en".equals(language)) t = repo.findFirstByChannelAndCodeAndLanguageAndActiveTrueOrderByVersionDesc("SMS", code, "en");
        return t.map(x -> TemplateRules.render(x.getBody(), vars)).orElse(fallback);
    }

    /** Letter and KFS wording the lender changed, for one language (key -> text). */
    public Map<String, String> letterTexts(String language) {
        Map<String, String> out = new LinkedHashMap<>();
        // the highest active version of each key wins
        repo.findByChannelAndLanguageAndActiveTrue("LETTER", language == null ? "en" : language).stream()
                .sorted(Comparator.comparingInt(MessageTemplate::getVersion)).forEach(t -> out.put(t.getCode(), t.getBody()));
        return out;
    }

    public Map<String, Object> list() {
        Map<String, MessageTemplate> latest = new LinkedHashMap<>();
        for (MessageTemplate t : repo.findByActiveTrueOrderByChannelAscCodeAscLanguageAscVersionDesc())
            latest.putIfAbsent(t.getChannel() + "|" + t.getCode() + "|" + t.getLanguage(), t);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("templates", latest.values());
        m.put("messages", TemplateRules.MESSAGES);
        m.put("languages", new TreeSet<>(TemplateRules.LANGUAGES));
        m.put("channels", new TreeSet<>(TemplateRules.CHANNELS));
        return m;
    }

    public List<MessageTemplate> history(String channel, String code, String language) {
        return repo.findByChannelAndCodeAndLanguageOrderByVersionDesc(channel, code, language);
    }

    public record SaveRequest(String channel, String code, String language, String subject, String body, String note) {}

    @Transactional
    public MessageTemplate save(SaveRequest r, CurrentUser by) {
        if (!EDITORS.contains(by.role())) throw ApiException.forbidden("NOT_ALLOWED", "An admin or compliance edits the wording");
        String channel = r.channel() == null ? "" : r.channel().toUpperCase(), code = r.code() == null ? "" : r.code().trim(), lang = r.language() == null ? "en" : r.language();
        List<String> problems = TemplateRules.check(channel, code, lang, r.body());
        if (!problems.isEmpty()) throw ApiException.unprocessable("TEMPLATE_INVALID", String.join("; ", problems));
        String note = r.note() == null ? "" : r.note().trim();
        if (note.length() < 5) throw ApiException.unprocessable("REASON_REQUIRED", "Say what changed (at least 5 characters)");
        List<MessageTemplate> old = repo.findByChannelAndCodeAndLanguageOrderByVersionDesc(channel, code, lang);
        MessageTemplate t = new MessageTemplate();
        t.setChannel(channel);
        t.setCode(code);
        t.setLanguage(lang);
        t.setSubject(r.subject() == null || r.subject().isBlank() ? null : r.subject().trim());
        t.setBody(r.body().trim());
        t.setVersion(old.isEmpty() ? 1 : old.get(0).getVersion() + 1);
        t.setNote(note.length() > 300 ? note.substring(0, 300) : note);
        t.setUpdatedBy(by.username());
        t.setUpdatedAt(Instant.now());
        repo.save(t);
        audit.record(by, "template.saved", "TEMPLATE", channel + "/" + code + "/" + lang, null, "v" + t.getVersion() + " · " + t.getNote());
        return t;
    }

    /** Back to the built-in wording (letter) or the previous version (messages keep at least one). */
    @Transactional
    public void revert(String channel, String code, String language, CurrentUser by) {
        if (!EDITORS.contains(by.role())) throw ApiException.forbidden("NOT_ALLOWED", "An admin or compliance edits the wording");
        List<MessageTemplate> all = repo.findByChannelAndCodeAndLanguageOrderByVersionDesc(channel, code, language).stream().filter(MessageTemplate::isActive).toList();
        if (all.isEmpty()) throw ApiException.notFound("Template");
        if (!"LETTER".equals(channel) && all.size() == 1) throw ApiException.conflict("LAST_VERSION", "This is the only version; edit it instead");
        MessageTemplate t = all.get(0);
        t.setActive(false);
        repo.save(t);
        audit.record(by, "template.reverted", "TEMPLATE", channel + "/" + code + "/" + language, null, "v" + t.getVersion() + " withdrawn");
    }

    public Map<String, Object> preview(SaveRequest r) {
        String channel = r.channel() == null ? "" : r.channel().toUpperCase();
        List<String> problems = TemplateRules.check(channel, r.code() == null ? "" : r.code(), r.language() == null ? "en" : r.language(), r.body());
        String text = r.body() == null ? "" : TemplateRules.render(r.body(), TemplateRules.sample(r.code()));
        return Map.of("text", text, "length", text.length(), "smsParts", text.isEmpty() ? 0 : (text.length() + 159) / 160, "problems", problems);
    }
}
