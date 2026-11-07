package dev.lyy.friendlycore.modules.chat.filter;

/**
 * Fast Levenshtein distance and similarity percentage evaluator.
 */
public final class SimilarityEvaluator {

    private SimilarityEvaluator() {
    }

    /**
     * Calculates the similarity ratio between two strings from 0.0 (completely distinct) to 1.0 (identical).
     */
    public static double evaluateSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;
        if (s1.equalsIgnoreCase(s2)) return 1.0;
        if (s1.isEmpty() || s2.isEmpty()) return 0.0;

        String a = s1.trim().toLowerCase();
        String b = s2.trim().toLowerCase();

        if (a.equals(b)) return 1.0;

        int distance = computeLevenshteinDistance(a, b);
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 1.0;

        return 1.0 - ((double) distance / maxLen);
    }

    /**
     * Space-optimized Levenshtein Distance calculation using two rows (O(min(N, M)) memory).
     */
    public static int computeLevenshteinDistance(String a, String b) {
        if (a == null || b == null) return 0;
        int lenA = a.length();
        int lenB = b.length();

        if (lenA == 0) return lenB;
        if (lenB == 0) return lenA;

        // Ensure lenA <= lenB to minimize memory allocation
        if (lenA > lenB) {
            String temp = a;
            a = b;
            b = temp;
            lenA = a.length();
            lenB = b.length();
        }

        int[] previousRow = new int[lenA + 1];
        int[] currentRow = new int[lenA + 1];

        for (int i = 0; i <= lenA; i++) {
            previousRow[i] = i;
        }

        for (int j = 1; j <= lenB; j++) {
            currentRow[0] = j;
            char charB = b.charAt(j - 1);

            for (int i = 1; i <= lenA; i++) {
                char charA = a.charAt(i - 1);
                int cost = (charA == charB) ? 0 : 1;
                currentRow[i] = Math.min(
                        Math.min(currentRow[i - 1] + 1, previousRow[i] + 1),
                        previousRow[i - 1] + cost
                );
            }

            int[] temp = previousRow;
            previousRow = currentRow;
            currentRow = temp;
        }

        return previousRow[lenA];
    }
}
