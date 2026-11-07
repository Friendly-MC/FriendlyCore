package dev.lyy.friendlycore.modules.chat.listener;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.chat.ChatModule;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerHideEntityEvent;
import org.bukkit.event.player.PlayerShowEntityEvent;

/**
 * Handles async player lifecycle for chat data caching and cleanup.
 */
public class ChatPlayerListener implements Listener {

    private final FriendlyCore plugin;
    private final ChatModule chatModule;

    public ChatPlayerListener(FriendlyCore plugin, ChatModule chatModule) {
        this.plugin = plugin;
        this.chatModule = chatModule;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        chatModule.loadPlayer(player.getUniqueId(), player.getName());
        chatModule.getRenderManager().getMentionHandler().addCompletions(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        chatModule.unloadPlayer(player.getUniqueId());
        chatModule.getRenderManager().getMentionHandler().removeCompletions(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHide(PlayerHideEntityEvent event) {
        if (event.getEntity() instanceof Player target) {
            chatModule.getRenderManager().getMentionHandler().updateCompletion(event.getPlayer(), target);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onShow(PlayerShowEntityEvent event) {
        if (event.getEntity() instanceof Player target) {
            chatModule.getRenderManager().getMentionHandler().updateCompletion(event.getPlayer(), target);
        }
    }
}
