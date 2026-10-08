package com.mhamzah.gateway.condition;

import com.mhamzah.gateway.extension.ErrorType;
import com.mhamzah.gateway.extension.ExecutionContext;
import com.mhamzah.gateway.extension.GatewayError;
import com.mhamzah.gateway.mapping.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import tools.jackson.databind.JsonNode;

/**
 * A step condition or success expression (spec Section 6.5): restricted SpEL where {@code ${path}}
 * placeholders refer to context paths, e.g. {@code ${steps.inquiry.body.status} == 'ACTIVE'}.
 *
 * <p>Only literals, comparisons, boolean logic, arithmetic, ternary/elvis and {@code matches} are allowed.
 * Type references, constructors, bean references, method calls, property access and assignment are
 * rejected at compile time; evaluation additionally runs in a read-only {@link SimpleEvaluationContext}.
 */
public final class Condition {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)}");
    private static final Pattern VARIABLE = Pattern.compile("#p(\\d+)");
    private static final SpelExpressionParser PARSER = new SpelExpressionParser();
    private static final Set<String> ALLOWED_NODES = Set.of(
            "OpEQ", "OpNE", "OpLT", "OpGT", "OpLE", "OpGE",
            "OpAnd", "OpOr", "OperatorNot",
            "OpPlus", "OpMinus", "OpMultiply", "OpDivide", "OpModulus",
            "Ternary", "Elvis", "OperatorMatches", "OperatorBetween",
            "StringLiteral", "IntLiteral", "LongLiteral", "RealLiteral", "FloatLiteral",
            "BooleanLiteral", "NullLiteral", "VariableReference");

    private final String text;
    private final SpelExpression expression;
    private final List<JsonPath> references;

    private Condition(String text, SpelExpression expression, List<JsonPath> references) {
        this.text = text;
        this.expression = expression;
        this.references = List.copyOf(references);
    }

    public static Condition compile(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Condition is empty");
        }
        List<JsonPath> refs = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder spel = new StringBuilder();
        while (m.find()) {
            refs.add(JsonPath.compile("$." + m.group(1).trim()));
            m.appendReplacement(spel, "#p" + (refs.size() - 1));
        }
        m.appendTail(spel);
        SpelExpression expr;
        try {
            expr = (SpelExpression) PARSER.parseExpression(spel.toString());
        } catch (ParseException e) {
            throw new IllegalArgumentException("Invalid expression '" + text + "': " + e.getSimpleMessage(), e);
        }
        checkAllowed(expr.getAST(), refs.size(), text);
        return new Condition(text, expr, refs);
    }

    private static void checkAllowed(SpelNode node, int variableCount, String text) {
        String kind = node.getClass().getSimpleName();
        if (!ALLOWED_NODES.contains(kind)) {
            throw new IllegalArgumentException("Construct '" + node.toStringAST() + "' (" + kind
                    + ") is not allowed in expression '" + text + "'");
        }
        if (kind.equals("VariableReference")) {
            Matcher v = VARIABLE.matcher(node.toStringAST());
            if (!v.matches() || Integer.parseInt(v.group(1)) >= variableCount) {
                throw new IllegalArgumentException("Variable '" + node.toStringAST()
                        + "' is not allowed; use ${path} placeholders in expression '" + text + "'");
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            checkAllowed(node.getChild(i), variableCount, text);
        }
    }

    /** Context paths referenced by placeholders, in order of appearance. */
    public List<JsonPath> references() {
        return references;
    }

    public boolean evaluate(ExecutionContext ctx) {
        SimpleEvaluationContext ec = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        for (int i = 0; i < references.size(); i++) {
            ec.setVariable("p" + i, toJava(ctx.read(references.get(i))));
        }
        Object result;
        try {
            result = expression.getValue(ec);
        } catch (EvaluationException e) {
            throw failure("Expression '" + text + "' failed: " + e.getMessage(), e);
        }
        if (!(result instanceof Boolean b)) {
            throw failure("Expression '" + text + "' did not return a boolean", null);
        }
        return b;
    }

    private static GatewayError failure(String message, Throwable cause) {
        return GatewayError.of(ErrorType.MAPPING_ERROR).message(message).details(List.of(message)).cause(cause).build();
    }

    private static Object toJava(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            return node.longValue();
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isString()) {
            return node.stringValue();
        }
        return node.toString();
    }

    @Override
    public String toString() {
        return text;
    }
}
