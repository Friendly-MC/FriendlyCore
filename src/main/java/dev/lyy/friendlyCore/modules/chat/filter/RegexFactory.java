package dev.lyy.friendlycore.modules.chat.filter;

import dev.lyy.friendlycore.FriendlyCore;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Dynamic regular expression factory for chat filtering, link detection, and word blacklisting.
 */
public class RegexFactory {

    private static final String IP_REGEX = "\\b(([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\s?(\\.|\\(\\.\\)|\\(,\\)|-|;|:|,|\\(dot\\)|\\(DOT\\)|\\s)\\s?([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\s?(\\.|\\(\\.\\)|\\(,\\)|-|;|:|,|\\(dot\\)|\\(DOT\\)|\\s)\\s?([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\s?(\\.|\\(\\.\\)|\\(,\\)|-|;|:|,|\\(dot\\)|\\(DOT\\)|\\s)\\s?([01]?\\d\\d?|2[0-4]\\d|25[0-5])(:\\d{4,5})?)\\b";
    private static final String URL_REGEX = "(?i)((https?|ftp|file)\\s*:\\s*\\/\\/|www\\s*\\.\\s*|ftp\\s*\\.\\s*)[-A-Z0-9+&@#/%?=~_|!:,.;]*[-A-Z0-9+&@#/%=~_|](?=\\s|$)";
    private static final String DOMAIN_TEMPLATE = "(?i)\\b(?<!https?\\s{0,4}:\\s{0,4}\\/\\/|ftp\\s{0,4}:\\s{0,4}\\/\\/|www\\s{0,4}\\.\\s{0,4}|ftp\\s{0,4}\\.\\s{0,4})([\\p{L}0-9]([\\p{L}0-9\\-]{0,61}[\\p{L}0-9])?\\s*(?:\\.|,|;|\\(dot\\)|\\(DOT\\)|\\[dot\\]|\\[DOT\\])\\s*({tld_pattern})(:\\d{4,5})?)\\b";
    private static final String PHONE_REGEX = "\\b(\\+\\d{1,2}[- ]?)?(\\d{3}[- ]?){2}\\d{4}\\b";
    private static final String UNICODE_REGEX = "[^\\u0000-\\u007F]+";

    private final FriendlyCore plugin;
    private final LeetMap leetMap;
    private final Set<String> ianaTlds = new HashSet<>();
    private Pattern ipPattern;
    private Pattern urlPattern;
    private Pattern domainPattern;
    private Pattern phonePattern;
    private Pattern unicodePattern;

    public RegexFactory(LeetMap leetMap) {
        this(null, leetMap);
    }

    public RegexFactory(FriendlyCore plugin, LeetMap leetMap) {
        this.plugin = plugin;
        this.leetMap = leetMap;
        loadIanaTlds();
        compileDefaultPatterns();
    }

    public void reload() {
        loadIanaTlds();
        compileDefaultPatterns();
    }

    private void loadIanaTlds() {
        ianaTlds.clear();
        try (InputStream in = plugin != null ? plugin.getResource("modules/chat/iana_tlds.txt") : getClass().getClassLoader().getResourceAsStream("modules/chat/iana_tlds.txt")) {
            if (in != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim().toLowerCase();
                        if (!line.isEmpty() && !line.startsWith("#")) {
                            ianaTlds.add(line);
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (plugin != null) {
                plugin.getLogger().log(Level.WARNING, "Failed to load IANA TLDs dataset", e);
            }
        }
    }

    private void compileDefaultPatterns() {
        this.ipPattern = Pattern.compile(IP_REGEX);
        this.urlPattern = Pattern.compile(URL_REGEX);
        this.phonePattern = Pattern.compile(PHONE_REGEX);
        this.unicodePattern = Pattern.compile(UNICODE_REGEX);

        String tldPattern;
        if (ianaTlds.isEmpty()) {
            tldPattern = "[a-z]{2,6}";
        } else {
            tldPattern = ianaTlds.stream()
                    .map(tld -> tld.replace(".", "\\."))
                    .collect(Collectors.joining("|"));
        }
        this.domainPattern = Pattern.compile(DOMAIN_TEMPLATE.replace("{tld_pattern}", tldPattern));
    }

    public Pattern getIpPattern() {
        return ipPattern;
    }

    public Pattern getUrlPattern() {
        return urlPattern;
    }

    public Pattern getDomainPattern() {
        return domainPattern;
    }

    public Pattern getPhonePattern() {
        return phonePattern;
    }

    public Pattern getUnicodePattern() {
        return unicodePattern;
    }

    /**
     * Generates a compiled regex pattern for a blacklist word utilizing leet substitutions.
     */
    public Pattern generateWordPattern(String word, boolean aggressive) {
        if (word == null || word.isEmpty()) return null;
        String raw = word.toLowerCase();
        StringBuilder body = new StringBuilder();

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            List<String> replacements = leetMap.getReplacements(c);
            body.append("(?>");
            for (int j = 0; j < replacements.size(); j++) {
                if (j > 0) body.append("|");
                body.append(Pattern.quote(replacements.get(j)));
            }
            body.append(")++");
            if (i < raw.length() - 1) {
                body.append("[\\W_]*+");
            }
        }

        if (aggressive) {
            return Pattern.compile("(?i)(?:" + body + ")");
        } else {
            String asciiVariant = "(?i)\\b(?:" + body + ")\\b";
            String unicodeVariant = "(?i)(?<!\\p{L})(?:" + body + ")(?!\\p{L})";
            return Pattern.compile(asciiVariant + "|" + unicodeVariant);
        }
    }

    /**
     * Generates a whitelist regex pattern.
     */
    public Pattern generateWhitelistPattern(String input) {
        if (input == null || input.isEmpty()) return null;
        String escaped = Pattern.quote(input.toLowerCase());
        return Pattern.compile("(?i)\\b" + escaped + "\\b");
    }
}
