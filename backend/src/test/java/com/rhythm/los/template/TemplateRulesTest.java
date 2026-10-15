package com.rhythm.los.template;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateRulesTest {
    @Test
    void messagesUseOnlyTheirPlaceholdersAndFitTwoSms() {
        assertThat(TemplateRules.check("SMS", "OTP_LOGIN", "en", "{otp} is your OTP for {lender}.")).isEmpty();
        assertThat(TemplateRules.check("SMS", "OTP_LOGIN", "en", "Your OTP for {lender}.")).anyMatch(e -> e.contains("must include {otp}"));
        assertThat(TemplateRules.check("SMS", "PARTY_INVITE", "en", "Open {link} now, {pan}")).anyMatch(e -> e.contains("{pan} cannot be used here"));
        assertThat(TemplateRules.check("SMS", "VKYC_LINK", "xx", "{link}")).anyMatch(e -> e.contains("Language"));
        assertThat(TemplateRules.check("SMS", "NOPE", "en", "x")).anyMatch(e -> e.contains("Unknown message"));
        assertThat(TemplateRules.check("SMS", "VKYC_LINK", "en", "{link} " + "x".repeat(400))).anyMatch(e -> e.contains("too long") || e.contains("Too long"));
        assertThat(TemplateRules.check("SMS", "VKYC_LINK", "en", "<b>{link}</b>")).anyMatch(e -> e.contains("No HTML"));
    }

    @Test
    void letterWordingTakesNoPlaceholdersAndRenders() {
        assertThat(TemplateRules.check("LETTER", "intro", "hi", "हम आपका ऋण स्वीकृत करते हैं।")).isEmpty();
        assertThat(TemplateRules.check("LETTER", "intro", "en", "Dear {name}")).anyMatch(e -> e.contains("no placeholders"));
        assertThat(TemplateRules.render("{otp} for {lender} ({unknown})", Map.of("otp", "123456", "lender", "X$Finance"))).isEqualTo("123456 for X$Finance ({unknown})");
        assertThat(TemplateRules.placeholders("{a} {b} {a}")).containsExactly("a", "b");
        assertThat(TemplateRules.sample("PARTY_INVITE").keySet()).contains("link", "role");
    }
}
