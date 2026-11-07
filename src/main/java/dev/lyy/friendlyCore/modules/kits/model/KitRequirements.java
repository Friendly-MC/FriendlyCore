package dev.lyy.friendlycore.modules.kits.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Requirements and pricing rules required to claim or purchase a kit.
 */
public class KitRequirements {

    private boolean oneTimeRequirements;
    private double price;
    private List<String> extraRequirements;
    private List<String> message;
    private List<String> actionsOnBuy;

    public KitRequirements() {
        this(false, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), 0.0);
    }

    public KitRequirements(boolean oneTimeRequirements, List<String> extraRequirements, List<String> message,
                           List<String> actionsOnBuy, double price) {
        this.oneTimeRequirements = oneTimeRequirements;
        this.extraRequirements = extraRequirements != null ? new ArrayList<>(extraRequirements) : new ArrayList<>();
        this.message = message != null ? new ArrayList<>(message) : new ArrayList<>();
        this.actionsOnBuy = actionsOnBuy != null ? new ArrayList<>(actionsOnBuy) : new ArrayList<>();
        this.price = price;
    }

    public boolean isOneTimeRequirements() {
        return oneTimeRequirements;
    }

    public void setOneTimeRequirements(boolean oneTimeRequirements) {
        this.oneTimeRequirements = oneTimeRequirements;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public List<String> getExtraRequirements() {
        return extraRequirements;
    }

    public void setExtraRequirements(List<String> extraRequirements) {
        this.extraRequirements = extraRequirements != null ? new ArrayList<>(extraRequirements) : new ArrayList<>();
    }

    public List<String> getMessage() {
        return message;
    }

    public void setMessage(List<String> message) {
        this.message = message != null ? new ArrayList<>(message) : new ArrayList<>();
    }

    public List<String> getActionsOnBuy() {
        return actionsOnBuy;
    }

    public void setActionsOnBuy(List<String> actionsOnBuy) {
        this.actionsOnBuy = actionsOnBuy != null ? new ArrayList<>(actionsOnBuy) : new ArrayList<>();
    }

    public KitRequirements clone() {
        return new KitRequirements(oneTimeRequirements, extraRequirements, message, actionsOnBuy, price);
    }
}
