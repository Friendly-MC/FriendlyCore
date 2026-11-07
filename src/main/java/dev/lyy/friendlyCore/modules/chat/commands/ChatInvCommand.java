package dev.lyy.friendlycore.modules.chat.commands;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.chat.ChatModule;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Handles opening interactive show preview GUIs (/chatinv <uuid>).
 */
public class ChatInvCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final ChatModule chatModule;

    public ChatInvCommand(FriendlyCore plugin, ChatModule chatModule) {
        this.plugin = plugin;
        this.chatModule = chatModule;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                    "general.player-only", "<red>This command can only be used by players.</red>"));
            return true;
        }

        if (args.length < 1) {
            return true;
        }

        UUID snapshotId;
        try {
            snapshotId = UUID.fromString(args[0]);
        } catch (IllegalArgumentException e) {
            plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                    "renders.item-expired", "<red>The requested inventory/item preview has expired.</red>"));
            return true;
        }

        chatModule.getRenderManager().getSnapshotAsync(snapshotId).thenAccept(snapshot ->
                plugin.getScheduler().runEntity(player, () -> {
                    if (!player.isOnline()) return;
                    if (snapshot == null) {
                        plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                                "renders.item-expired", "<red>The requested inventory/item preview has expired.</red>"));
                        return;
                    }

                    Inventory inv = chatModule.getRenderManager().getGuiBuilder().buildGui(snapshot);
                    if (inv != null) {
                        player.openInventory(inv);
                    }
                }));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return Collections.emptyList();
    }
}
