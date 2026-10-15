package com.rhythm.los.party;

import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PartyRulesTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 14);
    // business loan defaults: co-applicant optional (2), guarantor required above 5 lakh, spouse/partner income counts, 2 earners
    static final PartyRules BL = PartyRules.parse("""
            {"coApplicant":{"mode":"OPTIONAL","max":2,"requiredWhen":""},"guarantor":{"mode":"OPTIONAL","max":1,"requiredWhen":"amount > 500000"},
             "coOwnersMustJoin":false,"incomeClubbing":{"allowed":true,"maxEarners":2,"relationships":["SPOUSE","BUSINESS_PARTNER"]},
             "relationships":["SPOUSE","PARENT","CHILD","SIBLING","BUSINESS_PARTNER","FRIEND"],"minBureauScore":700,"minAge":18,"maxAgeAtMaturity":70}""");
    static final PartyRules LAP = PartyRules.parse("""
            {"coApplicant":{"mode":"OPTIONAL","max":3},"guarantor":{"mode":"OPTIONAL","max":2},"coOwnersMustJoin":true,
             "incomeClubbing":{"allowed":true,"maxEarners":3,"relationships":[]},"minBureauScore":700}""");
    static final PartyRules GOLD = PartyRules.parse("""
            {"coApplicant":{"mode":"NOT_ALLOWED","max":0},"guarantor":{"mode":"NOT_ALLOWED","max":0}}""");

    static PartyRules.Party party(long id, String role, String rel, String name, boolean income, String kyc, String bureau, Integer score, double inc, double emi, boolean signed) {
        return new PartyRules.Party(id, role, rel, name, "ABCP" + id + "1234Z", LocalDate.of(1980, 1, 1), income, false, "ACTIVE",
                true, kyc, bureau, score, inc, emi, 0, signed);
    }

    static Map<String, Object> facts(double amount) { return Map.of("amount", amount, "tenure", 36.0); }

    @Test
    void guarantorRuleBlocksSanctionNotSubmission() {
        List<PartyRules.Issue> big = BL.check(facts(600_000), List.of(), List.of(), "Suresh Yadav", List.of(), false, 36, TODAY);
        assertThat(big).extracting(PartyRules.Issue::code).containsExactly("PARTY_REQUIRED");
        assertThat(big.get(0).gate()).isEqualTo("SANCTION");
        assertThat(PartyRules.blocking(big, "SUBMIT")).isEmpty();
        assertThat(PartyRules.blocking(big, "DECISION")).isEmpty();
        assertThat(PartyRules.blocking(big, "SANCTION")).hasSize(1);
        assertThat(BL.check(facts(400_000), List.of(), List.of(), "Suresh Yadav", List.of(), false, 36, TODAY)).isEmpty();
    }

    @Test
    void mandatoryPartyAndLimitsBlockSubmission() {
        PartyRules mandatory = PartyRules.parse("{\"coApplicant\":{\"mode\":\"MANDATORY\",\"max\":1},\"guarantor\":{\"mode\":\"NOT_ALLOWED\",\"max\":0}}");
        assertThat(PartyRules.blocking(mandatory.check(facts(100_000), List.of(), List.of(), "A", List.of(), false, 12, TODAY), "SUBMIT"))
                .extracting(PartyRules.Issue::message).containsExactly("A co-applicant is required for this product");
        List<PartyRules.Party> two = List.of(party(1, "GUARANTOR", "PARENT", "Ravi Rao", false, "VERIFIED", "FETCHED", 760, 0, 0, false));
        assertThat(PartyRules.blocking(GOLD.check(facts(100_000), two, List.of(), "A", List.of(), false, 12, TODAY), "SUBMIT"))
                .extracting(PartyRules.Issue::code).containsExactly("PARTY_NOT_ALLOWED");
    }

    @Test
    void admitChecksRoleCountRelationshipAgeAndClubbing() {
        assertThat(GOLD.admit("CO_APPLICANT", "SPOUSE", LocalDate.of(1990, 1, 1), false, 0, 12, TODAY)).containsExactly("This product does not allow a co-applicant");
        assertThat(BL.admit("GUARANTOR", "PARENT", LocalDate.of(1990, 1, 1), false, 1, 24, TODAY)).containsExactly("This product allows at most 1 guarantor");
        assertThat(BL.admit("CO_APPLICANT", "DIRECTOR", LocalDate.of(1990, 1, 1), false, 0, 24, TODAY)).anyMatch(x -> x.contains("director is not allowed"));
        // 60 today, 36-month loan: 63 at maturity is fine; 69 today is 72 at maturity, above 70
        assertThat(BL.admit("CO_APPLICANT", "PARENT", LocalDate.of(1966, 6, 1), false, 0, 36, TODAY)).isEmpty();
        assertThat(BL.admit("CO_APPLICANT", "PARENT", LocalDate.of(1957, 6, 1), false, 0, 36, TODAY)).anyMatch(x -> x.contains("70 or younger"));
        assertThat(BL.admit("CO_APPLICANT", "SIBLING", LocalDate.of(2010, 6, 1), false, 0, 36, TODAY)).anyMatch(x -> x.contains("at least 18"));
        // only a spouse's or partner's income counts on a business loan; a guarantor's never does
        assertThat(BL.admit("CO_APPLICANT", "PARENT", LocalDate.of(1966, 6, 1), true, 0, 36, TODAY)).anyMatch(x -> x.contains("cannot be added"));
        assertThat(BL.admit("CO_APPLICANT", "SPOUSE", LocalDate.of(1985, 6, 1), true, 0, 36, TODAY)).isEmpty();
        assertThat(BL.admit("GUARANTOR", "SPOUSE", LocalDate.of(1985, 6, 1), true, 0, 36, TODAY)).anyMatch(x -> x.contains("guarantor's income is not counted"));
    }

    @Test
    void eachPartyMustConsentPassKycAndBureauBeforeDecisionThenSign() {
        PartyRules.Party noConsent = new PartyRules.Party(1, "CO_APPLICANT", "SPOUSE", "Asha Yadav", "ASHPY1234A", LocalDate.of(1984, 1, 1), true, false,
                "INVITED", false, "NOT_STARTED", "NOT_FETCHED", null, 40_000, 0, 0, false);
        assertThat(PartyRules.blocking(BL.check(facts(300_000), List.of(noConsent), List.of(), "Suresh", List.of(), false, 36, TODAY), "DECISION"))
                .extracting(PartyRules.Issue::message).containsExactly("Asha Yadav (co-applicant) has not given consent yet");
        PartyRules.Party review = party(1, "CO_APPLICANT", "SPOUSE", "Asha Yadav", true, "REVIEW", "NOT_FETCHED", null, 40_000, 0, false);
        assertThat(BL.check(facts(300_000), List.of(review), List.of(), "Suresh", List.of(), false, 36, TODAY)).extracting(PartyRules.Issue::message)
                .containsExactly("Asha Yadav (co-applicant): KYC waiting for review");
        PartyRules.Party ready = party(1, "CO_APPLICANT", "SPOUSE", "Asha Yadav", true, "VERIFIED", "FETCHED", 740, 40_000, 0, false);
        assertThat(BL.check(facts(300_000), List.of(ready), List.of(), "Suresh", List.of(), false, 36, TODAY)).isEmpty();
        List<PartyRules.Issue> after = BL.check(facts(300_000), List.of(ready), List.of(), "Suresh", List.of(), true, 36, TODAY);
        assertThat(after).extracting(PartyRules.Issue::gate).containsExactly("DISBURSE");
        assertThat(PartyRules.blocking(after, "SANCTION")).isEmpty();
    }

    @Test
    void coOwnersOfTheCollateralMustJoinAndCreditRequestsBlockSanction() {
        List<String> owners = PartyRules.splitOwners("Smt Asha Rao & Vikram Rao");
        assertThat(owners).containsExactly("Asha Rao", "Vikram Rao");
        assertThat(PartyRules.splitOwners("A K Shah and B Shah, C Shah")).containsExactly("A K Shah", "B Shah", "C Shah");
        List<PartyRules.Issue> missing = LAP.check(facts(2_000_000), List.of(), owners, "Vikram Rao", List.of(), false, 120, TODAY);
        assertThat(missing).extracting(PartyRules.Issue::message).containsExactly("Asha Rao co-owns the collateral and must join as a co-applicant");
        assertThat(missing.get(0).gate()).isEqualTo("SANCTION");
        PartyRules.Party asha = party(1, "CO_APPLICANT", "SPOUSE", "Asha Rao", false, "VERIFIED", "FETCHED", 760, 0, 0, false);
        assertThat(LAP.check(facts(2_000_000), List.of(asha), owners, "Vikram Rao", List.of(), false, 120, TODAY)).isEmpty();
        // a guarantor does not satisfy a co-owner
        PartyRules.Party ashaG = party(1, "GUARANTOR", "SPOUSE", "Asha Rao", false, "VERIFIED", "FETCHED", 760, 0, 0, false);
        assertThat(LAP.check(facts(2_000_000), List.of(ashaG), owners, "Vikram Rao", List.of(), false, 120, TODAY)).hasSize(1);
        List<PartyRules.Issue> asked = LAP.check(facts(2_000_000), List.of(asha), owners, "Vikram Rao",
                List.of(new PartyRules.Requirement(9, "GUARANTOR", "Thin file: needs a salaried guarantor")), false, 120, TODAY);
        assertThat(asked).extracting(PartyRules.Issue::code).containsExactly("PARTY_REQUESTED");
    }

    @Test
    void jointIncomeCountsOnlyAllowedVerifiedEarnersUpToTheLimit() {
        List<PartyRules.Party> ps = List.of(
                party(1, "CO_APPLICANT", "SPOUSE", "Asha", true, "VERIFIED", "FETCHED", 760, 40_000, 5_000, false),
                party(2, "CO_APPLICANT", "BUSINESS_PARTNER", "Kiran", true, "VERIFIED", "FETCHED", 720, 60_000, 0, false),
                party(3, "GUARANTOR", "PARENT", "Ravi", false, "VERIFIED", "FETCHED", 650, 90_000, 0, false));
        PartyRules.Joint j = BL.joint(ps, 36, TODAY);
        // two earners in all: the applicant and one co-applicant (the first added)
        assertThat(j.earnersCounted()).isEqualTo(1);
        assertThat(j.clubbedIncome()).isEqualTo(40_000);
        assertThat(j.clubbedEmi()).isEqualTo(5_000);
        assertThat(j.coApplicants()).isEqualTo(2);
        assertThat(j.guarantors()).isEqualTo(1);
        assertThat(j.policyFails()).containsExactly("Ravi (guarantor): bureau score 650 below 700");
        PartyRules.Joint lap = LAP.joint(ps, 120, TODAY);
        assertThat(lap.clubbedIncome()).isEqualTo(100_000);
        // 46 today + 10 years = 56: within 70
        assertThat(lap.policyFails()).hasSize(1);
        // a guarantor with no credit record is referred when the product has a minimum score
        PartyRules.Party noHit = party(4, "GUARANTOR", "FRIEND", "New Person", false, "VERIFIED", "NO_HIT", null, 0, 0, false);
        assertThat(BL.joint(List.of(noHit), 36, TODAY).policyFails()).containsExactly("New Person (guarantor): no credit record");
    }

    @Test
    void validationRefusesBadRules() {
        assertThatThrownBy(() -> PartyRules.validated(new PartyRules(new PartyRules.Slot("SOMETIMES", 1, ""), null, false, null, null, 0, 18, 70, "")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PartyRules.validated(new PartyRules(new PartyRules.Slot("OPTIONAL", 1, "loanSize > 5"), null, false, null, null, 0, 18, 70, "")))
                .hasMessageContaining("Unknown field");
        assertThatThrownBy(() -> PartyRules.validated(new PartyRules(new PartyRules.Slot("NOT_ALLOWED", 0, "amount > 5 L"), null, false, null, null, 0, 18, 70, "")))
                .hasMessageContaining("cannot be required");
        assertThatThrownBy(() -> PartyRules.validated(new PartyRules(null, null, false, new PartyRules.Clubbing(true, 1, List.of()), null, 0, 18, 70, "")))
                .hasMessageContaining("at least 2 earners");
        PartyRules ok = PartyRules.validated(Json.read(Json.write(BL), PartyRules.class));
        assertThat(ok.guarantor().requiredWhen()).isEqualTo("amount > 500000");
        assertThat(PartyRules.parse(null)).isEqualTo(PartyRules.DEFAULT);
        assertThat(PartyRules.parse("not json")).isEqualTo(PartyRules.DEFAULT);
    }

    static final PartyRules BL_VERIFY = PartyRules.parse("""
            {"coApplicant":{"mode":"OPTIONAL","max":2},"guarantor":{"mode":"OPTIONAL","max":1},"entityGuarantors":true,
             "incomeClubbing":{"allowed":true,"maxEarners":2,"relationships":["SPOUSE"],"verify":true}}""");

    static PartyRules.Party spouse(Double verified) {
        return new PartyRules.Party(1, "CO_APPLICANT", "SPOUSE", "Asha", "ABCPA1234Z", LocalDate.of(1985, 1, 1), true, false, "ACTIVE",
                true, "VERIFIED", "FETCHED", 760, 40_000, 2_000, 0, false, "INDIVIDUAL", verified);
    }

    @Test
    void countedIncomeWaitsForBankDataAndTheLowerFigureCounts() {
        assertThat(BL_VERIFY.incomeClubbing().verify()).isTrue();
        assertThat(BL.incomeClubbing().verify()).isFalse();
        // not checked yet: a decision blocker, and nothing counted
        List<PartyRules.Issue> before = BL_VERIFY.check(facts(300_000), List.of(spouse(null)), List.of(), "Ravi", List.of(), false, 36, TODAY);
        assertThat(PartyRules.blocking(before, "DECISION")).extracting(PartyRules.Issue::code).contains("PARTY_INCOME");
        assertThat(BL_VERIFY.joint(List.of(spouse(null)), 36, TODAY).clubbedIncome()).isZero();
        // bank data shows less than declared: the lower counts; more than declared: the declared figure counts
        assertThat(BL_VERIFY.joint(List.of(spouse(31_000.0)), 36, TODAY).clubbedIncome()).isEqualTo(31_000);
        assertThat(BL_VERIFY.joint(List.of(spouse(55_000.0)), 36, TODAY).clubbedIncome()).isEqualTo(40_000);
        assertThat(PartyRules.blocking(BL_VERIFY.check(facts(300_000), List.of(spouse(38_000.0)), List.of(), "Ravi", List.of(), false, 36, TODAY), "DECISION")).isEmpty();
        // without the setting, declared income counts as before
        assertThat(BL.joint(List.of(spouse(null)), 36, TODAY).clubbedIncome()).isEqualTo(40_000);
    }

    @Test
    void sustainableIncomeDiscountsOneOffSpikes() {
        // eleven ordinary months and one big receipt: the spike does not lift the figure
        List<Double> in = List.of(40_000.0, 41_000.0, 39_000.0, 42_000.0, 40_500.0, 38_500.0, 41_500.0, 40_000.0, 39_500.0, 40_000.0, 41_000.0, 400_000.0);
        assertThat(PartyRules.sustainableIncome(in)).isBetween(39_500.0, 41_000.0);
        // irregular: a quarter of months are thin, so it is held to 1.15 x the 25th percentile
        assertThat(PartyRules.sustainableIncome(List.of(10_000.0, 10_000.0, 60_000.0, 60_000.0))).isLessThan(30_000);
        assertThat(PartyRules.sustainableIncome(List.of())).isZero();
    }

    @Test
    void companiesStandOnlyAsGuarantorsWithTheRightPanAndLink() {
        assertThat(PartyRules.panLetter("COMPANY")).isEqualTo("C");
        assertThat(PartyRules.panLetter("LLP")).isEqualTo("F");
        assertThat(PartyRules.panLetter("PARTNERSHIP")).isEqualTo("F");
        assertThat(PartyRules.panLetter("HUF")).isEqualTo("H");
        assertThat(PartyRules.panLetter("TRUST")).isEqualTo("T");
        assertThat(PartyRules.panLetter("PROPRIETORSHIP")).isEqualTo("P");
        LocalDate inc = LocalDate.of(2020, 4, 1);
        assertThat(BL_VERIFY.admit("GUARANTOR", "GROUP_COMPANY", inc, false, 0, 36, TODAY, "COMPANY")).isEmpty();
        assertThat(BL_VERIFY.admit("CO_APPLICANT", "GROUP_COMPANY", inc, false, 0, 36, TODAY, "COMPANY")).anyMatch(x -> x.contains("Only a guarantor"));
        assertThat(BL_VERIFY.admit("GUARANTOR", "SPOUSE", inc, false, 0, 36, TODAY, "COMPANY")).anyMatch(x -> x.contains("link must be one of"));
        assertThat(BL_VERIFY.admit("GUARANTOR", "GROUP_COMPANY", inc, true, 0, 36, TODAY, "COMPANY")).anyMatch(x -> x.contains("income is not counted"));
        // a product that does not take them
        assertThat(BL.admit("GUARANTOR", "GROUP_COMPANY", inc, false, 0, 36, TODAY, "COMPANY")).anyMatch(x -> x.contains("does not take company"));
        // a 6-year-old company is not "under 18"
        assertThat(BL_VERIFY.admit("GUARANTOR", "EMPLOYER", inc, false, 0, 36, TODAY, "LLP")).isEmpty();
    }
}
