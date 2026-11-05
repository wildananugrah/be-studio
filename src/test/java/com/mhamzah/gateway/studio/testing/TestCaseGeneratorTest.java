package com.mhamzah.gateway.studio.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.studio.testing.TestModel.TestCase;
import java.util.List;
import org.junit.jupiter.api.Test;

class TestCaseGeneratorTest {

    private final RouterFixture fixture = new RouterFixture();

    private List<TestCase> cases(String flow) {
        return TestCaseGenerator.generate(fixture.doc, fixture.flow(flow), "/api");
    }

    @Test
    void unvalidatedRouterGetsNoMissingFieldCases() {
        fixture.build();

        List<TestCase> cases = cases("PAYMENT");

        assertThat(cases).extracting(TestCase::name).noneMatch(n -> n.startsWith("Missing required field"));
        // the happy path sends the first option
        assertThat(cases.getFirst().body().has("fromAccount")).isTrue();
    }

    @Test
    void unvalidatedPlainFlowStillGetsThem() {
        fixture.rows.flow("TRANSFER_NV", "POST", "/transfers-nv",
                f -> f.withSchemas("TRANSFER_REQUEST", null).withSchemaValidation("NONE"));
        fixture.build();

        assertThat(cases("TRANSFER_NV")).extracting(TestCase::name).contains("Missing required field 'fromAccount'");
    }
}
