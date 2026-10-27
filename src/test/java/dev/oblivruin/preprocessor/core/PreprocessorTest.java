package dev.oblivruin.preprocessor.core;

import dev.oblivruin.preprocessor.Keywords;
import dev.oblivruin.preprocessor.LinePolicy;
import dev.oblivruin.preprocessor.api.DirectiveContext;
import dev.oblivruin.preprocessor.api.DirectiveHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the preprocessing engine. */
class PreprocessorTest {

    private static Preprocessor engine(Map<String, Object> vars) {
        return Preprocessor.builder().variables(vars).build();
    }

    private static Map<String, Object> vars(String... pairs) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static Map<String, Keywords> keywordsMap(String key, Keywords value) {
        Map<String, Keywords> map = new LinkedHashMap<String, Keywords>();
        map.put(key, value);
        return map;
    }

    private static Map<String, String> remapMap(String key, String value) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put(key, value);
        return map;
    }

    private static List<String> lines(String... lines) {
        return new ArrayList<String>(Arrays.asList(lines));
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    private static String join(String... lines) {
        return String.join("\n", lines);
    }

    @Test
    void basicIfElse() {
        List<String> result = engine(vars("flag", "1")).processLines("Test.java", "Test.java",
                lines(
                        "class Test {",
                        "    //#if flag",
                        "    int a = 1;",
                        "    //#else",
                        "    int a = 2;",
                        "    //#endif",
                        "}"), null);
        assertEquals(join(
                "class Test {",
                "    //#if flag",
                "    int a = 1;",
                "    //#else",
                "    //$$ int a = 2;",
                "    //#endif",
                "}"), join(result));
    }

    @Test
    void inactiveBranchIsCommentedNotRemoved() {
        List<String> result = engine(vars("flag", "0")).processLines("Test.java", "Test.java",
                lines(
                        "    //#if flag",
                        "    int a = 1;",
                        "    //#endif",
                        "    int b = 3;"), null);
        assertEquals(join(
                "    //#if flag",
                "    //$$ int a = 1;",
                "    //#endif",
                "    int b = 3;"), join(result));
    }

    @Test
    void evalLinesAreActivated() {
        List<String> result = engine(vars("flag", "1")).processLines("Test.java", "Test.java",
                lines(
                        "    //#if flag",
                        "    //$$ int a = 1;",
                        "    //#else",
                        "    int a = 2;",
                        "    //#endif"), null);
        assertEquals(join(
                "    //#if flag",
                "    int a = 1;",
                "    //#else",
                "    //$$ int a = 2;",
                "    //#endif"), join(result));
    }

    @Test
    void elseifChain() {
        List<String> result = engine(vars("v", "2")).processLines("Test.java", "Test.java",
                lines(
                        "//#if v == 1",
                        "int a = 1;",
                        "//#elseif v == 2",
                        "int a = 2;",
                        "//#else",
                        "int a = 3;",
                        "//#endif"), null);
        assertEquals(join(
                "//#if v == 1",
                "//$$ int a = 1;",
                "//#elseif v == 2",
                "int a = 2;",
                "//#else",
                "//$$ int a = 3;",
                "//#endif"), join(result));
    }

    @Test
    void elseIfTwoWordForm() {
        // the condition of the two word form must be respected (it opens an elseif branch)
        List<String> hit = engine(vars("v", "2")).processLines("Test.java", "Test.java",
                lines(
                        "//#if v == 1",
                        "one();",
                        "//#else if v == 2",
                        "two();",
                        "//#endif"), null);
        String hitText = join(hit);
        assertTrue(hitText.contains("two();"));
        assertTrue(hitText.contains("//$$ one();"));

        // v = 5 satisfies neither branch: the whole block must be inactive
        List<String> miss = engine(vars("v", "5")).processLines("Test.java", "Test.java",
                lines(
                        "//#if v == 1",
                        "one();",
                        "//#else if v == 2",
                        "two();",
                        "//#endif"), null);
        String missText = join(miss);
        assertTrue(missText.contains("//$$ one();"));
        assertTrue(missText.contains("//$$ two();"));
    }

    @Test
    void suffixKeywordsRequireDotBoundary() {
        // a "son" keyword entry must not hijack ".json" files, and "json" must not match
        // a bare name like "foojson" that merely ends with the letters
        Keywords sonKeywords = new Keywords("//@", "if", "elseif", "else", "endif", "//$$");
        Preprocessor looseSuffix = Preprocessor.builder()
                .variables(vars("flag", "0"))
                .keywordsBySuffix(keywordsMap("son", sonKeywords))
                .build();
        List<String> source = lines(
                "//@if flag",
                "int b;",
                "//@endif");
        // "a.json" is not a ".son" file: the //@ directives are plain comments
        assertEquals(join(source), join(looseSuffix.processLines("a.json", "a.json",
                new ArrayList<String>(source), null)));
        // "foojson" does not end with ".json": default keywords apply, so //@if is inert
        Preprocessor jsonSuffix = Preprocessor.builder()
                .variables(vars("flag", "0"))
                .keywordsBySuffix(keywordsMap("json", sonKeywords))
                .build();
        assertEquals(join(source), join(jsonSuffix.processLines("foojson", "foojson",
                new ArrayList<String>(source), null)));
        // the suffix still matches when it is dot bounded
        assertEquals(join("//@if flag", "//$$ int b;", "//@endif"), join(jsonSuffix.processLines("a.json", "a.json",
                new ArrayList<String>(source), null)));
    }

    @Test
    void contextWarnToleratesMissingWarningSink() {
        Map<String, DirectiveHandler> directives = new LinkedHashMap<String, DirectiveHandler>();
        directives.put("mark", new DirectiveHandler() {
            @Override
            public void handle(DirectiveContext ctx) {
                ctx.warn("some warning"); // must not throw when the sink is null
            }
        });
        Preprocessor custom = Preprocessor.builder().directives(directives).build();
        assertDoesNotThrow(() -> custom.processLines("Test.java", "Test.java",
                lines("//#mark", "int a;"), null));
    }

    @Test
    void remapperIgnoresEmptyKey() {
        Map<String, String> remapper = new LinkedHashMap<String, String>();
        remapper.put("", "X"); // must be ignored, not interleaved between every character
        remapper.put("OLD", "NEW");
        Preprocessor custom = Preprocessor.builder().remapper(remapper).build();
        List<String> result = custom.processLines("Test.java", "Test.java",
                lines("OLD value;"), null);
        assertEquals("NEW value;", join(result));
        assertTrue(!join(result).contains("X"));
    }

    @Test
    void nestedBlocks() {
        List<String> result = engine(vars("a", "1", "b", "0")).processLines("Test.java", "Test.java",
                lines(
                        "//#if a",
                        "outer();",
                        "//#if b",
                        "inner();",
                        "//#endif",
                        "afterInner();",
                        "//#else",
                        "fallback();",
                        "//#endif"), null);
        assertEquals(join(
                "//#if a",
                "outer();",
                "//#if b",
                "//$$ inner();",
                "//#endif",
                "afterInner();",
                "//#else",
                "//$$ fallback();",
                "//#endif"), join(result));
    }

    @Test
    void conditionsInDeadBranchesAreNotEvaluated() {
        // strict mode would reject "undefinedVar" if the dead branch were evaluated
        Preprocessor strictEngine = Preprocessor.builder().variables(vars("outer", "0")).strict(true).build();
        List<String> result = strictEngine.processLines("Test.java", "Test.java",
                lines(
                        "//#if outer",
                        "x();",
                        "//#if undefinedVar",
                        "y();",
                        "//#endif",
                        "//#endif"), null);
        assertEquals(6, result.size());
    }

    @Test
    void blankAndRemovePolicies() {
        List<String> input = lines(
                "keep();",
                "//#if a",
                "live();",
                "//#else",
                "dead();",
                "//#endif");
        List<String> blank = Preprocessor.builder().variables(vars("a", "1")).policy(LinePolicy.BLANK).build()
                .processLines("t.txt", "t.txt", input, null);
        assertEquals(join("keep();", "", "live();", "", "", ""), join(blank));

        List<String> removed = Preprocessor.builder().variables(vars("a", "1")).policy(LinePolicy.REMOVE).build()
                .processLines("t.txt", "t.txt", input, null);
        assertEquals(join("keep();", "live();"), join(removed));
    }

    @Test
    void removePolicyMimicsToCraftRemoveComments() {
        List<String> removed = Preprocessor.builder().variables(vars("a", "0")).policy(LinePolicy.REMOVE).build()
                .processLines("t.txt", "t.txt",
                        lines("//@top", "//#if a", "dead();", "//#endif", "after();"), null);
        assertEquals(join("//@top", "after();"), join(removed));
    }

    @Test
    void keywordsPerFileSuffix() {
        Preprocessor custom = Preprocessor.builder()
                .variables(vars("a", "1"))
                .keywordsBySuffix(keywordsMap("json", Keywords.DEFAULT))
                .build();
        List<String> result = custom.processLines("mixins.json", "mixins.json",
                lines(
                        "{",
                        "  \"package\": \"test\",",
                        "  //#if a",
                        "  \"a\": true,",
                        "  //#else",
                        "  \"a\": false,",
                        "  //#endif",
                        "  \"b\": true",
                        "}"), null);
        String text = join(result);
        assertTrue(text.contains("\"a\": true"));
        assertTrue(text.contains("  //$$ \"a\": false,"));
        assertTrue(text.contains("\"b\": true"));
    }

    @Test
    void customKeywordPrefix() {
        Keywords bracket = new Keywords("[@", "if", "elseif", "else", "endif", "[@$");
        Preprocessor custom = Preprocessor.builder()
                .variables(vars("a", "1"))
                .keywordsBySuffix(keywordsMap("txt", bracket))
                .build();
        List<String> result = custom.processLines("file.txt", "file.txt",
                lines("[@if a", "alive();", "[@else", "dead();", "[@endif"), null);
        assertEquals(join("[@if a", "alive();", "[@else", "[@$ dead();", "[@endif"), join(result));
    }

    @Test
    void nestedIfBlocksTwoLevels() {
        // A=1, B=0, C=1: the inner else branch is live, the inner if branch is commented
        List<String> liveB0 = engine(vars("A", "1", "B", "0", "C", "1")).processLines("t.java", "t.java",
                lines(
                        "//#if A",
                        "a1();",
                        "//#if B",
                        "b1();",
                        "//#else",
                        "b2();",
                        "//#endif",
                        "//#if C",
                        "c1();",
                        "//#endif",
                        "a2();",
                        "//#endif"), null);
        assertEquals(join(
                "//#if A",
                "a1();",
                "//#if B",
                "//$$ b1();",
                "//#else",
                "b2();",
                "//#endif",
                "//#if C",
                "c1();",
                "//#endif",
                "a2();",
                "//#endif"), join(liveB0));

        // A=0: the whole outer branch is dead; inner conditions are inert and inner
        // content is commented out, directive lines are kept
        List<String> deadA = engine(vars("A", "0", "B", "1", "C", "1")).processLines("t.java", "t.java",
                lines(
                        "//#if A",
                        "a1();",
                        "//#if B",
                        "b1();",
                        "//#else",
                        "b2();",
                        "//#endif",
                        "//#if C",
                        "c1();",
                        "//#endif",
                        "a2();",
                        "//#endif"), null);
        assertEquals(join(
                "//#if A",
                "//$$ a1();",
                "//#if B",
                "//$$ b1();",
                "//#else",
                "//$$ b2();",
                "//#endif",
                "//#if C",
                "//$$ c1();",
                "//#endif",
                "//$$ a2();",
                "//#endif"), join(deadA));
    }

    @Test
    void nestedIfBlocksThreeLevels() {
        List<String> result = engine(vars("A", "1", "B", "1", "C", "0")).processLines("t.java", "t.java",
                lines(
                        "//#if A",
                        "    //#if B",
                        "        //#if C",
                        "        deep();",
                        "        //#endif",
                        "    //#endif",
                        "//#endif"), null);
        assertEquals(join(
                "//#if A",
                "    //#if B",
                "        //#if C",
                "        //$$ deep();",
                "        //#endif",
                "    //#endif",
                "//#endif"), join(result));
    }

    @Test
    void nestedBlocksPreserveLineCount() {
        List<String> input = lines(
                "class T {",
                "    //#if A",
                "    void a() {",
                "        //#if B",
                "        x();",
                "        //#else",
                "        y();",
                "        //#endif",
                "    }",
                "    //#else",
                "    void b() {",
                "    }",
                "    //#endif",
                "}");
        List<String> result = engine(vars("A", "1", "B", "1")).processLines("T.java", "T.java", input, null);
        assertEquals(input.size(), result.size(), "COMMENT policy must keep the line count stable for nested blocks");
        assertTrue(join(result).contains("        x();"));
        assertTrue(join(result).contains("        //$$ y();"));
        assertTrue(join(result).contains("    //$$ void b() {"));
    }

    @Test
    void nestedEvalLinesInInnerBranches() {
        Preprocessor engine = engine(vars("A", "1", "B", "0"));
        List<String> result = engine.processLines("t.java", "t.java",
                lines(
                        "//#if A",
                        "//$$ outerLive();",
                        "//#if B",
                        "//$$ innerLive();",
                        "//#endif",
                        "//#endif"), null);
        assertEquals(join(
                "//#if A",
                "outerLive();",
                "//#if B",
                "//$$ innerLive();",
                "//#endif",
                "//#endif"), join(result));

        // with A=0 both eval lines stay commented
        List<String> dead = engine(vars("A", "0", "B", "1")).processLines("t.java", "t.java",
                lines(
                        "//#if A",
                        "//$$ outerLive();",
                        "//#if B",
                        "//$$ innerLive();",
                        "//#endif",
                        "//#endif"), null);
        assertEquals(join(
                "//#if A",
                "//$$ outerLive();",
                "//#if B",
                "//$$ innerLive();",
                "//#endif",
                "//#endif"), join(dead));
    }

    @Test
    void nestedBlocksInsideDeadBranchesAreInert() {
        // the inner elseif conditions must never be evaluated while the outer branch is dead
        Preprocessor strictEngine = Preprocessor.builder().variables(vars("A", "0")).strict(true).build();
        List<String> result = strictEngine.processLines("t.java", "t.java",
                lines(
                        "//#if A",
                        "x();",
                        "//#if B",
                        "y();",
                        "//#elseif unknownVariable1",
                        "w();",
                        "//#else",
                        "z();",
                        "//#endif",
                        "//#if unknownVariable2",
                        "v();",
                        "//#endif",
                        "//#endif"), null);
        assertEquals(13, result.size());
        assertTrue(join(result).contains("//$$ x();"));
        assertTrue(join(result).contains("//$$ z();"));
    }

    @Test
    void customBlockDirectivesNestedWithBuiltinIf() {
        Map<String, dev.oblivruin.preprocessor.api.DirectiveHandler> handlers = new LinkedHashMap<>();
        handlers.put("beginx", context -> context.blocks().beginBranch(true));
        handlers.put("endx", context -> context.blocks().endBranch());
        Preprocessor engine = Preprocessor.builder().directives(handlers).build();
        List<String> result = engine.processLines("t.txt", "t.txt",
                lines(
                        "//#beginx",
                        "outer();",
                        "//#if B",
                        "inner();",
                        "//#endif",
                        "//#endx"), null);
        assertEquals(join(
                "//#beginx",
                "outer();",
                "//#if B",
                "//$$ inner();",
                "//#endif",
                "//#endx"), join(result));
    }

    @Test
    void nestedBlocksWithBlankPolicy() {
        List<String> result = Preprocessor.builder().variables(vars("A", "1", "B", "0"))
                .policy(LinePolicy.BLANK).build()
                .processLines("t.txt", "t.txt",
                        lines(
                                "keep();",
                                "//#if A",
                                "a();",
                                "//#if B",
                                "b();",
                                "//#endif",
                                "//#endif"), null);
        assertEquals(join("keep();", "", "a();", "", "", "", ""), join(result));
    }

    @Test
    void preprocessingIsIdempotentForNestedBlocks() {
        List<String> input = lines(
                "//#if A",
                "a1();",
                "//#if B",
                "b1();",
                "//#else",
                "b2();",
                "//#endif",
                "//#endif");
        Preprocessor engine = engine(vars("A", "1", "B", "0"));
        List<String> first = engine.processLines("t.txt", "t.txt", input, null);
        List<String> second = engine.processLines("t.txt", "t.txt", first, null);
        assertEquals(join(first), join(second), "preprocessing the processed output must be a no-op");
    }

    @Test
    void missingInnerEndifFails() {
        ProcessingException exception = assertThrows(ProcessingException.class,
                () -> engine(vars("A", "1")).processLines("t.txt", "t.txt",
                        lines(
                                "//#if A",
                                "//#if B",
                                "x();",
                                "//#endif"), null));
        assertTrue(exception.getMessage().startsWith("missing '//#endif'"));
    }

    @Test
    void variableAssignmentAndShadowing() {
        Preprocessor engine = engine(vars("base", "0"));
        List<String> result = engine.processLines("t.txt", "t.txt",
                lines(
                        "//#var local = base",
                        "//#if local",
                        "never();",
                        "//#endif",
                        "//#var local2 = 5",
                        "//#if local2 == 5",
                        "assigned();",
                        "//#endif",
                        "//#unset local2",
                        "//#if local2",
                        "never();",
                        "//#endif"), null);
        String text = join(result);
        assertTrue(text.contains("assigned();"));
        assertTrue(text.contains("//$$ never();"));
    }

    @Test
    void varStringAssignment() {
        List<String> result = engine(vars()).processLines("t.txt", "t.txt",
                lines(
                        "//#var name = \"hello\"",
                        "//#if name == \"hello\"",
                        "yes();",
                        "//#endif"), null);
        assertTrue(join(result).contains("yes();"));
    }

    @Test
    void warningAndErrorDirectives() {
        List<String> warnings = new ArrayList<String>();
        engine(vars("a", "1")).processLines("t.txt", "t.txt",
                lines("//#warning something is odd", "//#if a", "ok();", "//#endif"), warnings);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("something is odd"));

        assertThrows(ProcessingException.class, () -> engine(vars("a", "1")).processLines("t.txt", "t.txt",
                lines("//#if a", "//#error custom failure", "//#endif"), null));
        // an error inside a dead branch must not fire
        assertDoesNotThrow(() -> engine(vars("a", "0")).processLines("t.txt", "t.txt",
                lines("//#if a", "//#error custom failure", "//#endif"), null));
    }

    @Test
    void unknownDirectivesArePassedThrough() {
        List<String> result = engine(vars()).processLines("t.txt", "t.txt",
                lines("//#totally-unknown argument", "plain();"), null);
        assertEquals(join("//#totally-unknown argument", "plain();"), join(result));
    }

    @Test
    void missingEndifFails() {
        ProcessingException exception = assertThrows(ProcessingException.class,
                () -> engine(vars()).processLines("t.txt", "t.txt", lines("//#if a", "x();"), null));
        assertTrue(exception.getMessage().contains("t.txt"));
        assertTrue(exception.getMessage().contains("endif"));
    }

    @Test
    void elseAfterElseFails() {
        assertThrows(ProcessingException.class, () -> engine(vars()).processLines("t.txt", "t.txt",
                lines("//#if a", "//#else", "//#else", "//#endif"), null));
    }

    @Test
    void elseifWithoutIfFails() {
        assertThrows(ProcessingException.class, () -> engine(vars()).processLines("t.txt", "t.txt",
                lines("//#elseif a", "//#endif"), null));
    }

    @Test
    void strictUnknownVariableFails() {
        Preprocessor strict = Preprocessor.builder().variables(vars()).strict(true).build();
        assertThrows(ProcessingException.class, () -> strict.processLines("t.txt", "t.txt",
                lines("//#if nope", "x();", "//#endif"), null));
    }

    @Test
    void customDirectiveReplacesLine() {
        Map<String, dev.oblivruin.preprocessor.api.DirectiveHandler> handlers = new LinkedHashMap<>();
        handlers.put("say", context -> context.replaceLine("// [say] " + context.argument()));
        Preprocessor engine = Preprocessor.builder().directives(handlers).build();
        List<String> result = engine.processLines("t.txt", "t.txt",
                lines("//#say hello world", "plain();"), null);
        assertEquals(join("// [say] hello world", "plain();"), join(result));
    }

    @Test
    void customDirectiveEmitsLines() {
        Map<String, dev.oblivruin.preprocessor.api.DirectiveHandler> handlers = new LinkedHashMap<>();
        handlers.put("twice", context -> {
            context.emit(context.argument());
            context.emit(context.argument());
        });
        Preprocessor engine = Preprocessor.builder().directives(handlers).build();
        List<String> result = engine.processLines("t.txt", "t.txt",
                lines("before();", "//#twice middle();", "after();"), null);
        // the directive line itself is kept per the COMMENT policy
        assertEquals(join("before();", "middle();", "middle();", "//#twice middle();", "after();"), join(result));
    }

    @Test
    void customBlockDirectives() {
        Map<String, dev.oblivruin.preprocessor.api.DirectiveHandler> handlers = new LinkedHashMap<>();
        handlers.put("beginx", context -> context.blocks().beginBranch(false));
        handlers.put("endx", context -> context.blocks().endBranch());
        Preprocessor engine = Preprocessor.builder().directives(handlers).build();
        List<String> result = engine.processLines("t.txt", "t.txt",
                lines(
                        "//#beginx",
                        "suppressed();",
                        "//#endx",
                        "visible();"), null);
        assertEquals(join(
                "//#beginx",
                "//$$ suppressed();",
                "//#endx",
                "visible();"), join(result));
    }

    @Test
    void customDirectiveSeesVariables() {
        Map<String, dev.oblivruin.preprocessor.api.DirectiveHandler> handlers = new LinkedHashMap<>();
        handlers.put("setx", context -> {
            context.variables().set("dyn", "1");
            context.dropLine();
        });
        Preprocessor engine = Preprocessor.builder().directives(handlers).build();
        List<String> result = engine.processLines("t.txt", "t.txt",
                lines(
                        "//#setx",
                        "//#if dyn",
                        "dynamic();",
                        "//#endif"), null);
        assertTrue(join(result).contains("dynamic();"));
    }

    @Test
    void variableScopeDoesNotLeakBetweenFiles() {
        List<String> first = engine(vars()).processLines("a.txt", "a.txt",
                lines("//#var scoped = 1", "//#if scoped", "yes();", "//#endif"), null);
        assertTrue(join(first).contains("yes();"));
        List<String> second = engine(vars()).processLines("b.txt", "b.txt",
                lines("//#if scoped", "yes();", "//#endif"), null);
        assertTrue(join(second).contains("//$$ yes();"));
    }

    @Test
    void processContentPreservesLineEndings() {
        String crlf = "//#if a\r\nlive();\r\n//#else\r\ndead();\r\n//#endif";
        String result = engine(vars("a", "1")).processContent("t.txt", "t.txt", crlf, null);
        assertTrue(result.contains("\r\n"));
        assertTrue(result.contains("live();"));
        assertTrue(result.contains("//$$ dead();"));
        assertTrue(result.endsWith("//#endif"));
    }

    @Test
    void remapperReplacesAllOccurrences() {
        Preprocessor engine = Preprocessor.builder()
                .remapper(remapMap("OLD_NAME", "NEW_NAME"))
                .build();
        List<String> result = engine.processLines("t.txt", "t.txt",
                lines("OLD_NAME and OLD_NAME again"), null);
        assertEquals("NEW_NAME and NEW_NAME again", result.get(0));
    }

    @Test
    void emptyLinesInsideInactiveBranchKeepIndentation() {
        List<String> result = engine(vars("a", "0")).processLines("Test.java", "Test.java",
                lines("    //#if a", "", "    int a = 1;", "    //#endif"), null);
        assertEquals(join(
                "    //#if a",
                "    //$$",
                "    //$$ int a = 1;",
                "    //#endif"), join(result));
    }

    @Test
    void directiveArgumentsWithParentheses() {
        List<String> result = engine(vars("a", "1", "b", "0")).processLines("t.txt", "t.txt",
                lines("//#if (a == 1) && (!b)", "ok();", "//#endif"), null);
        assertTrue(join(result).contains("ok();"));
    }

    @Test
    void malformedVariableAssignmentFails() {
        assertThrows(ProcessingException.class, () -> engine(vars()).processLines("t.txt", "t.txt",
                lines("//#var = broken"), null));
    }
}
