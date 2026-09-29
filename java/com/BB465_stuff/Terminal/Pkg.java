package com.BB465_stuff.Terminal;

/** Pure string logic, no Android imports, so it stays unit-testable off-device. */
public final class PsInput {

    /**
     * Walks the text tracking quote state, here-strings and bracket depth, then
     * refuses to call it complete if the last real character means "more
     * follows" - a trailing pipe, comma, backtick or operator.
     *
     * A heuristic, and biased towards false: wrongly waiting just shows '>>',
     * which the user can finish. Wrongly proceeding sends a fragment to
     * PowerShell and gets a ParserError.
     */
    public static boolean complete(String text) {
        if (text == null) return true;
        int brace = 0, paren = 0, bracket = 0;
        char inQuote = 0;                 // 0, '\'' or '"'
        String here = null;               // "'@" or "\"@" while inside one
        boolean escaped = false;
        // last character that counted, ignoring whitespace and comments: a
        // comment can hide the real end, as in 'Get-ChildItem | # now filter'
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
                // '#' comments out the rest of the line anywhere outside a
                // string, not just at the start of a line
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
                // @' or @" is a here-string only when last on its line,
                // otherwise it is a splat like $env:PATH
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
