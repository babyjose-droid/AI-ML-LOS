package com.rhythm.los.vkyc;

import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VkycPolicyTest {
    static final VkycPolicy LAP = new VkycPolicy("ALL", 0.45, true, true, 48, "09:00", "19:00", 2, List.of("OPERATIONS", "CREDIT_OFFICER"),
            List.of("Confirm your name", "Do you agree to the mortgage?"), List.of("en", "mr"));

    static Map<String, Object> analysis(double face, boolean live, Object panCheck, List<String> reasons) {
        return Map.of("status", "PASS", "liveness", Map.of("passed", live, "reasons", live ? List.of() : List.of("Head turn left not seen")),
                "faceMatch", Map.of("score", face, "matched", face >= 0.363), "panCheck", panCheck == null ? "none" : panCheck, "reasons", reasons);
    }

    @Test
    void productThresholdsDecidePassOrReview() {
        Map<String, Object> ok = LAP.judge(analysis(0.52, true, Map.of("found", "ABCPK1234L", "matches", true), List.of()));
        assertThat(ok.get("status")).isEqualTo("PASS");
        // the AI says PASS at 0.40 (above its own 0.363) but this product wants 0.45
        Map<String, Object> weak = LAP.judge(analysis(0.40, true, Map.of("found", "ABCPK1234L", "matches", true), List.of()));
        assertThat(weak.get("status")).isEqualTo("REVIEW");
        assertThat(weak.get("aiStatus")).isEqualTo("PASS");
        assertThat((List<?>) weak.get("reasons")).anyMatch(r -> r.toString().contains("below the product's minimum 0.45"));
        // liveness and the PAN on camera count only when the product asks for them
        Map<String, Object> noPan = analysis(0.52, false, null, List.of("The PAN card was not shown on camera"));
        assertThat(LAP.judge(noPan).get("status")).isEqualTo("REVIEW");
        VkycPolicy lenient = new VkycPolicy("NONE", 0.363, false, false, 24, "00:00", "23:59", 3, List.of("OPERATIONS"), List.of(), List.of("en"));
        assertThat(lenient.judge(noPan).get("status")).isEqualTo("PASS");
        // picture quality always counts
        assertThat(lenient.judge(analysis(0.52, true, null, List.of("Video too dark in some frames"))).get("status")).isEqualTo("REVIEW");
    }

    @Test
    void callHoursAreIndiaTime() {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        assertThat(LAP.withinHours(ZonedDateTime.of(2026, 10, 14, 10, 0, 0, 0, ist))).isTrue();
        assertThat(LAP.withinHours(ZonedDateTime.of(2026, 10, 14, 21, 30, 0, 0, ist))).isFalse();
        // 03:00 UTC is 08:30 in India: before the 09:00 start
        assertThat(LAP.withinHours(ZonedDateTime.of(2026, 10, 14, 3, 0, 0, 0, ZoneId.of("UTC")))).isFalse();
        assertThat(VkycPolicy.DEFAULT.allTheTime()).isTrue();
    }

    @Test
    void partiesNeedACallAsTheProductSays() {
        assertThat(LAP.partyNeedsCall("VERIFIED")).isTrue();
        assertThat(VkycPolicy.DEFAULT.partyNeedsCall("VERIFIED")).isFalse();
        assertThat(VkycPolicy.DEFAULT.partyNeedsCall("REVIEW")).isTrue();
        VkycPolicy none = new VkycPolicy("NONE", 0.363, true, true, 24, "00:00", "23:59", 3, List.of("OPERATIONS"), List.of(), List.of("en"));
        assertThat(none.partyNeedsCall("REVIEW")).isFalse();
    }

    @Test
    void validationAndDefaults() {
        assertThat(VkycPolicy.validated(Json.read(Json.write(LAP), VkycPolicy.class))).isEqualTo(LAP);
        assertThat(VkycPolicy.parse(null)).isEqualTo(VkycPolicy.DEFAULT);
        assertThat(VkycPolicy.parse("{}").maxAttempts()).isEqualTo(3);
        assertThatThrownBy(() -> VkycPolicy.validated(new VkycPolicy("SOME", 0.4, true, true, 24, "09:00", "18:00", 3, List.of("OPERATIONS"), List.of(), List.of("en"))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> VkycPolicy.validated(new VkycPolicy("ALL", 0.95, true, true, 24, "09:00", "18:00", 3, List.of("OPERATIONS"), List.of(), List.of("en"))))
                .hasMessageContaining("between 0.30 and 0.80");
        assertThatThrownBy(() -> VkycPolicy.validated(new VkycPolicy("ALL", 0.4, true, true, 24, "18:00", "09:00", 3, List.of("OPERATIONS"), List.of(), List.of("en"))))
                .hasMessageContaining("start before");
        assertThatThrownBy(() -> VkycPolicy.validated(new VkycPolicy("ALL", 0.4, true, true, 24, "09:00", "18:00", 3, List.of("SALES"), List.of(), List.of("en"))))
                .hasMessageContaining("Officials can be");
    }
}
