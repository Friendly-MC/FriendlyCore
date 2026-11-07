package dev.lyy.friendlycore.modules.chat.filter;

import java.util.regex.Pattern;

/**
 * Represents a single filter rule or pattern match.
 */
public class ChatRule {

    public enum RuleType {
        SWEARING,
        RACISM,
        IP,
        URL,
        DOMAIN,
        PHONE,
        UNICODE,
        CUSTOM,
        WHITELIST
    }

    private final String name;
    private final RuleType type;
    private final Pattern pattern;
    private final int points;

    public ChatRule(String name, RuleType type, Pattern pattern, int points) {
        this.name = name;
        this.type = type;
        this.pattern = pattern;
        this.points = points;
    }

    public String getName() {
        return name;
    }

    public RuleType getType() {
        return type;
    }

    public Pattern getPattern() {
        return pattern;
    }

    public int getPoints() {
        return points;
    }
}
