package dev.lyy.friendlycore.modules.kits.model;

import java.util.ArrayList;
import java.util.List;

public class KitItemFireworkData {
    private List<String> rocketEffects;
    private String starEffect;
    private int power;

    public KitItemFireworkData(List<String> rocketEffects, String starEffect, int power) {
        this.rocketEffects = rocketEffects != null ? new ArrayList<>(rocketEffects) : new ArrayList<>();
        this.starEffect = starEffect;
        this.power = power;
    }

    public List<String> getRocketEffects() {
        return rocketEffects;
    }

    public void setRocketEffects(List<String> rocketEffects) {
        this.rocketEffects = rocketEffects != null ? new ArrayList<>(rocketEffects) : new ArrayList<>();
    }

    public String getStarEffect() {
        return starEffect;
    }

    public void setStarEffect(String starEffect) {
        this.starEffect = starEffect;
    }

    public int getPower() {
        return power;
    }

    public void setPower(int power) {
        this.power = power;
    }

    public KitItemFireworkData clone() {
        return new KitItemFireworkData(rocketEffects, starEffect, power);
    }
}
