package dev.lyy.friendlycore.modules.orders.enums;

public enum OrderState {
    ACTIVE(0),
    FULFILLED(1),
    CANCELLED(2),
    EXPIRED(3);

    private final int id;

    OrderState(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    public static OrderState fromId(int id) {
        for (OrderState state : values()) {
            if (state.id == id) return state;
        }
        return ACTIVE;
    }
}
