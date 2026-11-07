package dev.lyy.friendlycore.common;

import org.bukkit.configuration.file.FileConfiguration;

import java.text.DecimalFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Number and currency formatter supporting configurable decimal places,
 * scale suffixes (K, M, B, T, Q, QT), thread-local formatters, and caching.
 */
public class NumberFormatter {

    private static final double QUINTILLION = 1.0E18;
    private static final double QUADRILLION = 1.0E15;
    private static final double TRILLION = 1.0E12;
    private static final double BILLION = 1.0E9;
    private static final double MILLION = 1.0E6;
    private static final double THOUSAND = 1.0E3;

    private final ConcurrentHashMap<Double, String> cache = new ConcurrentHashMap<>(256);

    private volatile int decimalPlaces = 2;
    private volatile String standardPattern = "#,##0.00";
    private volatile String suffixPattern = "#,##0.##";
    private volatile String suffixQuintillion = "QT";
    private volatile String suffixQuadrillion = "Q";
    private volatile String suffixTrillion = "T";
    private volatile String suffixBillion = "B";
    private volatile String suffixMillion = "M";
    private volatile String suffixThousand = "K";

    private final ThreadLocal<DecimalFormat> decimalFormat = ThreadLocal.withInitial(() -> new DecimalFormat(standardPattern));
    private final ThreadLocal<DecimalFormat> suffixDecimalFormat = ThreadLocal.withInitial(() -> new DecimalFormat(suffixPattern));

    public NumberFormatter() {
    }

    public void updateSettings(FileConfiguration config) {
        if (config != null && config.contains("format")) {
            this.decimalPlaces = Math.max(0, config.getInt("format.decimal-places", 2));
            this.suffixThousand = config.getString("format.thousands", "K");
            this.suffixMillion = config.getString("format.millions", "M");
            this.suffixBillion = config.getString("format.billions", "B");
            this.suffixTrillion = config.getString("format.trillions", "T");
            this.suffixQuadrillion = config.getString("format.quadrillions", "Q");
            this.suffixQuintillion = config.getString("format.quintillions", "QT");

            if (decimalPlaces == 0) {
                this.standardPattern = "#,##0";
                this.suffixPattern = "#,##0";
            } else {
                this.standardPattern = "#,##0." + "0".repeat(decimalPlaces);
                this.suffixPattern = "#,##0." + "#".repeat(decimalPlaces);
            }
        }
        cache.clear();
    }

    private DecimalFormat getDecimalFormat() {
        DecimalFormat df = decimalFormat.get();
        if (!df.toPattern().equals(standardPattern)) {
            df.applyPattern(standardPattern);
        }
        return df;
    }

    private DecimalFormat getSuffixDecimalFormat() {
        DecimalFormat df = suffixDecimalFormat.get();
        if (!df.toPattern().equals(suffixPattern)) {
            df.applyPattern(suffixPattern);
        }
        return df;
    }

    public String format(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount)) {
            return "0.00";
        }

        String cached = cache.get(amount);
        if (cached != null) {
            return cached;
        }

        boolean isNegative = amount < 0;
        double abs = Math.abs(amount);
        String result;

        if (abs >= QUINTILLION) {
            result = formatWithSuffix(abs, QUINTILLION, suffixQuintillion);
        } else if (abs >= QUADRILLION) {
            result = formatWithSuffix(abs, QUADRILLION, suffixQuadrillion);
        } else if (abs >= TRILLION) {
            result = formatWithSuffix(abs, TRILLION, suffixTrillion);
        } else if (abs >= BILLION) {
            result = formatWithSuffix(abs, BILLION, suffixBillion);
        } else if (abs >= MILLION) {
            result = formatWithSuffix(abs, MILLION, suffixMillion);
        } else if (abs >= THOUSAND) {
            result = formatWithSuffix(abs, THOUSAND, suffixThousand);
        } else {
            result = getDecimalFormat().format(abs);
        }

        if (isNegative) {
            result = "-" + result;
        }

        if (cache.size() >= 2048) {
            cache.clear();
        }
        cache.put(amount, result);
        return result;
    }

    public String formatExact(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount)) {
            return "0.00";
        }
        return getDecimalFormat().format(amount);
    }

    /**
     * Formats numbers with short suffix scale (K, M, B, etc.) when >= 1,000,
     * and trims trailing zeros for numbers < 1,000 (e.g. 0 -> "0", 50.5 -> "50.5").
     */
    public String formatShort(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount)) {
            return "0";
        }

        boolean isNegative = amount < 0;
        double abs = Math.abs(amount);
        String result;

        if (abs >= QUINTILLION) {
            result = formatWithSuffix(abs, QUINTILLION, suffixQuintillion);
        } else if (abs >= QUADRILLION) {
            result = formatWithSuffix(abs, QUADRILLION, suffixQuadrillion);
        } else if (abs >= TRILLION) {
            result = formatWithSuffix(abs, TRILLION, suffixTrillion);
        } else if (abs >= BILLION) {
            result = formatWithSuffix(abs, BILLION, suffixBillion);
        } else if (abs >= MILLION) {
            result = formatWithSuffix(abs, MILLION, suffixMillion);
        } else if (abs >= THOUSAND) {
            result = formatWithSuffix(abs, THOUSAND, suffixThousand);
        } else {
            result = getSuffixDecimalFormat().format(abs);
        }

        if (isNegative) {
            result = "-" + result;
        }

        return result;
    }

    private String formatWithSuffix(double value, double divisor, String suffix) {
        double divided = value / divisor;
        return getSuffixDecimalFormat().format(divided) + suffix;
    }

    /**
     * Parses numeric strings with short scale suffixes (e.g. "10k", "2.5m", "1b").
     */
    public static double parseAmount(String input) throws NumberFormatException {
        if (input == null) {
            throw new NumberFormatException("Input cannot be null");
        }
        String clean = input.trim().toLowerCase();
        if (clean.isEmpty()) {
            throw new NumberFormatException("Input cannot be empty");
        }

        double multiplier = 1.0;

        if (clean.endsWith("qt")) {
            multiplier = QUINTILLION;
            clean = clean.substring(0, clean.length() - 2).trim();
        } else if (clean.endsWith("q")) {
            multiplier = QUADRILLION;
            clean = clean.substring(0, clean.length() - 1).trim();
        } else if (clean.endsWith("t")) {
            multiplier = TRILLION;
            clean = clean.substring(0, clean.length() - 1).trim();
        } else if (clean.endsWith("b")) {
            multiplier = BILLION;
            clean = clean.substring(0, clean.length() - 1).trim();
        } else if (clean.endsWith("m")) {
            multiplier = MILLION;
            clean = clean.substring(0, clean.length() - 1).trim();
        } else if (clean.endsWith("k")) {
            multiplier = THOUSAND;
            clean = clean.substring(0, clean.length() - 1).trim();
        }

        if (clean.isEmpty()) {
            throw new NumberFormatException("No number specified in: " + input);
        }

        double base = Double.parseDouble(clean);
        double result = base * multiplier;

        if (Double.isNaN(result) || Double.isInfinite(result)) {
            throw new NumberFormatException("Numeric overflow or NaN for: " + input);
        }

        return result;
    }
}
