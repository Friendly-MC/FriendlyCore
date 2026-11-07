package dev.lyy.friendlycore.modules.orders.model;

public record OrderPermissionLimit(String permission, int limit) implements Comparable<OrderPermissionLimit> {
    @Override
    public int compareTo(OrderPermissionLimit o) {
        return Integer.compare(this.limit, o.limit);
    }
}
