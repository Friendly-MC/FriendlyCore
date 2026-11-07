package dev.lyy.friendlycore.modules.teams.model;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** One ownership token per open attempt; a saving session remains reserved. */
public final class VaultSession {
    private enum State { ACQUIRING, READY, OPEN, CLOSING }
    private final UUID playerUuid;
    private final UUID token = UUID.randomUUID();
    private final String serverId;
    private State state = State.ACQUIRING;
    private volatile CompletableFuture<Void> pending = CompletableFuture.completedFuture(null);

    public VaultSession(UUID playerUuid, String serverId) {
        this.playerUuid = playerUuid;
        this.serverId = serverId;
    }

    public UUID playerUuid() { return playerUuid; }
    public UUID token() { return token; }
    public String serverId() { return serverId; }
    public synchronized void ready() { if (state == State.ACQUIRING) state = State.READY; }
    public synchronized boolean isReady() { return state == State.READY; }
    public synchronized boolean isOpen() { return state == State.OPEN; }
    public synchronized boolean open() {
        if (state != State.READY) return false;
        state = State.OPEN;
        return true;
    }
    public synchronized boolean beginSave() {
        if (state != State.OPEN) return false;
        state = State.CLOSING;
        return true;
    }
    public synchronized boolean beginRelease() {
        if (state != State.READY) return false;
        state = State.CLOSING;
        return true;
    }
    public CompletableFuture<Void> pending() { return pending; }
    public void pending(CompletableFuture<Void> pending) { this.pending = pending; }
}
