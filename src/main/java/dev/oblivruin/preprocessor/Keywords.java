package dev.oblivruin.preprocessor;

import java.io.Serializable;
import java.util.Objects;

/**
 * The directive keywords the preprocessor recognizes for a group of files.
 *
 * <p>A directive line always has the shape {@code <prefix><word>[ argument]}, where
 * {@code prefix} is a comment marker such as {@code "//#"} and {@code word} is one of
 * {@code if}, {@code elseif}, {@code else}, {@code endif} or the name of a registered
 * custom directive. The {@code evalToken} (usually {@code //$$}) marks lines that are
 * activated in the current branch.</p>
 *
 * <p>Instances are immutable and serializable so they can be used as task inputs.</p>
 */
public final class Keywords implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * The default keywords, identical to the ones used by the ReplayMod / ToCraft
     * preprocessor: {@code //#if}, {@code //#elseif}, {@code //#else}, {@code //#endif}
     * and the {@code //$$} eval marker.
     */
    public static final Keywords DEFAULT =
            new Keywords("//#", "if", "elseif", "else", "endif", "//$$");

    private final String prefix;
    private final String ifWord;
    private final String elseifWord;
    private final String elseWord;
    private final String endifWord;
    private final String evalToken;

    /**
     * Creates keywords from full tokens (ToCraft / ReplayMod style), e.g.
     * {@code new Keywords("//#if", "//#elseif", "//#else", "//#endif", "//$$")}.
     * The common prefix of the tokens is used as the directive prefix and stripped
     * from each token to obtain the bare words.
     *
     * @throws IllegalArgumentException if the tokens do not share a common non-empty prefix
     *                                  that leaves valid identifiers
     */
    public Keywords(String ifToken, String elseifToken, String elseToken, String endifToken, String evalToken) {
        this(derivePrefix(ifToken, elseifToken, elseToken, endifToken),
                tokenOf(ifToken, derivePrefix(ifToken, elseifToken, elseToken, endifToken)),
                tokenOf(elseifToken, derivePrefix(ifToken, elseifToken, elseToken, endifToken)),
                tokenOf(elseToken, derivePrefix(ifToken, elseifToken, elseToken, endifToken)),
                tokenOf(endifToken, derivePrefix(ifToken, elseifToken, elseToken, endifToken)),
                evalToken);
    }

    /**
     * Creates keywords from an explicit prefix and the bare directive words.
     *
     * @param prefix     the comment marker every directive line starts with, e.g. {@code "//#"}
     * @param ifWord     the word opening a conditional block, e.g. {@code "if"}
     * @param elseifWord the word opening an alternative branch, e.g. {@code "elseif"}
     * @param elseWord   the word opening the fallback branch, e.g. {@code "else"}
     * @param endifWord  the word closing a conditional block, e.g. {@code "endif"}
     * @param evalToken  the line marker that activates a line inside the active branch, e.g. {@code "//$$"}
     */
    public Keywords(String prefix, String ifWord, String elseifWord, String elseWord, String endifWord, String evalToken) {
        requireIdentifier(ifWord, "ifWord");
        requireIdentifier(elseifWord, "elseifWord");
        requireIdentifier(elseWord, "elseWord");
        requireIdentifier(endifWord, "endifWord");
        if (prefix == null || prefix.isEmpty()) {
            throw new IllegalArgumentException("prefix must not be empty");
        }
        if (evalToken == null || evalToken.isEmpty()) {
            throw new IllegalArgumentException("evalToken must not be empty");
        }
        this.prefix = prefix;
        this.ifWord = ifWord;
        this.elseifWord = elseifWord;
        this.elseWord = elseWord;
        this.endifWord = endifWord;
        this.evalToken = evalToken;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getIfWord() {
        return ifWord;
    }

    public String getElseifWord() {
        return elseifWord;
    }

    public String getElseWord() {
        return elseWord;
    }

    public String getEndifWord() {
        return endifWord;
    }

    public String getEvalToken() {
        return evalToken;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Keywords)) return false;
        Keywords keywords = (Keywords) o;
        return prefix.equals(keywords.prefix)
                && ifWord.equals(keywords.ifWord)
                && elseifWord.equals(keywords.elseifWord)
                && elseWord.equals(keywords.elseWord)
                && endifWord.equals(keywords.endifWord)
                && evalToken.equals(keywords.evalToken);
    }

    @Override
    public int hashCode() {
        return Objects.hash(prefix, ifWord, elseifWord, elseWord, endifWord, evalToken);
    }

    @Override
    public String toString() {
        return "Keywords{" + prefix + ifWord + ", " + prefix + elseifWord + ", " + prefix + elseWord
                + ", " + prefix + endifWord + ", " + evalToken + "}";
    }

    private static String derivePrefix(String ifToken, String elseifToken, String elseToken, String endifToken) {
        String prefix = longestCommonPrefix(ifToken, elseifToken);
        prefix = longestCommonPrefix(prefix, elseToken);
        prefix = longestCommonPrefix(prefix, endifToken);
        if (prefix.isEmpty()) {
            throw new IllegalArgumentException(
                    "keyword tokens must share a common prefix, use Keywords(prefix, if, elseif, else, endif, eval) instead");
        }
        return prefix;
    }

    private static String tokenOf(String token, String prefix) {
        if (!token.startsWith(prefix)) {
            throw new IllegalArgumentException(
                    "token '" + token + "' does not start with the derived prefix '" + prefix + "'");
        }
        return token.substring(prefix.length());
    }

    private static String longestCommonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return a.substring(0, i);
    }

    private static void requireIdentifier(String word, String label) {
        if (word == null || word.isEmpty() || !Character.isJavaIdentifierStart(word.charAt(0))) {
            throw new IllegalArgumentException(label + " must be a non-empty identifier: " + word);
        }
        for (int i = 1; i < word.length(); i++) {
            if (!Character.isJavaIdentifierPart(word.charAt(i))) {
                throw new IllegalArgumentException(label + " must be a valid identifier: " + word);
            }
        }
    }
}
