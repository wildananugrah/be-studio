package com.mhamzah.gateway.extension.custom;

import com.mhamzah.gateway.extension.ExecutionContext;
import com.mhamzah.gateway.extension.FieldHandler;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Example {@link FieldHandler}: formats an amount as Indonesian Rupiah text, e.g. {@code 1500000.00 -> "Rp1.500.000,00"}.
 *
 * <p>Use it on a mapping rule: {@code gw_mapping_rule.field_handler = 'idrAmountFormatter'}. Accepts a JSON number
 * or numeric text; a missing value stays missing; anything else fails the request with HANDLER_ERROR.
 */
@Component("idrAmountFormatter")
public class IdrAmountFormatter implements FieldHandler {

    @Override
    public JsonNode handle(JsonNode value, ExecutionContext ctx) {
        if (value == null || value.isNull()) {
            return null;
        }
        BigDecimal amount;
        try {
            amount = value.isNumber() ? value.decimalValue() : new BigDecimal(value.asString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not an amount: '" + value.asString() + "'", e);
        }
        // DecimalFormat is not thread-safe and handlers run concurrently, so create one per call
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setGroupingSeparator('.');
        symbols.setDecimalSeparator(',');
        DecimalFormat format = new DecimalFormat("#,##0.00", symbols);
        format.setRoundingMode(RoundingMode.HALF_UP);
        String text = (amount.signum() < 0 ? "-Rp" : "Rp") + format.format(amount.abs());
        return JsonNodeFactory.instance.stringNode(text);
    }
}
