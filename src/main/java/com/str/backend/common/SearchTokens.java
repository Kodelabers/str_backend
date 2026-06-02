package com.str.backend.common;

import java.util.ArrayList;
import java.util.List;

/**
 * Token-AND megasearch helper. Splits a free-text query into lowercased tokens; every token
 * must match (as a case-insensitive substring) the per-record haystack — see the search queries
 * in {@code RnRepository} / {@code LessorRepository}. Mirrors the frontend reference logic
 * (str_frontend {@code src/mock/routes/statistics.ts} {@code matchesQuery}).
 */
public final class SearchTokens {

    /** Defensive cap on tokens honored from a single query (extra tokens are dropped). */
    public static final int MAX_TOKENS = 10;

    private SearchTokens() {
    }

    /** Splits {@code q} on whitespace and commas, lowercases, drops blanks, caps at {@link #MAX_TOKENS}. */
    public static List<String> split(String q) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        String[] raw = q.trim().toLowerCase().split("[\\s,]+");
        List<String> tokens = new ArrayList<>(Math.min(raw.length, MAX_TOKENS));
        for (String token : raw) {
            if (!token.isEmpty()) {
                tokens.add(token);
                if (tokens.size() == MAX_TOKENS) {
                    break;
                }
            }
        }
        return tokens;
    }

    /**
     * Returns exactly {@link #MAX_TOKENS} slots: the tokens of {@code q} followed by trailing nulls.
     * Bind these to the {@code tok0..tok9} query params; null slots are no-ops in the query.
     */
    public static String[] slots(String q) {
        String[] slots = new String[MAX_TOKENS];
        List<String> tokens = split(q);
        for (int i = 0; i < tokens.size(); i++) {
            slots[i] = tokens.get(i);
        }
        return slots;
    }
}
