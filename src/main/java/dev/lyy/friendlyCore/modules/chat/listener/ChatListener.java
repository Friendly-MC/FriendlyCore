package dev.lyy.friendlycore.modules.chat.listener;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.chat.ChatModule;
import dev.lyy.friendlycore.modules.chat.model.ChatChannel;
import dev.lyy.friendlycore.modules.chat.model.ChatMessage;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Modern Folia, Canvas, and Paper asynchronous chat event listener.
 */
public class ChatListener implements Listener {

    private final FriendlyCore plugin;
    private final ChatModule chatModule;

    public ChatListener(FriendlyCore plugin, ChatModule chatModule) {
        this.plugin = plugin;
        this.chatModule = chatModule;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();

        String raw = PlainTextComponentSerializer.plainText().serialize(event.message());

        // Staff chat route via prefix or active toggle (prioritized before global mute/toggle)
        boolean isStaffToggled = chatModule.isStaffChatToggled(playerUuid);
        String staffPrefix = chatModule.getStaffChatPrefix();
        boolean hasStaffPrefix = staffPrefix != null && !staffPrefix.isEmpty() && raw.startsWith(staffPrefix);

        if ((isStaffToggled || hasStaffPrefix) && player.hasPermission("friendlycore.chat.staffchat")) {
            event.setCancelled(true);
            String staffMsg = hasStaffPrefix ? raw.substring(staffPrefix.length()).trim() : raw;
            if (!staffMsg.isEmpty()) {
                plugin.getScheduler().runEntity(player, () -> chatModule.sendStaffChat(player, staffMsg));
            }
            return;
        }

        if (chatModule.isChatMuted() && !player.hasPermission("friendlycore.chat.bypass.chatmuted")) {
            event.setCancelled(true);
            plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                    "chat-mute.message-cancelled", "<red>You cannot chat while the server chat is muted!</red>"));
            return;
        }

        // Check if player has toggled their own public chat off
        if (chatModule.isChatToggled(playerUuid) && !player.hasPermission("friendlycore.chat.bypass.chattoggle")) {
            event.setCancelled(true);
            plugin.getTextService().sendMessage(player, plugin.getConfigService().getMessage("modules/chat/messages.yml",
                    "chat-toggle.no-chatting", "<red>You cannot chat while you have public chat toggled off!</red>"));
            return;
        }

        // Sanitize formatting permissions, escape unparsed tags, and run filter checks
        String sanitized = dev.lyy.friendlycore.common.TextService.sanitizeChatInput(player, raw);
        String safeRaw = player.hasPermission("friendlycore.chat.minimessage")
                ? sanitized
                : net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(sanitized);
        ChatMessage chatMessage = new ChatMessage(player, safeRaw, event.message(), ChatChannel.PUBLIC);
        boolean allowed = chatModule.getFilterManager().processMessage(chatMessage, false);
        if (!allowed || chatMessage.isCancelled()) {
            event.setCancelled(true);
            return;
        }

        // Filter viewers based on ignore lists and toggled chat preferences
        Set<Audience> viewers = event.viewers();
        boolean bypassIgnore = player.hasPermission("friendlycore.chat.bypass.ignore");
        boolean bypassChatToggle = player.hasPermission("friendlycore.chat.bypass.chattoggle");
        viewers.removeIf(audience -> {
            if (!(audience instanceof Player viewerPlayer)) return false;
            if (!viewerPlayer.isOnline()) return true;
            UUID viewerUuid = viewerPlayer.getUniqueId();
            if (viewerUuid.equals(playerUuid)) return false;
            return !chatModule.canReceivePublicChat(viewerUuid, playerUuid, bypassIgnore, bypassChatToggle);
        });

        // Keep the transport text free of local mention styling.
        chatMessage.setRawMessage(chatModule.getRenderManager().processRenders(chatMessage));
        Component messageComp = plugin.getTextService().parse(chatMessage.getRawMessage());
        messageComp = chatModule.getRenderManager().getMentionHandler()
                .processMentions(chatMessage, messageComp, viewers, chatModule.getPingMutedPlayers());
        Component formattedMessage = chatModule.renderChatMessage(
                player, Map.of("%player%", player.getName()), messageComp);

        event.renderer(ChatRenderer.viewerUnaware((source, sourceDisplayName, msg) -> formattedMessage));

        // Cross-server Redis sync
        if (chatModule.isCrossServerPublicChatEnabled()) {
            chatModule.broadcastCrossServerPublicChat(player, chatMessage.getRawMessage(), chatMessage.getCreatedSnapshots());
        }
    }
}
