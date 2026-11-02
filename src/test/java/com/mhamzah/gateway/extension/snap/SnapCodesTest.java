package com.mhamzah.gateway.extension.snap;

import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.extension.ExecutionContext;
import com.mhamzah.gateway.mapping.JsonValues;
import com.mhamzah.gateway.mapping.LookupTable;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class SnapCodesTest {

    private static ExecutionContext ctx(Map<String, String> catalog) {
        Map<String, tools.jackson.databind.JsonNode> entries = new java.util.HashMap<>();
        catalog.forEach((k, v) -> entries.put(k, JsonValues.parseLiteral(v)));
        return new ExecutionContext("F", "c", Map.of(SnapCodes.CATALOG, new LookupTable(SnapCodes.CATALOG, entries, null)),
                null);
    }

    @Test
    void keyKeepsTwoDecimalsOfAValueWrittenAsANumber() {
        // a target value 400.10 without quotes is read as the number 400.1
        assertThat(SnapCodes.key(JsonValues.parseLiteral("400.10"))).isEqualTo("400.10");
        assertThat(SnapCodes.key(JsonValues.parseLiteral("403.14"))).isEqualTo("403.14");
        assertThat(SnapCodes.key(JsonNodeFactory.instance.stringNode(" 404.01 "))).isEqualTo("404.01");
        assertThat(SnapCodes.key(null)).isNull();
    }

    @Test
    void catalogMessageAsTextOrObjectAndBuiltInsWithoutRows() {
        ExecutionContext ctx = ctx(Map.of("403.14", "Saldo tidak cukup", "404.01", "{\"message\": \"Transaction Not Found\"}"));

        assertThat(SnapCodes.find("403.14", ctx)).isEqualTo(new SnapCodes.Code(403, "14", "Saldo tidak cukup"));
        assertThat(SnapCodes.find("404.01", ctx).message()).isEqualTo("Transaction Not Found");
        assertThat(SnapCodes.find("504.00", ctx).message()).isEqualTo("Timeout");
        assertThat(SnapCodes.find("409.99", ctx).message()).isEmpty();
        assertThat(SnapCodes.find("4031", ctx)).isNull();
        assertThat(SnapCodes.require("nonsense", ctx).responseCode("24")).isEqualTo("5002401");
    }

    @Test
    void responseCodeIsStatusServiceAndCase() {
        assertThat(new SnapCodes.Code(403, "14", "").responseCode("24")).isEqualTo("4032414");
        assertThat(new SnapCodes.Code(403, "14", "").responseCode(null)).isEqualTo("4030014");
    }

    @Test
    void placeholdersAreFilledOrDropped() {
        assertThat(SnapCodes.fill("Invalid Mandatory Field {field name}", "amount.value", null))
                .isEqualTo("Invalid Mandatory Field amount.value");
        assertThat(SnapCodes.fill("Invalid Mandatory Field {field name}", null, null)).isEqualTo("Invalid Mandatory Field");
        assertThat(SnapCodes.fill("Unauthorized. [reason]", null, "Invalid Client")).isEqualTo("Unauthorized. Invalid Client");
        assertThat(SnapCodes.fill("Transaction Not Permitted.[reason]", null, null)).isEqualTo("Transaction Not Permitted.");
    }
}
