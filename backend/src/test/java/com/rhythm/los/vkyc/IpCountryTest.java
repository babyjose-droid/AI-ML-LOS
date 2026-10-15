package com.rhythm.los.vkyc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IpCountryTest {
    @Test
    void looksUpRangesAndPrivateAddresses() {
        IpCountry t = new IpCountry(IpCountry.SAMPLES);
        assertThat(t.size()).isEqualTo(4);
        assertThat(t.lookup("203.0.113.9").country()).isEqualTo("IN");
        assertThat(t.lookup("198.51.100.20").country()).isEqualTo("GB");
        assertThat(t.lookup("198.51.100.20").outsideIndia()).isTrue();
        assertThat(t.lookup("8.8.8.8").country()).isEqualTo("US");
        assertThat(t.lookup("127.0.0.1").country()).isEqualTo("PRIVATE");
        assertThat(t.lookup("192.168.1.4").outsideIndia()).isFalse();
        assertThat(t.lookup("::1").country()).isEqualTo("PRIVATE");
        assertThat(t.lookup("::ffff:198.51.100.7").country()).isEqualTo("GB");
        assertThat(t.lookup("5.6.7.8").country()).isNull();
        assertThat(t.lookup("not-an-ip").country()).isNull();
        assertThat(new IpCountry(List.of("# comment", "bad", "1.2.3.0/33,XX", "49.32.0.0/11,in")).lookup("49.40.1.1").country()).isEqualTo("IN");
    }
}
