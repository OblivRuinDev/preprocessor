package dev.oblivruin.preprocessor.core;

import dev.oblivruin.preprocessor.Keywords;
import dev.oblivruin.preprocessor.LinePolicy;
import dev.oblivruin.preprocessor.api.BlockControl;
import dev.oblivruin.preprocessor.api.DirectiveContext;
import dev.oblivruin.preprocessor.api.DirectiveHandler;
import dev.oblivruin.preprocessor.api.ProcessorContext;
import dev.oblivruin.preprocessor.api.TextProcessor;
import dev.oblivruin.preprocessor.api.Variables;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The preprocessing engine. Scans a file line by line, executes the built-in
 * directives ({@code if}, {@code elseif}, {@code else}, {@code endif}, {@code var},
 * {@code unset}, {@code error}, {@code warning}) plus all registered custom directives
 * and finally runs the registered {@link TextProcessor}s.
 *
 * <p>The engine is intentionally free of Gradle APIs so it can be unit tested and
 * reused in other tooling.</p>
 */
public final class Preprocessor {

    /** Version of the engine; included in task up-to-date fingerprints. */
    public static final String ENGINE_VERSION = "1.1.0";

    private final Keywords defaultKeywords;
    private final Map<String, Keywords> keywordsBySuffix;
    private final Map<String, Object> variables;
    private final Map<String, String> remapper;
    private final Map<String, DirectiveHandler> directives;
    private final List<TextProcessor> processors;
    private final LinePolicy policy;
    private final boolean strict;

    private Preprocessor(Builder builder) {
        this.defaultKeywords = builder.defaultKeywords;
        Map<String, Keywords> suffixMap = new LinkedHashMap<>();
        for (Map.Entry<String, Keywords> entry : builder.keywordsBySuffix.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT).trim();
            while (key.startsWith(".")) {
                key = key.substring(1);
            }
            if (!key.isEmpty()) {
                suffixMap.put(key, entry.getValue());
            }
        }
        this.keywordsBySuffix = suffixMap;
        this.variables = new LinkedHashMap<>(
                builder.variables == null ? Collections.emptyMap() : builder.variables);
        this.remapper = new LinkedHashMap<>(
                builder.remapper == null ? Collections.emptyMap() : builder.remapper);
        this.directives = new LinkedHashMap<>(
                builder.directives == null ? Collections.emptyMap() : builder.directives);
        this.processors = new ArrayList<>(
                builder.processors == null ? Collections.emptyList() : builder.processors);
        this.policy = builder.policy;
        this.strict = builder.strict;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Processes a whole file given as text. Line endings are preserved (the dominant
     * line ending wins for the entire file) as is the presence of a trailing newline.
     */
    public String processContent(String fileName, String relativePath, String content, List<String> warnings) {
        String eol = detectEol(content);
        boolean trailingNewline = !content.isEmpty() && (content.endsWith("\n") || content.endsWith("\r"));
        List<String> lines = splitLines(content);
        List<String> result = processLines(fileName, relativePath, lines, warnings);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < result.size(); i++) {
            if (i > 0) {
                sb.append(eol);
            }
            sb.append(result.get(i));
        }
        if (trailingNewline) {
            sb.append(eol);
        }
        return sb.toString();
    }

    /**
     * Processes a file given as a list of lines (without line endings) and returns
     * the transformed lines.
     *
     * @param fileName     file name used for suffix based keyword resolution and messages
     * @param relativePath path of the file relative to its source directory
     * @param lines        the input lines
     * @param warnings     sink for warnings, may be {@code null}
     * @return the processed lines
     * @throws ProcessingException on unbalanced blocks, bad conditions, strict mode errors
     */
    public List<String> processLines(String fileName, String relativePath, List<String> lines, List<String> warnings) {
        Keywords kw = resolveKeywords(fileName);
        FileState st = new FileState(fileName, kw, variables, directives, processors, policy, strict, warnings);
        DirectiveContextImpl ctx = new DirectiveContextImpl(st);

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            boolean active = st.currentActive();

            // an eval line (e.g. "//$$ code") is activated when the enclosing branch is active
            if (active && trimmed.startsWith(kw.getEvalToken())) {
                st.out.add(stripEvalMarker(line, kw.getEvalToken()));
                continue;
            }

            DirectiveLine directive = parseDirective(trimmed, kw);
            if (directive != null) {
                ctx.beginLine(directive.word, directive.argument, line, i + 1, active);
                if (handleDirective(ctx, st, directive)) {
                    st.addDirectiveLine(line, ctx.disposition, ctx.replacement);
                    continue;
                }
            }

            if (active) {
                st.out.add(line);
            } else {
                switch (policy) {
                    case COMMENT:
                        st.out.add(commentOut(line, trimmed, kw, st.innermostIndent()));
                        break;
                    case BLANK:
                        st.out.add("");
                        break;
                    case REMOVE:
                        break;
                    default:
                        st.out.add(line);
                        break;
                }
            }
        }

        if (!st.stack.isEmpty()) {
            BlockFrame frame = st.stack.peek();
            throw new ProcessingException("missing '" + kw.getPrefix() + kw.getEndifWord()
                    + "' for block opened here", fileName, frame.lineNumber);
        }

        runProcessors(st, relativePath);
        return st.out;
    }

    private boolean handleDirective(DirectiveContextImpl ctx, FileState st, DirectiveLine directive) {
        Keywords kw = st.keywords;
        String word = directive.word;
        String argument = directive.argument;
        int line = ctx.lineNumber;
        int indent = indentOf(ctx.rawLine);

        if (word.equals(kw.getIfWord())) {
            st.beginIf(argument, line, indent);
            return true;
        }
        if (word.equals(kw.getElseifWord())) {
            st.branchElseif(argument, line, indent);
            return true;
        }
        if (word.equals(kw.getElseWord())) {
            // also accept the two word forms "else if <condition>" and, for custom keyword
            // sets, "else <elseifWord> <condition>"; a bare "else" ignores its argument
            if (startsWithWord(argument, kw.getElseifWord())) {
                st.branchElseif(argument.substring(kw.getElseifWord().length()).trim(), line, indent);
            } else if (startsWithWord(argument, "if")) {
                st.branchElseif(argument.substring(2).trim(), line, indent);
            } else {
                st.branchElse(line, indent);
            }
            return true;
        }
        if (word.equals(kw.getEndifWord())) {
            st.endBranch(line);
            return true;
        }

        String lower = word.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "var":
            case "assign":
                if (st.currentActive()) {
                    assignVariable(argument, ctx, st);
                }
                return true;
            case "unset":
                if (st.currentActive()) {
                    st.scope.unset(argument.trim());
                }
                return true;
            case "error":
                if (st.currentActive()) {
                    throw new ProcessingException(argument.isEmpty() ? "error directive invoked" : argument,
                            st.fileName, line);
                }
                return true;
            case "warning":
                if (st.currentActive()) {
                    ctx.warn(argument.isEmpty() ? "warning directive invoked" : argument);
                }
                return true;
        }

        DirectiveHandler handler = st.directives.get(lower);
        if (handler != null) {
            handler.handle(ctx);
            return true;
        }
        return false;
    }

    private void assignVariable(String argument, DirectiveContextImpl ctx, FileState st) {
        String name;
        String expression;
        int eq = argument.indexOf('=');
        if (eq >= 0) {
            name = argument.substring(0, eq).trim();
            expression = argument.substring(eq + 1).trim();
        } else {
            int space = 0;
            while (space < argument.length() && !Character.isWhitespace(argument.charAt(space))) space++;
            name = argument.substring(0, space).trim();
            expression = argument.substring(space).trim();
        }
        if (!isValidName(name)) {
            throw new ProcessingException("invalid variable name in '//#var': '" + name + "'",
                    st.fileName, ctx.lineNumber);
        }
        if (expression.isEmpty()) {
            throw new ProcessingException("//#var requires a value: " + name + " = <expression>",
                    st.fileName, ctx.lineNumber);
        }
        Object value = ExpressionEvaluator.evaluate(expression, st.scope, st.strict, st, st.fileName, ctx.lineNumber);
        st.scope.set(name, stringify(value));
    }

    private void runProcessors(FileState st, String relativePath) {
        if (st.processors.isEmpty() && remapper.isEmpty()) {
            return;
        }
        ProcessorContext context = new ProcessorContextImpl(st, relativePath);
        if (!remapper.isEmpty()) {
            new RemapProcessor(remapper).process(context);
        }
        for (TextProcessor processor : st.processors) {
            processor.process(context);
        }
    }

    private Keywords resolveKeywords(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        Keywords best = defaultKeywords;
        int bestLength = 0;
        for (Map.Entry<String, Keywords> entry : keywordsBySuffix.entrySet()) {
            String key = entry.getKey();
            int keyLength = key.length();
            // the suffix must be dot bounded (or the whole name) so that e.g. a "son"
            // entry does not hijack ".json" files
            if (keyLength > bestLength && (lower.equals(key) || lower.endsWith("." + key))) {
                best = entry.getValue();
                bestLength = keyLength;
            }
        }
        return best;
    }

    private static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "true" : "false";
        }
        return value.toString();
    }

    private static boolean isValidName(String name) {
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static DirectiveLine parseDirective(String trimmed, Keywords kw) {
        if (!trimmed.startsWith(kw.getPrefix())) {
            return null;
        }
        String rest = trimmed.substring(kw.getPrefix().length());
        String word = leadingWord(rest);
        if (word == null) {
            return null;
        }
        return new DirectiveLine(word, rest.substring(word.length()).trim());
    }

    private static String leadingWord(String s) {
        if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) {
            return null;
        }
        int end = 1;
        while (end < s.length() && Character.isJavaIdentifierPart(s.charAt(end))) {
            end++;
        }
        return s.substring(0, end);
    }

    private static boolean startsWithWord(String s, String word) {
        int i = 0;
        int n = s.length();
        while (i < n && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        if (!s.startsWith(word, i)) {
            return false;
        }
        int after = i + word.length();
        if (after >= n) {
            return true;
        }
        return !Character.isJavaIdentifierPart(s.charAt(after));
    }

    private static String stripEvalMarker(String line, String token) {
        int idx = line.indexOf(token);
        String before = line.substring(0, idx);
        String after = line.substring(idx + token.length());
        if (after.startsWith(" ") || after.startsWith("\t")) {
            after = after.substring(1);
        }
        return before + after;
    }

    /** Comments an inactive line out, keeping its position; mirrors the ToCraft preprocessor. */
    private static String commentOut(String line, String trimmed, Keywords kw, int ifIndent) {
        if (trimmed.isEmpty()) {
            return spaces(ifIndent) + kw.getEvalToken();
        }
        int indent = indentOf(line);
        if (!trimmed.startsWith(kw.getEvalToken()) && ifIndent <= indent) {
            return spaces(ifIndent) + kw.getEvalToken() + " " + line.substring(ifIndent);
        }
        return line;
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return i;
    }

    private static String spaces(int n) {
        // deliberately no String.repeat (Java 11+) fast path behind a Util.JAVA guard:
        // the plugin is compiled with --release 8, where javac rejects references to newer
        // platform APIs at compile time - a runtime check cannot make them compilable
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String detectEol(String content) {
        int idx = content.indexOf('\n');
        if (idx > 0 && content.charAt(idx - 1) == '\r') {
            return "\r\n";
        }
        return "\n";
    }

    private static List<String> splitLines(String content) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                int end = i;
                if (end > start && content.charAt(end - 1) == '\r') {
                    end--;
                }
                lines.add(content.substring(start, end));
                start = i + 1;
            }
        }
        if (start < content.length()) {
            String tail = content.substring(start);
            if (tail.endsWith("\r")) {
                tail = tail.substring(0, tail.length() - 1);
            }
            lines.add(tail);
        }
        return lines;
    }

    private static String suffixOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static final class DirectiveLine {
        final String word;
        final String argument;

        DirectiveLine(String word, String argument) {
            this.word = word;
            this.argument = argument;
        }
    }

    private static final class BlockFrame {
        boolean active;
        boolean trueSeen;
        boolean elseSeen;
        final boolean inert;
        int indent;
        final int lineNumber;

        BlockFrame(boolean active, boolean trueSeen, boolean elseSeen, boolean inert, int indent, int lineNumber) {
            this.active = active;
            this.trueSeen = trueSeen;
            this.elseSeen = elseSeen;
            this.inert = inert;
            this.indent = indent;
            this.lineNumber = lineNumber;
        }

        static BlockFrame inert(int indent, int lineNumber) {
            return new BlockFrame(false, false, false, true, indent, lineNumber);
        }
    }

    private static final class FileState implements WarningSink {
        final String fileName;
        final Keywords keywords;
        final VariableScope scope;
        final Map<String, DirectiveHandler> directives;
        final List<TextProcessor> processors;
        final LinePolicy policy;
        final boolean strict;
        final List<String> warnings;
        final List<String> out = new ArrayList<String>();
        final Map<String, String> meta = new LinkedHashMap<String, String>();
        final Deque<BlockFrame> stack = new ArrayDeque<BlockFrame>();

        FileState(String fileName, Keywords keywords, Map<String, Object> variables,
                Map<String, DirectiveHandler> directives, List<TextProcessor> processors,
                LinePolicy policy, boolean strict, List<String> warnings) {
            this.fileName = fileName;
            this.keywords = keywords;
            this.scope = new VariableScope(variables);
            this.directives = directives;
            this.processors = processors;
            this.policy = policy;
            this.strict = strict;
            this.warnings = warnings;
        }

        @Override
        public void warn(String message) {
            if (warnings != null) {
                warnings.add(message);
            }
        }

        /** Adds a warning prefixed with file and line; no-op when no warning sink is present. */
        void warnLocated(String message, int line) {
            warn("[" + fileName + ":" + line + "] " + message);
        }

        boolean currentActive() {
            for (BlockFrame frame : stack) {
                if (!frame.active) {
                    return false;
                }
            }
            return true;
        }

        int innermostIndent() {
            return stack.isEmpty() ? 0 : stack.peek().indent;
        }

        void addDirectiveLine(String rawLine, int disposition, String replacement) {
            switch (disposition) {
                case 1:
                    out.add(replacement);
                    break;
                case 2:
                    break;
                default:
                    switch (policy) {
                        case COMMENT:
                            out.add(rawLine);
                            break;
                        case BLANK:
                            out.add("");
                            break;
                        case REMOVE:
                            break;
                        default:
                            out.add(rawLine);
                            break;
                    }
                    break;
            }
        }

        boolean eval(String condition, int line) {
            return ExpressionEvaluator.evaluateBoolean(condition, scope, strict, this, fileName, line);
        }

        void beginIf(String condition, int line, int indent) {
            if (!currentActive()) {
                stack.push(BlockFrame.inert(indent, line));
                return;
            }
            boolean conditionValue = eval(condition, line);
            stack.push(new BlockFrame(conditionValue, conditionValue, false, false, indent, line));
        }

        void beginBranch(boolean condition, int line, int indent) {
            if (!currentActive()) {
                stack.push(BlockFrame.inert(indent, line));
                return;
            }
            stack.push(new BlockFrame(condition, condition, false, false, indent, line));
        }

        void branchElseif(String condition, int line, int indent) {
            BlockFrame top = requireTop(line, keywords.getElseifWord());
            if (top.elseSeen) {
                throw new ProcessingException("'" + keywords.getPrefix() + keywords.getElseifWord()
                        + "' after '" + keywords.getPrefix() + keywords.getElseWord() + "'", fileName, line);
            }
            top.indent = indent;
            if (top.inert) {
                return;
            }
            if (top.trueSeen) {
                top.active = false;
            } else {
                boolean conditionValue = eval(condition, line);
                top.active = conditionValue;
                top.trueSeen = conditionValue;
            }
        }

        void branchElseifRaw(boolean condition, int line, int indent) {
            BlockFrame top = requireTop(line, keywords.getElseifWord());
            if (top.elseSeen) {
                throw new ProcessingException("'" + keywords.getPrefix() + keywords.getElseifWord()
                        + "' after '" + keywords.getPrefix() + keywords.getElseWord() + "'", fileName, line);
            }
            top.indent = indent;
            if (top.inert) {
                return;
            }
            if (top.trueSeen) {
                top.active = false;
            } else {
                top.active = condition;
                top.trueSeen = condition;
            }
        }

        void branchElse(int line, int indent) {
            BlockFrame top = requireTop(line, keywords.getElseWord());
            if (top.elseSeen) {
                throw new ProcessingException("duplicate '" + keywords.getPrefix() + keywords.getElseWord() + "'",
                        fileName, line);
            }
            top.indent = indent;
            if (top.inert) {
                top.elseSeen = true;
                return;
            }
            top.active = !top.trueSeen;
            top.elseSeen = true;
        }

        void endBranch(int line) {
            requireTop(line, keywords.getEndifWord());
            stack.pop();
        }

        private BlockFrame requireTop(int line, String word) {
            BlockFrame top = stack.peek();
            if (top == null) {
                throw new ProcessingException("'" + keywords.getPrefix() + word
                        + "' without a matching open block", fileName, line);
            }
            return top;
        }
    }

    private static final class DirectiveContextImpl implements DirectiveContext {
        final FileState st;
        final BlockControl blockControl;
        String directiveName;
        String argument;
        String rawLine;
        int lineNumber;
        boolean dispatchActive;
        int disposition;
        String replacement;

        DirectiveContextImpl(FileState st) {
            this.st = st;
            this.blockControl = new BlockControlImpl(this);
        }

        void beginLine(String name, String argument, String rawLine, int lineNumber, boolean active) {
            this.directiveName = name;
            this.argument = argument;
            this.rawLine = rawLine;
            this.lineNumber = lineNumber;
            this.dispatchActive = active;
            this.disposition = 0;
            this.replacement = null;
        }

        @Override
        public String directive() {
            return directiveName;
        }

        @Override
        public String argument() {
            return argument;
        }

        @Override
        public String fileName() {
            return st.fileName;
        }

        @Override
        public int lineNumber() {
            return lineNumber;
        }

        @Override
        public boolean active() {
            return dispatchActive;
        }

        @Override
        public boolean currentlyActive() {
            return st.currentActive();
        }

        @Override
        public Variables variables() {
            return st.scope;
        }

        @Override
        public void emit(String line) {
            if (st.currentActive()) {
                st.out.add(line);
            }
        }

        @Override
        public void replaceLine(String line) {
            this.disposition = 1;
            this.replacement = line;
        }

        @Override
        public void dropLine() {
            this.disposition = 2;
        }

        @Override
        public BlockControl blocks() {
            return blockControl;
        }

        @Override
        public Map<String, String> meta() {
            return st.meta;
        }

        @Override
        public void warn(String message) {
            st.warnLocated(message, lineNumber);
        }

        @Override
        public void fail(String message) {
            throw new ProcessingException(message, st.fileName, lineNumber);
        }
    }

    private static final class BlockControlImpl implements BlockControl {
        final DirectiveContextImpl ctx;

        BlockControlImpl(DirectiveContextImpl ctx) {
            this.ctx = ctx;
        }

        @Override
        public void beginBranch(boolean condition) {
            ctx.st.beginBranch(condition, ctx.lineNumber, indentOf(ctx.rawLine));
        }

        @Override
        public void nextBranch(boolean condition) {
            ctx.st.branchElseifRaw(condition, ctx.lineNumber, indentOf(ctx.rawLine));
        }

        @Override
        public void nextBranch() {
            ctx.st.branchElse(ctx.lineNumber, indentOf(ctx.rawLine));
        }

        @Override
        public void endBranch() {
            ctx.st.endBranch(ctx.lineNumber);
        }

        @Override
        public boolean hasOpenBranch() {
            return !ctx.st.stack.isEmpty();
        }

        @Override
        public boolean branchActive() {
            return ctx.st.currentActive();
        }
    }

    private static final class ProcessorContextImpl implements ProcessorContext {
        final FileState st;
        final String relativePath;

        ProcessorContextImpl(FileState st, String relativePath) {
            this.st = st;
            this.relativePath = relativePath;
        }

        @Override
        public String fileName() {
            return st.fileName;
        }

        @Override
        public String relativePath() {
            return relativePath;
        }

        @Override
        public String suffix() {
            return suffixOf(st.fileName);
        }

        @Override
        public Keywords keywords() {
            return st.keywords;
        }

        @Override
        public List<String> lines() {
            return st.out;
        }

        @Override
        public Variables variables() {
            return st.scope;
        }

        @Override
        public Map<String, String> meta() {
            return st.meta;
        }

        @Override
        public void warn(String message) {
            st.warn("[" + st.fileName + "] " + message);
        }
    }

    /** Fluent builder for a {@link Preprocessor} instance. */
    public static final class Builder {
        private Keywords defaultKeywords = Keywords.DEFAULT;
        private Map<String, Keywords> keywordsBySuffix = Collections.emptyMap();
        private Map<String, Object> variables = Collections.emptyMap();
        private Map<String, String> remapper = Collections.emptyMap();
        private Map<String, DirectiveHandler> directives = Collections.emptyMap();
        private List<TextProcessor> processors = Collections.emptyList();
        private LinePolicy policy = LinePolicy.COMMENT;
        private boolean strict = false;

        /** Sets the keywords used for files without a suffix specific entry. */
        public Builder defaultKeywords(Keywords keywords) {
            this.defaultKeywords = keywords == null ? Keywords.DEFAULT : keywords;
            return this;
        }

        /** Sets the suffix based keywords map (keys are file name suffixes such as {@code json}). */
        public Builder keywordsBySuffix(Map<String, Keywords> keywordsBySuffix) {
            this.keywordsBySuffix = keywordsBySuffix == null ? Collections.<String, Keywords>emptyMap() : keywordsBySuffix;
            return this;
        }

        /** Sets the variables visible to all conditions. */
        public Builder variables(Map<String, Object> variables) {
            this.variables = variables == null ? Collections.<String, Object>emptyMap() : variables;
            return this;
        }

        /** Sets the custom directives (lowercase name to handler). */
        public Builder directives(Map<String, DirectiveHandler> directives) {
            this.directives = directives == null ? Collections.<String, DirectiveHandler>emptyMap() : directives;
            return this;
        }

        /** Sets the string replacement map applied to every processed line. */
        public Builder remapper(Map<String, String> remapper) {
            this.remapper = remapper == null ? Collections.<String, String>emptyMap() : remapper;
            return this;
        }

        /** Sets the text processors that run after the directive scan. */
        public Builder processors(List<TextProcessor> processors) {
            this.processors = processors == null ? Collections.<TextProcessor>emptyList() : processors;
            return this;
        }

        /** Sets the {@link LinePolicy} applied to directive and inactive lines. */
        public Builder policy(LinePolicy policy) {
            this.policy = policy == null ? LinePolicy.COMMENT : policy;
            return this;
        }

        /** Enables strict mode: unknown variables abort preprocessing instead of warning. */
        public Builder strict(boolean strict) {
            this.strict = strict;
            return this;
        }

        public Preprocessor build() {
            return new Preprocessor(this);
        }
    }
}
