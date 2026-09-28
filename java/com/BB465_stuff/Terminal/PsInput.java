package com.BB465_stuff.Terminal;

/**
 * Decides when typed PowerShell input is a complete statement.
 *
 * This lives on its own, with no Android imports, for two reasons. It is pure
 * string logic and deserves to be testable off-device - the terminal class
 * cannot even be loaded without a real framework, because its static Handler
 * field blows up on the stub. And it is fiddly enough to want its own
 * tests.
 */
public final class PsInput {

    /**
     * Is this PowerShell input a complete statement yet?
     *
     * Walks the text once, tracking quote state, here-strings and bracket
     * depth, then refuses to call it complete if the last real character is an
     * operator that means "more follows" - a trailing pipe, a comma, a
     * backtick, or an arithmetic or comparison operator.
     *
     * Deliberately a heuristic. Getting it wrong in the cautious direction
     * just means the prompt shows '>>' and waits for another line, which the
     * user can always finish. Getting it wrong the other way sends a fragment
     * to PowerShell and gets a ParserError, which is exactly what this exists
     * to prevent: a block opener sent on its own used to come back as "n="
     * followed by "Unexpected token '}'".
     */
    public static boolean complete(String text) {
        if (text == null) return true;
        int brace = 0, paren = 0, bracket = 0;
        char inQuote = 0;                 // 0, '\'' or '"'
        String here = null;               // "'@" or "\"@" while inside one
        boolean escaped = false;
        // the last character that actually counted, ignoring whitespace and
        // ignoring anything after a '#'. Needed because a comment can hide the
        // real end of the line: 'Get-ChildItem | # now filter' still wants a
        // continuation, and looking at the final 'r' of "filter" would not say so.
        char lastSignificant = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (here != null) {
                // a here-string only ends on its terminator at the start of a line
                if (c == '\n' && text.startsWith(here, i + 1)) {
                    i += here.length() - 1;
                    here = null;
                }
                continue;
            }
            if (inQuote != 0) {
                if (escaped) { escaped = false; continue; }
                if (c == '\\' && inQuote == '"') { escaped = true; continue; }
                if (c == inQuote) {
                    // '' inside a single-quoted string is an escaped quote
                    if (inQuote == '\'' && i + 1 < text.length()
                            && text.charAt(i + 1) == '\'') {
                        i++;
                        continue;
                    }
                    inQuote = 0;
                }
                if (!Character.isWhitespace(c)) lastSignificant = c;
                continue;
            }

            if (c == '#') {
                // In PowerShell '#' starts a comment anywhere outside a string,
                // not just at the start of a line. Getting this wrong hides
                // what came before it, so 'Get-ChildItem | # now filter' would
                // look complete when the pipe is still waiting for input.
                int nl = text.indexOf('\n', i);
                if (nl < 0) break;         // rest is a comment, done
                i = nl;
                continue;
            }
            if (c != ' ' && c != '\t' && c != '\r') lastSignificant = c;
            if (c == '\'') { inQuote = '\''; continue; }
            if (c == '"')  { inQuote = '"';  continue; }
            if (c == '@' && i + 1 < text.length()
                    && (text.charAt(i + 1) == '\'' || text.charAt(i + 1) == '"')) {
                // @' or @" opens a here-string, but only when it is the last
                // thing on its line, otherwise it is a splat like $env:PATH
                int nl = text.indexOf('\n', i);
                String tail = (nl < 0) ? text.substring(i + 2)
                                       : text.substring(i + 2, nl).trim();
                if (tail.length() == 0) {
                    char q = text.charAt(i + 1);
                    here = (q == '\'') ? "'@" : "\"@";
                    i++;                        // skip the quote just consumed
                    continue;
                }
            }
            if (c == '{') { brace++; continue; }
            if (c == '}') { if (brace > 0) brace--; continue; }
            if (c == '(') { paren++; continue; }
            if (c == ')') { if (paren > 0) paren--; continue; }
            if (c == '[') { bracket++; continue; }
            if (c == ']') { if (bracket > 0) bracket--; continue; }
        }

        if (inQuote != 0 || here != null) return false;
        if (brace > 0 || paren > 0 || bracket > 0) return false;
        if (lastSignificant == 0) return true;      // blank or comment only

        char last = lastSignificant;
        if (last == '|' || last == ',' || last == '`' || last == '+' || last == '-'
                || last == '*' || last == '/' || last == '%' || last == '=') {
            return false;                            // wants the next line
        }
        if (last == '{' || last == '(' || last == '[') return false;
        return true;
    }
}
