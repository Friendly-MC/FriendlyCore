package dev.lyy.friendlycore.modules.kits.model;

public class KitItemTrimData {
    private String pattern;
    private String material;

    public KitItemTrimData(String pattern, String material) {
        this.pattern = pattern;
        this.material = material;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public String getMaterial() {
        return material;
    }

    public void setMaterial(String material) {
        this.material = material;
    }

    public KitItemTrimData clone() {
        return new KitItemTrimData(pattern, material);
    }
}
