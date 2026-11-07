package dev.lyy.friendlycore.modules.pets.model;

/**
 * Result enum for withdrawing a pet to an egg item.
 */
public enum WithdrawResult {
    OK,
    NO_ACTIVE,
    NOT_WITHDRAWABLE,
    CANNOT_AFFORD,
    INVENTORY_FULL
}
