package dev.oblivruin.preprocessor.core;

import dev.oblivruin.preprocessor.api.Variables;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Evaluates preprocessor condition expressions with the following grammar:
 *
 * <pre>
 * expr       := orExpr
 * orExpr     := andExpr ( '||' andExpr )*
 * andExpr    := notExpr ( '&amp;&amp;' notExpr )*
 * notExpr    := '!' notExpr | comparison
 * comparison := primary ( op primary )?          op in { == != &lt; &lt;= &gt; &gt;= }
 * primary    := '(' expr ')' | string | number | true | false | 'defined' ident | ident
 * </pre>
 *
 * <p>Operands may be quoted strings ({@code "1.20.1"} or {@code 'fabric'}), numbers,
 * booleans or variables. Numeric operands are compared numerically; everything else
 * is compared with a version aware algorithm, so {@code "1.16" > "1.9"} evaluates to
 * {@code true}. Equality of non-numeric operands is exact string equality.</p>
 *
 * <p>An unknown variable evaluates to {@code false}; in strict mode it is an error
 * instead. A leading {@code !} negates, {@code &&} binds tighter than {@code ||},
 * and parentheses group sub expressions.</p>
 */
public final class ExpressionEvaluator {
    private ExpressionEvaluator() {
    }

    /**
     * Evaluates a condition to a boolean.
     *
     * @param expression the condition text, e.g. {@code MC >= "1.17" && FABRIC}
     * @param vars       the variables visible to the condition
     * @param strict     if {@code true}, unknown variables abort evaluation with an error
     * @param warnings   sink for non-fatal warnings, may be {@code null}
     * @param fileName   file used for error location, may be {@code null}
     * @param lineNumber line used for error location
     * @return the boolean result of the condition
     * @throws ProcessingException on syntax errors or (in strict mode) unknown variables
     */
    public static boolean evaluateBoolean(String expression, Variables vars, boolean strict,
            WarningSink warnings, String fileName, int lineNumber) {
        Object result = evaluate(expression, vars, strict, warnings, fileName, lineNumber);
        return isTruthy(result);
    }

    /**
     * Evaluates a condition and returns the raw value: {@link Boolean} for logical
     * results, {@link Number} for numeric literals, {@link String} for string literals
     * and resolved variables, or {@code null} for unknown variables.
     */
    public static Object evaluate(String expression, Variables vars, boolean strict,
            WarningSink warnings, String fileName, int lineNumber) {
        if (expression == null || expression.trim().isEmpty()) {
            warn(warnings, "empty condition, assuming false" + locate(fileName, lineNumber));
            return Boolean.FALSE;
        }
        List<Token> tokens = tokenize(expression, fileName, lineNumber);
        return new Parser(tokens, vars, strict, warnings, fileName, lineNumber).parseExpression();
    }

    /** Truthiness: {@code null}, empty strings, {@code "0"}, {@code "false"}, {@code "null"}, {@code "no"}, {@code "off"} are false. */
    public static boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return new BigDecimal(value.toString()).signum() != 0;
        }
        String s = value.toString();
        if (s.isEmpty()) {
            return false;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        return !(lower.equals("0") || lower.equals("false") || lower.equals("null")
                || lower.equals("no") || lower.equals("off"));
    }

    private static void warn(WarningSink warnings, String message) {
        if (warnings != null) {
            warnings.warn(message);
        }
    }

    private static String locate(String fileName, int lineNumber) {
        if (fileName != null) {
            return " [" + fileName + ":" + lineNumber + "]";
        }
        return "";
    }

    static int compareVersions(String a, String b) {
        List<Object> ta = versionTokens(a);
        List<Object> tb = versionTokens(b);
        int n = Math.max(ta.size(), tb.size());
        for (int i = 0; i < n; i++) {
            Object sa = i < ta.size() ? ta.get(i) : Long.valueOf(0);
            Object sb = i < tb.size() ? tb.get(i) : Long.valueOf(0);
            if (sa instanceof Long && sb instanceof Long) {
                int cmp = Long.compare((Long) sa, (Long) sb);
                if (cmp != 0) {
                    return cmp;
                }
            } else if (sa instanceof Long) {
                return 1; // numeric segments outrank pre-release letters
            } else if (sb instanceof Long) {
                return -1;
            } else {
                int cmp = ((String) sa).compareTo((String) sb);
                if (cmp != 0) {
                    return cmp;
                }
            }
        }
        return 0;
    }

    private static List<Object> versionTokens(String s) {
        List<Object> tokens = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                int start = i;
                while (i < n && Character.isDigit(s.charAt(i))) i++;
                tokens.add(Long.valueOf(s.substring(start, i)));
            } else if (Character.isLetter(c)) {
                int start = i;
                while (i < n && Character.isLetter(s.charAt(i))) i++;
                tokens.add(s.substring(start, i).toLowerCase(Locale.ROOT));
            } else {
                i++; // separators such as . - _ + : space
            }
        }
        return tokens;
    }

    private static List<Token> tokenize(String s, String fileName, int lineNumber) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '(') {
                tokens.add(Token.paren('('));
                i++;
                continue;
            }
            if (c == ')') {
                tokens.add(Token.paren(')'));
                i++;
                continue;
            }
            if (c == '"' || c == '\'') {
                int end = s.indexOf(c, i + 1);
                if (end < 0) {
                    throw new ProcessingException("unterminated string literal", fileName, lineNumber);
                }
                tokens.add(Token.string(s.substring(i + 1, end)));
                i = end + 1;
                continue;
            }
            if (c == '&' || c == '|') {
                if (i + 1 < n && s.charAt(i + 1) == c) {
                    tokens.add(Token.op(new String(new char[]{c, c})));
                    i += 2;
                    continue;
                }
                throw new ProcessingException("expected '" + c + c + "', single '" + c + "' is not supported",
                        fileName, lineNumber);
            }
            if (c == '!') {
                if (i + 1 < n && s.charAt(i + 1) == '=') {
                    tokens.add(Token.op("!="));
                    i += 2;
                } else {
                    tokens.add(Token.op("!"));
                    i++;
                }
                continue;
            }
            if (c == '=' || c == '<' || c == '>') {
                if (i + 1 < n && s.charAt(i + 1) == '=') {
                    tokens.add(Token.op(new String(new char[]{c, '='})));
                    i += 2;
                } else if (c == '=') {
                    throw new ProcessingException("unexpected '=', use '==' or '!='", fileName, lineNumber);
                } else {
                    tokens.add(Token.op(String.valueOf(c)));
                    i++;
                }
                continue;
            }
            if (Character.isDigit(c)) {
                int start = i;
                while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
                String text = s.substring(start, i);
                int dots = 0;
                for (int k = 0; k < text.length(); k++) {
                    if (text.charAt(k) == '.') dots++;
                }
                if (dots <= 1) {
                    tokens.add(Token.number(text));
                } else {
                    tokens.add(Token.string(text)); // bare multi-dot literal, treated as a version string
                }
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < n && Character.isJavaIdentifierPart(s.charAt(i))) i++;
                tokens.add(Token.ident(s.substring(start, i)));
                continue;
            }
            throw new ProcessingException("unexpected character '" + c + "' in condition", fileName, lineNumber);
        }
        return tokens;
    }

    private static final class Token {
        enum Type {LPAREN, RPAREN, STRING, NUMBER, IDENT, OP}

        final Type type;
        final String text;
        final BigDecimal number;

        private Token(Type type, String text, BigDecimal number) {
            this.type = type;
            this.text = text;
            this.number = number;
        }

        static Token paren(char c) {
            return new Token(c == '(' ? Type.LPAREN : Type.RPAREN, String.valueOf(c), null);
        }

        static Token string(String value) {
            return new Token(Type.STRING, value, null);
        }

        static Token number(String value) {
            return new Token(Type.NUMBER, value, new BigDecimal(value));
        }

        static Token ident(String value) {
            return new Token(Type.IDENT, value, null);
        }

        static Token op(String value) {
            return new Token(Type.OP, value, null);
        }
    }

    private static final class Parser {
        private final List<Token> tokens;
        private final Variables vars;
        private final boolean strict;
        private final WarningSink warnings;
        private final String fileName;
        private final int lineNumber;
        private final Set<String> warnedUnknown = new HashSet<>();
        private int pos;

        Parser(List<Token> tokens, Variables vars, boolean strict, WarningSink warnings,
                String fileName, int lineNumber) {
            this.tokens = tokens;
            this.vars = vars;
            this.strict = strict;
            this.warnings = warnings;
            this.fileName = fileName;
            this.lineNumber = lineNumber;
        }

        Object parseExpression() {
            if (tokens.isEmpty()) {
                warn("empty condition, assuming false");
                return Boolean.FALSE;
            }
            Object value = parseOr();
            Token trailing = peek();
            if (trailing != null) {
                throw error("unexpected token '" + trailing.text + "'");
            }
            return value;
        }

        private Object parseOr() {
            Object left = parseAnd();
            while (matchOp("||")) {
                Object right = parseAnd();
                left = isTruthy(left) || isTruthy(right);
            }
            return left;
        }

        private Object parseAnd() {
            Object left = parseNot();
            while (matchOp("&&")) {
                Object right = parseNot();
                left = isTruthy(left) && isTruthy(right);
            }
            return left;
        }

        private Object parseNot() {
            if (matchOp("!")) {
                return !isTruthy(parseNot());
            }
            return parseComparison();
        }

        private Object parseComparison() {
            Object left = parsePrimary();
            String op = peekOp("==", "!=", "<=", ">=", "<", ">");
            if (op == null) {
                return left;
            }
            next();
            Object right = parsePrimary();
            return compare(left, op, right);
        }

        private Object parsePrimary() {
            Token token = peek();
            if (token == null) {
                throw error("unexpected end of condition");
            }
            if (token.type == Token.Type.LPAREN) {
                next();
                Object value = parseOr();
                Token closing = peek();
                if (closing == null || closing.type != Token.Type.RPAREN) {
                    throw error("expected ')'");
                }
                next();
                return value;
            }
            if (token.type == Token.Type.STRING) {
                next();
                return token.text;
            }
            if (token.type == Token.Type.NUMBER) {
                next();
                return token.number;
            }
            if (token.type == Token.Type.IDENT) {
                next();
                String name = token.text;
                if (name.equals("defined")) {
                    Token target = peek();
                    if (target == null || target.type != Token.Type.IDENT) {
                        throw error("'defined' must be followed by a variable name");
                    }
                    next();
                    return vars.defined(target.text);
                }
                if (name.equalsIgnoreCase("true")) {
                    return Boolean.TRUE;
                }
                if (name.equalsIgnoreCase("false")) {
                    return Boolean.FALSE;
                }
                return resolve(name);
            }
            throw error("unexpected token '" + token.text + "'");
        }

        private Object resolve(String name) {
            Object value = vars.get(name);
            if (value != null) {
                return value;
            }
            if (strict) {
                throw error("unknown variable '" + name + "'");
            }
            if (warnedUnknown.add(name)) {
                warn("unknown variable '" + name + "', assuming false");
            }
            return null;
        }

        private boolean compare(Object left, String op, Object right) {
            if (left == null || right == null) {
                return false;
            }
            if (left instanceof Number && right instanceof Number) {
                int cmp = new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()));
                return apply(cmp, op);
            }
            String l = left.toString();
            String r = right.toString();
            if (op.equals("==")) {
                return l.equals(r);
            }
            if (op.equals("!=")) {
                return !l.equals(r);
            }
            return apply(compareVersions(l, r), op);
        }

        private static boolean apply(int cmp, String op) {
            switch (op) {
                case "==":
                    return cmp == 0;
                case "!=":
                    return cmp != 0;
                case "<":
                    return cmp < 0;
                case "<=":
                    return cmp <= 0;
                case ">":
                    return cmp > 0;
                case ">=":
                    return cmp >= 0;
                default:
                    throw new IllegalStateException("unknown operator: " + op);
            }
        }

        private boolean matchOp(String op) {
            Token token = peek();
            if (token != null && token.type == Token.Type.OP && token.text.equals(op)) {
                next();
                return true;
            }
            return false;
        }

        private String peekOp(String... operators) {
            Token token = peek();
            if (token != null && token.type == Token.Type.OP) {
                for (String operator : operators) {
                    if (token.text.equals(operator)) {
                        return operator;
                    }
                }
            }
            return null;
        }

        private Token peek() {
            return pos < tokens.size() ? tokens.get(pos) : null;
        }

        private void next() {
            pos++;
        }

        private void warn(String message) {
            ExpressionEvaluator.warn(warnings, message + locate(fileName, lineNumber));
        }

        private ProcessingException error(String message) {
            return new ProcessingException(message, fileName, lineNumber);
        }
    }
}
