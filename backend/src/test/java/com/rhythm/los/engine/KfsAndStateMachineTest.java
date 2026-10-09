package com.rhythm.los.engine;

import com.rhythm.los.application.Domain;
import com.rhythm.los.application.StateMachine;
import com.rhythm.los.decision.engine.Finance;
import com.rhythm.los.sanction.Kfs;
import com.rhythm.los.sanction.KfsCalculator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class KfsAndStateMachineTest {

    @Test
    void kfsRepaysPrincipalAndAprIncludesFees() {
        Kfs k = KfsCalculator.build("APP-1", "Test", "PL", 300000, 17.5, 24, 2.0);
        assertThat(k.processingFee()).isEqualTo(6000);
        assertThat(k.gstOnFee()).isEqualTo(1080);
        assertThat(k.netDisbursed()).isEqualTo(300000 - 6000 - 1080);
        assertThat(k.schedule()).hasSize(24);
        double principal = k.schedule().stream().mapToDouble(Kfs.Row::principal).sum();
        assertThat(principal).isCloseTo(300000, offset(0.05));
        assertThat(k.schedule().get(23).balance()).isZero();
        assertThat(k.aprPa()).isGreaterThan(k.ratePa());
        assertThat(k.emi()).isEqualTo(14905);
    }

    @Test
    void aprWithoutFeesEqualsRate() {
        double emi = Finance.emi(100000, 18, 12);
        assertThat(Finance.apr(100000, emi, 12)).isCloseTo(18, offset(0.001));
    }

    @Test
    void stateMachineRejectsUnlistedMoves() {
        assertThat(StateMachine.allowed(Domain.APP, "DRAFT", "SUBMITTED")).isTrue();
        assertThat(StateMachine.allowed(Domain.APP, "DRAFT", "DISBURSED")).isFalse();
        assertThat(StateMachine.allowed(Domain.APP, "DISBURSED", "REJECTED")).isFalse();
        assertThat(StateMachine.allowed(Domain.SANCTION, "PENDING_L1", "PENDING_L2")).isTrue();
        assertThat(StateMachine.allowed(Domain.SANCTION, "PENDING_L3", "PENDING_L1")).isFalse();
        assertThat(StateMachine.allowed(Domain.DISB, "FAILED", "READY")).isTrue();
        for (Domain d : Domain.values()) assertThat(StateMachine.initial(d)).isNotBlank();
    }
}
