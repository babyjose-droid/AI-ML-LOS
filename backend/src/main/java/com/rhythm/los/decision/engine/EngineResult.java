package com.rhythm.los.decision.engine;

import java.util.List;

public record EngineResult(
        String decision,              // APPROVE, APPROVE_WITH_CONDITIONS, REFER, REJECT
        double pd,
        int score,
        String band,
        int fraud,
        String fraudStatus,           // CLEAR, REVIEW, BLOCK
        String kycStatus,             // PASS, REVIEW, FAIL
        double idScore,
        double income,
        double stability,
        int negMonths,
        double existing,
        double surplus,
        double foirExisting,
        double foirPost,
        double maxEmi,
        double reqEmi,
        double maxLoan,
        double recAmt,
        double recEmi,
        double rate,
        double lgd,
        double expectedLoss,
        List<Step> steps,
        List<RuleResult> rules,
        List<Contribution> contributions,
        double intercept,
        List<String> conditions,
        String narrative,
        List<String> reasonCodes,
        String modelVersion,
        String policyVersion) {

    public record Step(String name, String value, String status) {}
    public record RuleResult(String id, String description, boolean pass, String action) {}
    public record Contribution(String label, double value, String reasonCode) {}

    public long referCount() {
        return rules.stream().filter(r -> !r.pass() && "REFER".equals(r.action())).count();
    }
}
