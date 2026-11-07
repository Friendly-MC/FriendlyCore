package dev.lyy.friendlycore.common;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.function.Consumer;

/**
 * Resolves a player name without blocking a tick thread. {@link Bukkit#getOfflinePlayer(String)}
 * can perform a Mojang profile lookup for names Paper has not cached.
 */
public final class PlayerLookup {

    private PlayerLookup() {
    }

    /**
     * Runs {@code then} on the sender's thread (entity thread for players, global otherwise).
     */
    public static void byName(FriendlyCore plugin, CommandSender sender, String name, Consumer<OfflinePlayer> then) {
        OfflinePlayer local = Bukkit.getPlayerExact(name);
        if (local == null) {
            local = Bukkit.getOfflinePlayerIfCached(name);
        }
        if (local != null) {
            then.accept(local);
            return;
        }
        plugin.getScheduler().runAsync(() -> {
            OfflinePlayer resolved = Bukkit.getOfflinePlayer(name);
            Runnable task = () -> then.accept(resolved);
            if (sender instanceof Player player) {
                plugin.getScheduler().runEntity(player, () -> {
                    if (player.isOnline()) task.run();
                });
            } else {
                plugin.getScheduler().runGlobal(task);
            }
        });
    }
}
