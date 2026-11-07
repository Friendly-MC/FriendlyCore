package dev.lyy.friendlycore.modules.kits.model;

import java.util.ArrayList;
import java.util.List;

public class KitItemBannerData {
    private List<String> patterns;
    private String baseColor;

    public KitItemBannerData(List<String> patterns, String baseColor) {
        this.patterns = patterns != null ? new ArrayList<>(patterns) : new ArrayList<>();
        this.baseColor = baseColor;
    }

    public List<String> getPatterns() {
        return patterns;
    }

    public void setPatterns(List<String> patterns) {
        this.patterns = patterns != null ? new ArrayList<>(patterns) : new ArrayList<>();
    }

    public String getBaseColor() {
        return baseColor;
    }

    public void setBaseColor(String baseColor) {
        this.baseColor = baseColor;
    }

    public KitItemBannerData clone() {
        return new KitItemBannerData(patterns, baseColor);
    }
}
