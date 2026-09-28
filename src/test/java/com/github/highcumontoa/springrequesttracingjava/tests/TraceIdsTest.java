package com.github.highcumontoa.springrequesttracingjava.tests;

import com.github.highcumontoa.springrequesttracingjava.tracing.TraceIds;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdsTest {

    @Test
    void generatedIdsAreUniqueAndWellFormed() {
        long distinct = IntStream.range(0, 10_000)
                .mapToObj(i -> TraceIds.generate())
                .peek(id -> assertThat(id).hasSize(32).matches("[0-9a-f]{32}"))
                .distinct()
                .count();
        assertThat(distinct).isEqualTo(10_000L);
    }

    @Test
    void validInboundIdsAreAccepted() {
        assertThat(TraceIds.isValid(UUID.randomUUID().toString().replace("-", ""))).isTrue();
        assertThat(TraceIds.isValid("upstream-ABC_123.xyz")).isTrue();
    }

    @Test
    void invalidInboundIdsAreRejected() {
        assertThat(TraceIds.isValid(null)).isFalse();
        assertThat(TraceIds.isValid("short")).isFalse();                 // too short
        assertThat(TraceIds.isValid("x".repeat(65))).isFalse();          // too long
        assertThat(TraceIds.isValid("bad id with spaces")).isFalse();
        assertThat(TraceIds.isValid("line\nbreak")).isFalse();
    }

    @Test
    void resolveKeepsValidInboundIdOtherwiseGenerates() {
        String inbound = "upstream-0123456789";
        assertThat(TraceIds.resolve(inbound)).isEqualTo(inbound);
        assertThat(TraceIds.resolve(null)).hasSize(32);
        assertThat(TraceIds.resolve("bad id")).hasSize(32);
    }
}
