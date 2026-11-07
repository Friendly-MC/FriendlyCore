package dev.lyy.friendlycore.modules.auctions.gui;

import java.util.Locale;

/**
 * Lightweight, safe boolean expression evaluator used for GUI template condition checks.
 * Supports comparison operators (==, !=, <=, >=, <, >) against booleans, numbers, and strings.
 */
public class AuctionExpressionEvaluator {

    public static boolean eval(String expression) {
        if (expression == null || expression.isBlank()) {
            return true;
        }

        String expr = expression.trim();

        if (expr.contains("==")) {
            String[] parts = expr.split("==", 2);
            return compare(parts[0].trim(), parts[1].trim()) == 0;
        } else if (expr.contains("!=")) {
            String[] parts = expr.split("!=", 2);
            return compare(parts[0].trim(), parts[1].trim()) != 0;
        } else if (expr.contains("<=")) {
            String[] parts = expr.split("<=", 2);
            Double n1 = tryParseDouble(parts[0].trim());
            Double n2 = tryParseDouble(parts[1].trim());
            if (n1 != null && n2 != null) {
                return n1 <= n2;
            }
            return false;
        } else if (expr.contains(">=")) {
            String[] parts = expr.split(">=", 2);
            Double n1 = tryParseDouble(parts[0].trim());
            Double n2 = tryParseDouble(parts[1].trim());
            if (n1 != null && n2 != null) {
                return n1 >= n2;
            }
            return false;
        } else if (expr.contains("<")) {
            String[] parts = expr.split("<", 2);
            Double n1 = tryParseDouble(parts[0].trim());
            Double n2 = tryParseDouble(parts[1].trim());
            if (n1 != null && n2 != null) {
                return n1 < n2;
            }
            return false;
        } else if (expr.contains(">")) {
            String[] parts = expr.split(">", 2);
            Double n1 = tryParseDouble(parts[0].trim());
            Double n2 = tryParseDouble(parts[1].trim());
            if (n1 != null && n2 != null) {
                return n1 > n2;
            }
            return false;
        }

        if (expr.equalsIgnoreCase("true")) return true;
        if (expr.equalsIgnoreCase("false")) return false;

        return true;
    }

    private static int compare(String left, String right) {
        left = stripQuotes(left);
        right = stripQuotes(right);

        if ((left.equalsIgnoreCase("true") || left.equalsIgnoreCase("false")) &&
                (right.equalsIgnoreCase("true") || right.equalsIgnoreCase("false"))) {
            return Boolean.valueOf(left).compareTo(Boolean.valueOf(right));
        }

        Double d1 = tryParseDouble(left);
        Double d2 = tryParseDouble(right);
        if (d1 != null && d2 != null) {
            return Double.compare(d1, d2);
        }

        return left.compareToIgnoreCase(right);
    }

    private static Double tryParseDouble(String str) {
        try {
            return Double.parseDouble(str.replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stripQuotes(String str) {
        if ((str.startsWith("\"") && str.endsWith("\"")) || (str.startsWith("'") && str.endsWith("'"))) {
            if (str.length() >= 2) {
                return str.substring(1, str.length() - 1);
            }
        }
        return str;
    }
}
