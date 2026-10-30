package com.dharmawantoxi.rapiuang;

/** Formatting and cursor handling for whole-rupiah input; never rounds or truncates digits. */
final class AmountInput {
    // Matches the EditText's existing safety limit, including inserted grouping dots.
    static final int MAX_TEXT_LENGTH = 100;

    static final class Edit {
        final String text;
        final int selectionStart;
        final int selectionEnd;

        Edit(String text, int selectionStart, int selectionEnd) {
            this.text = text;
            this.selectionStart = selectionStart;
            this.selectionEnd = selectionEnd;
        }
    }

    private AmountInput() { }

    /** Returns digits only for an ungrouped value or correctly grouped Indonesian notation. */
    static String digitsOrNull(String text) {
        if (text == null || text.isEmpty()) return null;
        if (text.matches("[0-9]+")) return text;
        if (text.matches("[0-9]{1,3}(\\.[0-9]{3})+")) return text.replace(".", "");
        return null;
    }

    private static boolean digitsOnly(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static String group(String digits) {
        StringBuilder result = new StringBuilder(digits.length() + digits.length() / 3);
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) result.append('.');
            result.append(digits.charAt(i));
        }
        return result.toString();
    }

    /** Format valid restored values, but leave invalid pasted input visible for correction. */
    static String forDisplay(String text) {
        if (text == null || text.isEmpty()) return "";
        String digits = digitsOrNull(text);
        if (digits == null) return text;
        String formatted = group(digits);
        // Never let Android's length filter truncate a reformatted oversized value.
        return formatted.length() <= MAX_TEXT_LENGTH ? formatted : text;
    }

    /**
     * Reformat an edit while preserving the number of digits before each selection endpoint.
     * Existing separators are trusted only when the previous value was valid and newly inserted
     * text contains digits alone. Invalid pasted punctuation is left intact and fails validation.
     */
    static Edit afterEdit(String before, String after, int start, int removed, int inserted,
                          int previousSelectionStart, int previousSelectionEnd,
                          int selectionStart, int selectionEnd) {
        int from = clamp(selectionStart, after.length());
        int to = clamp(selectionEnd, after.length());
        if (start < 0 || removed < 0 || inserted < 0 || start + removed > before.length()
                || start + inserted > after.length()) return new Edit(after, from, to);

        boolean validBefore = before.isEmpty() || digitsOrNull(before) != null;
        String current = after;
        // Backspace/delete on an automatically inserted dot should remove the adjacent digit,
        // not merely reinsert the same dot and appear to do nothing.
        if (validBefore && removed == 1 && inserted == 0 && before.charAt(start) == '.'
                && previousSelectionStart == previousSelectionEnd) {
            if (previousSelectionStart == start + 1 && start > 0 && isDigit(current.charAt(start - 1))) {
                current = current.substring(0, start - 1) + current.substring(start);
                from = to = start - 1;
            } else if (previousSelectionStart == start && start < current.length() && isDigit(current.charAt(start))) {
                current = current.substring(0, start) + current.substring(start + 1);
                from = to = start;
            }
        }

        String digits = digitsOrNull(current);
        if (current.isEmpty()) digits = "";
        if (digits == null && validBefore && digitsOnly(after.substring(start, start + inserted))) {
            // Only dots that were already present in a valid previous value may be removed.
            String ungrouped = current.replace(".", "");
            if (digitsOnly(ungrouped)) digits = ungrouped;
        }
        if (digits == null) return new Edit(current, from, to);
        String formatted = group(digits);
        if (formatted.length() > MAX_TEXT_LENGTH) return new Edit(current, from, to);
        int startDigits = digitsBefore(current, from);
        int endDigits = digitsBefore(current, to);
        return new Edit(formatted, positionAfterDigits(formatted, startDigits), positionAfterDigits(formatted, endDigits));
    }

    private static int digitsBefore(String text, int position) {
        int count = 0;
        for (int i = 0; i < clamp(position, text.length()); i++) if (isDigit(text.charAt(i))) count++;
        return count;
    }

    private static int positionAfterDigits(String text, int count) {
        if (count <= 0) return 0;
        for (int i = 0; i < text.length(); i++) {
            if (isDigit(text.charAt(i)) && --count == 0) return i + 1;
        }
        return text.length();
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
    private static int clamp(int value, int max) { return Math.max(0, Math.min(value, max)); }
}
