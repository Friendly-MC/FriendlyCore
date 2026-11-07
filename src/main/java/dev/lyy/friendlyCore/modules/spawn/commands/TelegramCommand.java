package dev.lyy.friendlycore.modules.spawn.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.TextService;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Command executor for displaying community contact and store links (/telegram).
 */
public class TelegramCommand implements CommandExecutor {

    private final FriendlyCore plugin;

    public TelegramCommand(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.send(plugin, sender, "spawn", "this-command-can-only-be-executed-2");
            return true;
        }

        FileConfiguration spawnConfig = plugin.getConfigService().getSpawnConfig();
        String handle = spawnConfig.getString("telegram.handle", "leenith1314");
        String soundStr = spawnConfig.getString("telegram.sound", "ENTITY_EXPERIENCE_ORB_PICKUP");

        List<String> messages = plugin.getConfigService().getMessages().getStringList("telegram.message");
        if (messages.isEmpty()) {
            messages = List.of(
                    "",
                    " <#0099FF>✈ <bold>TELEGRAM</bold>",
                    "",
                    " <gray>Hey <white>%player%</white>! Want to buy a rank?</gray>",
                    " <gray>Contact the <#0099FF><bold>Owner</bold></#0099FF> on Telegram.</gray>",
                    "",
                    " <#FFE98D>★ <white>Telegram:</white> <#0099FF><underlined><click:open_url:'https://t.me/%handle%'>@%handle%</click></underlined></#0099FF>",
                    ""
            );
        }

        Map<String, String> placeholders = Map.of(
                "%player%", player.getName(),
                "%handle%", handle
        );

        for (String line : messages) {
            player.sendMessage(plugin.getTextService().parse(line, placeholders));
        }

        TextService.playSound(player, soundStr, 1.0f, 1.0f);

        return true;
    }
}
