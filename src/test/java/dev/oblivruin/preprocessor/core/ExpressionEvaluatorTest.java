package dev.oblivruin.preprocessor.core;

import dev.oblivruin.preprocessor.api.Variables;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Tests for the condition expression evaluator. */
class ExpressionEvaluatorTest {

    private static Variables vars(String... pairs) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return new VariableScope(map);
    }

    private static boolean eval(String expression, Variables variables) {
        return ExpressionEvaluator.evaluateBoolean(expression, variables, false, null, "test.java", 1);
    }

    private static Object evaluate(String expression, Variables variables) {
        return ExpressionEvaluator.evaluate(expression, variables, false, null, null, 0);
    }

    private static boolean eval(String expression, Variables variables, List<String> warnings) {
        return ExpressionEvaluator.evaluateBoolean(expression, variables, false, warnings::add, "test.java", 1);
    }

    @Test
    void bareVariableTruthiness() {
        assertTrue(eval("a", vars("a", "1")));
        assertFalse(eval("a", vars("a", "0")));
        assertFalse(eval("missing", vars()));
        assertFalse(eval("a", vars("a", "false")));
        assertTrue(eval("a", vars("a", "anything")));
    }

    @Test
    void numericComparison() {
        assertTrue(eval("a == 1", vars("a", "1")));
        assertTrue(eval("a != 1", vars("a", "2")));
        assertTrue(eval("a > 0", vars("a", "1")));
        assertTrue(eval("a >= 1", vars("a", "1")));
        assertTrue(eval("a < 10", vars("a", "9")));
        assertTrue(eval("a <= 1", vars("a", "1")));
        assertFalse(eval("a == 2", vars("a", "1")));
    }

    @Test
    void stringComparison() {
        assertTrue(eval("a == \"x\"", vars("a", "x")));
        assertTrue(eval("a != \"x\"", vars("a", "y")));
        assertTrue(eval("a == 'single quoted'", vars("a", "single quoted")));
        assertFalse(eval("a == \"x\"", vars("a", "X")));
    }

    @Test
    void versionAwareComparison() {
        // this is what plain lexicographic comparison gets wrong
        assertTrue(eval("mc >= \"1.9\"", vars("mc", "1.16")));
        assertTrue(eval("mc > \"1.9\"", vars("mc", "1.16")));
        assertTrue(eval("mc < \"1.17\"", vars("mc", "1.16.1")));
        assertFalse(eval("mc >= \"1.17\"", vars("mc", "1.16")));
        assertTrue(eval("mc == \"1.16\"", vars("mc", "1.16")));
        assertTrue(eval("a > b", vars("a", "1.0", "b", "1.0-alpha")));
        assertFalse(eval("mc >= 10", vars("mc", "9"))); // version semantics: "9" < "10"
    }

    @Test
    void logicalOperators() {
        assertTrue(eval("a && b", vars("a", "1", "b", "1")));
        assertFalse(eval("a && b", vars("a", "1", "b", "0")));
        assertTrue(eval("a || b", vars("a", "0", "b", "1")));
        assertFalse(eval("a || b", vars("a", "0", "b", "0")));
        // && binds tighter than ||
        assertTrue(eval("a || b && c", vars("a", "1", "b", "0", "c", "0")));
        assertFalse(eval("a && b || c", vars("a", "0", "b", "0", "c", "0")));
    }

    @Test
    void negationAndParentheses() {
        assertTrue(eval("!a", vars("a", "0")));
        assertFalse(eval("!a", vars("a", "1")));
        assertTrue(eval("!(a && b)", vars("a", "1", "b", "0")));
        assertTrue(eval("(a || b) && (c || d)", vars("a", "1", "b", "0", "c", "0", "d", "1")));
    }

    @Test
    void definedOperator() {
        assertTrue(eval("defined a", vars("a", "0")));
        assertFalse(eval("defined b", vars("a", "1")));
        assertTrue(eval("defined a && a == \"x\"", vars("a", "x")));
    }

    @Test
    void unknownVariableWarnsAndEvaluatesToFalse() {
        List<String> warnings = new ArrayList<String>();
        boolean result = eval("unknownVar", vars(), warnings);
        assertFalse(result);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("unknownVar"));
    }

    @Test
    void strictModeRejectsUnknownVariables() {
        assertThrows(ProcessingException.class,
                () -> ExpressionEvaluator.evaluateBoolean("unknownVar", vars(), true, null, "test.java", 3));
    }

    @Test
    void syntaxErrors() {
        assertThrows(ProcessingException.class, () -> eval("a & b", vars("a", "1")));
        assertThrows(ProcessingException.class, () -> eval("a = 1", vars("a", "1")));
        assertThrows(ProcessingException.class, () -> eval("(a", vars("a", "1")));
        assertThrows(ProcessingException.class, () -> eval("\"unterminated", vars()));
        assertThrows(ProcessingException.class, () -> eval("a == ", vars("a", "1")));
    }

    @Test
    void rawValues() {
        Object string = evaluate("\"hello\"", vars());
        assertEquals("hello", string);
        Object number = evaluate("42", vars());
        assertEquals(42, ((Number) number).intValue());
        Object variable = evaluate("a", vars("a", "value"));
        assertEquals("value", variable);
        Object bool = evaluate("!a", vars("a", "0"));
        assertEquals(Boolean.TRUE, bool);
    }

    @Test
    void emptyConditionIsFalse() {
        assertFalse(eval("   ", vars()));
    }

    @Test
    void definedIsKeywordNotVariable() {
        // "defined" is only special when used as an operator; a bare "defined" is a user error
        assertThrows(ProcessingException.class, () -> eval("defined", vars()));
        assertTrue(eval("defined defined", vars("defined", "1")));
    }
}
