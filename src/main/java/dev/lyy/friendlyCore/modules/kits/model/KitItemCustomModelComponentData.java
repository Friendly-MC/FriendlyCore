package dev.lyy.friendlycore.modules.kits.model;

import java.util.ArrayList;
import java.util.List;

public class KitItemCustomModelComponentData {
    private List<String> flags;
    private List<String> colors;
    private List<String> floats;
    private List<String> strings;

    public KitItemCustomModelComponentData(List<String> flags, List<String> colors, List<String> floats, List<String> strings) {
        this.flags = flags != null ? new ArrayList<>(flags) : new ArrayList<>();
        this.colors = colors != null ? new ArrayList<>(colors) : new ArrayList<>();
        this.floats = floats != null ? new ArrayList<>(floats) : new ArrayList<>();
        this.strings = strings != null ? new ArrayList<>(strings) : new ArrayList<>();
    }

    public List<String> getFlags() {
        return flags;
    }

    public void setFlags(List<String> flags) {
        this.flags = flags != null ? new ArrayList<>(flags) : new ArrayList<>();
    }

    public List<String> getColors() {
        return colors;
    }

    public void setColors(List<String> colors) {
        this.colors = colors != null ? new ArrayList<>(colors) : new ArrayList<>();
    }

    public List<String> getFloats() {
        return floats;
    }

    public void setFloats(List<String> floats) {
        this.floats = floats != null ? new ArrayList<>(floats) : new ArrayList<>();
    }

    public List<String> getStrings() {
        return strings;
    }

    public void setStrings(List<String> strings) {
        this.strings = strings != null ? new ArrayList<>(strings) : new ArrayList<>();
    }

    public KitItemCustomModelComponentData clone() {
        return new KitItemCustomModelComponentData(flags, colors, floats, strings);
    }
}
