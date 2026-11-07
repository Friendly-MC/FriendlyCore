package dev.lyy.friendlycore.modules.rtp;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.TextService;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Handles animated action bars, title/subtitle transitions, particle animations,
 * and audio cues throughout the RTP teleportation lifecycle.
 */
public class RtpActionbarAnimator {

    private static final String[] SPINNER_FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    private final FriendlyCore plugin;

    private boolean actionbarEnabled;

    private boolean titlesEnabled;
    private boolean suppressChatMessages;
    private boolean titleSearchingEnabled;
    private int titleSearchingFadeIn, titleSearchingStay, titleSearchingFadeOut;
    private boolean titleWarmupEnabled;
    private int titleWarmupFadeIn, titleWarmupStay, titleWarmupFadeOut;
    private boolean titleSuccessEnabled;
    private int titleSuccessFadeIn, titleSuccessStay, titleSuccessFadeOut;
    private boolean titleCancelledEnabled;
    private int titleCancelledFadeIn, titleCancelledStay, titleCancelledFadeOut;
    private boolean titleFailedEnabled;
    private int titleFailedFadeIn, titleFailedStay, titleFailedFadeOut;

    private String soundWarmupTick;
    private String soundSuccess;
    private String soundCancel;
    private String soundSearching;

    private boolean warmupParticlesEnabled;
    private Particle warmupParticle;
    private boolean teleportParticlesEnabled;
    private Particle teleportParticle;

    public RtpActionbarAnimator(FriendlyCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        FileConfiguration config = plugin.getConfigService().getRtpConfig();

        this.actionbarEnabled = config.getBoolean("effects.actionbar.enabled", true);

        this.titlesEnabled = config.getBoolean("effects.titles.enabled", true);
        this.suppressChatMessages = config.getBoolean("effects.titles.suppress-chat-messages", false);

        this.titleSearchingEnabled = config.getBoolean("effects.titles.searching.enabled", false);
        this.titleSearchingFadeIn = config.getInt("effects.titles.searching.fade-in-ticks", 5);
        this.titleSearchingStay = config.getInt("effects.titles.searching.stay-ticks", 30);
        this.titleSearchingFadeOut = config.getInt("effects.titles.searching.fade-out-ticks", 5);

        this.titleWarmupEnabled = config.getBoolean("effects.titles.warmup.enabled", true);
        this.titleWarmupFadeIn = config.getInt("effects.titles.warmup.fade-in-ticks", 2);
        this.titleWarmupStay = config.getInt("effects.titles.warmup.stay-ticks", 20);
        this.titleWarmupFadeOut = config.getInt("effects.titles.warmup.fade-out-ticks", 5);

        this.titleSuccessEnabled = config.getBoolean("effects.titles.success.enabled", true);
        this.titleSuccessFadeIn = config.getInt("effects.titles.success.fade-in-ticks", 10);
        this.titleSuccessStay = config.getInt("effects.titles.success.stay-ticks", 50);
        this.titleSuccessFadeOut = config.getInt("effects.titles.success.fade-out-ticks", 15);

        this.titleCancelledEnabled = config.getBoolean("effects.titles.cancelled.enabled", true);
        this.titleCancelledFadeIn = config.getInt("effects.titles.cancelled.fade-in-ticks", 5);
        this.titleCancelledStay = config.getInt("effects.titles.cancelled.stay-ticks", 35);
        this.titleCancelledFadeOut = config.getInt("effects.titles.cancelled.fade-out-ticks", 10);

        this.titleFailedEnabled = config.getBoolean("effects.titles.failed.enabled", true);
        this.titleFailedFadeIn = config.getInt("effects.titles.failed.fade-in-ticks", 5);
        this.titleFailedStay = config.getInt("effects.titles.failed.stay-ticks", 40);
        this.titleFailedFadeOut = config.getInt("effects.titles.failed.fade-out-ticks", 10);

        this.soundWarmupTick = config.getString("effects.sounds.warmup-tick", "BLOCK_NOTE_BLOCK_HAT");
        this.soundSuccess = config.getString("effects.sounds.teleport-success", "ENTITY_ENDERMAN_TELEPORT");
        this.soundCancel = config.getString("effects.sounds.teleport-cancel", "BLOCK_NOTE_BLOCK_BASS");
        this.soundSearching = config.getString("effects.sounds.search-searching", "UI_BUTTON_CLICK");

        this.warmupParticlesEnabled = config.getBoolean("effects.particles.warmup-enabled", true);
        String wpStr = config.getString("effects.particles.warmup-particle", "PORTAL");
        this.warmupParticle = dev.lyy.friendlycore.common.EffectUtil.parseParticle(wpStr, Particle.PORTAL);

        this.teleportParticlesEnabled = config.getBoolean("effects.particles.teleport-enabled", true);
        String tpStr = config.getString("effects.particles.teleport-particle", "DRAGON_BREATH");
        this.teleportParticle = dev.lyy.friendlycore.common.EffectUtil.parseParticle(tpStr, Particle.DRAGON_BREATH);
    }

    /**
     * Renders searching actionbar and title.
     */
    public void renderSearching(Player player, int attempt, int maxAttempts, int tickCount, String worldName) {
        if (player == null || !player.isOnline()) return;

        if (actionbarEnabled) {
            String spinner = SPINNER_FRAMES[(tickCount / 2) % SPINNER_FRAMES.length];
            String template = plugin.getConfigService().getMessages().getString("rtp.actionbar-searching",
                    "&e⚡ &6&lRTP &8» &fʟᴏᴀᴅɪɴɢ ᴄʜᴜɴᴋs... ᴘʟᴇᴀsᴇ ᴡᴀɪᴛ");

            plugin.getTextService().sendActionBar(player, template, Map.of(
                    "%spinner%", spinner,
                    "%attempt%", String.valueOf(attempt),
                    "%max_attempts%", String.valueOf(maxAttempts),
                    "%player%", player.getName(),
                    "%world%", worldName != null ? worldName : player.getWorld().getName()
            ));
        }

        if (titlesEnabled && titleSearchingEnabled && tickCount == 1) {
            String title = plugin.getConfigService().getMessages().getString("rtp.titles.searching.title",
                    "#fbf100⚡ &l&nL#fbe500&l&nO#fcd800&l&nA#fccc00&l&nD#fcc000&l&nI#fdb300&l&nN#fda700&l&nG&r #fda700⚡");
            String subtitle = plugin.getConfigService().getMessages().getString("rtp.titles.searching.subtitle",
                    "&fʟᴏᴀᴅɪɴɢ ᴄʜᴜɴᴋs... ᴘʟᴇᴀsᴇ ᴡᴀɪᴛ");

            plugin.getTextService().sendTitle(player, title, subtitle,
                    titleSearchingFadeIn, titleSearchingStay, titleSearchingFadeOut,
                    Map.of(
                            "%world%", worldName != null ? worldName : player.getWorld().getName(),
                            "%player%", player.getName()
                    ));
        }

        if (soundSearching != null && !soundSearching.isEmpty() && (tickCount == 1 || tickCount % 10 == 0)) {
            TextService.playSound(player, soundSearching, 0.4f, 1.2f);
        }
    }

    /**
     * Renders warmup progress actionbar, title countdown, and particles.
     */
    public void renderWarmup(Player player, int elapsedTicks, int totalTicks) {
        if (player == null || !player.isOnline()) return;

        double progress = Math.min(1.0, (double) elapsedTicks / (double) totalTicks);
        int remainingSeconds = Math.max(1, (int) Math.ceil((totalTicks - elapsedTicks) / 20.0));
        String formattedSeconds = String.valueOf(remainingSeconds);

        if (actionbarEnabled) {
            String template = plugin.getConfigService().getMessages().getString("rtp.actionbar-warmup",
                    "&e⚡ &6&lRTP &8» &fᴛᴇʟᴇᴘᴏʀᴛɪɴɢ ɪɴ #fbf100%time% ⌚ &fsᴇᴄᴏɴᴅs... <red><italic>(Don't move!)</italic></red>");

            plugin.getTextService().sendActionBar(player, template, Map.of(
                    "%seconds%", formattedSeconds,
                    "%time%", formattedSeconds,
                    "%player%", player.getName()
            ));
        }

        // Send title updates on whole second intervals
        if (titlesEnabled && titleWarmupEnabled && (elapsedTicks % 20 == 0 || elapsedTicks == 2)) {
            String title = plugin.getConfigService().getMessages().getString("rtp.titles.warmup.title",
                    "#fbf100⚡ &l&nT#fbea00&l&nE#fbe200&l&nL#fcdb00&l&nE#fcd300&l&nP#fccc00&l&nO#fcc500&l&nR#fcbd00&l&nT#fdb600&l&nI#fdae00&l&nN#fda700&l&nG&r #fda700⚡");
            String subtitle = plugin.getConfigService().getMessages().getString("rtp.titles.warmup.subtitle",
                    "&fᴛᴇʟᴇᴘᴏʀᴛɪɴɢ ɪɴ #fbf100%time% ⌚ &fsᴇᴄᴏɴᴅs...");

            plugin.getTextService().sendTitle(player, title, subtitle,
                    titleWarmupFadeIn, titleWarmupStay, titleWarmupFadeOut,
                    Map.of(
                            "%seconds%", formattedSeconds,
                            "%time%", formattedSeconds,
                            "%player%", player.getName()
                    ));
        }

        if (elapsedTicks % 20 == 0) {
            float pitch = 1.0f + (float) progress * 0.8f;
            TextService.playSound(player, soundWarmupTick, 0.8f, pitch);
        }

        if (warmupParticlesEnabled && elapsedTicks % 4 == 0) {
            spawnWarmupParticles(player, elapsedTicks);
        }
    }

    private void spawnWarmupParticles(Player player, int tick) {
        Location loc = player.getLocation();
        double angle = (tick * 0.3) % (2 * Math.PI);
        double radius = 0.8;
        double x1 = loc.getX() + radius * Math.cos(angle);
        double z1 = loc.getZ() + radius * Math.sin(angle);
        double x2 = loc.getX() + radius * Math.cos(angle + Math.PI);
        double z2 = loc.getZ() + radius * Math.sin(angle + Math.PI);

        dev.lyy.friendlycore.common.EffectUtil.spawnParticle(player, warmupParticle, x1, loc.getY() + 0.2, z1, 1, 0, 0, 0, 0);
        dev.lyy.friendlycore.common.EffectUtil.spawnParticle(player, warmupParticle, x2, loc.getY() + 1.2, z2, 1, 0, 0, 0, 0);
    }

    /**
     * Renders success effects upon landing.
     */
    public void renderSuccess(Player player, Location targetLoc) {
        if (player == null || !player.isOnline()) return;

        String biomeName = "Wilderness";
        try {
            if (targetLoc != null && targetLoc.getWorld() != null) {
                biomeName = targetLoc.getBlock().getBiome().name();
            }
        } catch (Throwable ignored) {
            biomeName = "Wilderness";
        }
        if (biomeName == null || biomeName.isEmpty()) {
            biomeName = "Wilderness";
        }

        if (actionbarEnabled) {
            String template = plugin.getConfigService().getMessages().getString("rtp.actionbar-success",
                    "&a✔ &2&lRTP &8» &fʏᴏᴜ ʜᴀᴠᴇ ʙᴇᴇɴ sᴜᴄᴄᴇssꜰᴜʟʟʏ ᴛᴇʟᴇᴘᴏʀᴛᴇᴅ.");

            plugin.getTextService().sendActionBar(player, template, Map.of(
                    "%x%", String.valueOf(targetLoc.getBlockX()),
                    "%y%", String.valueOf(targetLoc.getBlockY()),
                    "%z%", String.valueOf(targetLoc.getBlockZ()),
                    "%biome%", biomeName,
                    "%player%", player.getName()
            ));
        }

        if (titlesEnabled && titleSuccessEnabled) {
            String title = plugin.getConfigService().getMessages().getString("rtp.titles.success.title",
                    "#9cfb00✔ &l&nT#8bfb03&l&nE#79fb06&l&nL#68fc08&l&nE#57fc0b&l&nP#45fc0e&l&nO#34fc11&l&nR#23fd13&l&nT#11fd16&l&nE#00fd19&l&nD&r #00fd19✔");
            String subtitle = plugin.getConfigService().getMessages().getString("rtp.titles.success.subtitle",
                    "&fʏᴏᴜ ʜᴀᴠᴇ ʙᴇᴇɴ sᴜᴄᴄᴇssꜰᴜʟʟʏ ᴛᴇʟᴇᴘᴏʀᴛᴇᴅ.");

            plugin.getTextService().sendTitle(player, title, subtitle,
                    titleSuccessFadeIn, titleSuccessStay, titleSuccessFadeOut,
                    Map.of(
                            "%x%", String.valueOf(targetLoc.getBlockX()),
                            "%y%", String.valueOf(targetLoc.getBlockY()),
                            "%z%", String.valueOf(targetLoc.getBlockZ()),
                            "%biome%", biomeName,
                            "%player%", player.getName()
                    ));
        }

        TextService.playSound(player, soundSuccess, 1.0f, 1.0f);

        if (teleportParticlesEnabled) {
            dev.lyy.friendlycore.common.EffectUtil.spawnParticle(player, teleportParticle, targetLoc.clone().add(0, 1.0, 0), 30, 0.5, 0.5, 0.5, 0.05);
            dev.lyy.friendlycore.common.EffectUtil.spawnParticle(player, Particle.PORTAL, targetLoc.clone().add(0, 0.5, 0), 40, 0.4, 0.8, 0.4, 0.1);
        }
    }

    /**
     * Renders cancelled actionbar, title, and sounds.
     */
    public void renderCancelled(Player player, String reason) {
        if (player == null || !player.isOnline()) return;

        if (actionbarEnabled) {
            String template = plugin.getConfigService().getMessages().getString("rtp.actionbar-cancelled",
                    "&c✘ &4&lRTP &8» &fᴛᴇʟᴇᴘᴏʀᴛ ᴡᴀs ᴄᴀɴᴄᴇʟʟᴇᴅ!");
            plugin.getTextService().sendActionBar(player, template, Map.of(
                    "%reason%", reason != null ? reason : "Teleportation cancelled",
                    "%player%", player.getName()
            ));
        }

        if (titlesEnabled && titleCancelledEnabled) {
            String title = plugin.getConfigService().getMessages().getString("rtp.titles.cancelled.title",
                    "#FF0000✘ &l&nCANCELLED&r #FF0000✘");
            String subtitle = plugin.getConfigService().getMessages().getString("rtp.titles.cancelled.subtitle",
                    "&fᴛᴇʟᴇᴘᴏʀᴛ ᴡᴀs ᴄᴀɴᴄᴇʟʟᴇᴅ!");

            plugin.getTextService().sendTitle(player, title, subtitle,
                    titleCancelledFadeIn, titleCancelledStay, titleCancelledFadeOut,
                    Map.of(
                            "%reason%", reason != null ? reason : "Teleportation cancelled",
                            "%player%", player.getName()
                    ));
        }

        TextService.playSound(player, soundCancel, 1.0f, 0.7f);
    }

    /**
     * Renders failed actionbar, title, and sounds.
     */
    public void renderFailed(Player player) {
        if (player == null || !player.isOnline()) return;

        if (actionbarEnabled) {
            String template = plugin.getConfigService().getMessages().getString("rtp.actionbar-failed",
                    "&c✘ &4&lRTP &8» &fɴᴏ sᴀꜰᴇ sᴘᴏᴛs ʟᴏᴄᴀᴛᴇᴅ, ᴛʀʏ ᴀɢᴀɪɴ");
            plugin.getTextService().sendActionBar(player, template, Map.of(
                    "%player%", player.getName()
            ));
        }

        if (titlesEnabled && titleFailedEnabled) {
            String title = plugin.getConfigService().getMessages().getString("rtp.titles.failed.title",
                    "#FF0000✘ &l&nCANCELLED&r #FF0000✘");
            String subtitle = plugin.getConfigService().getMessages().getString("rtp.titles.failed.subtitle",
                    "&fɴᴏ sᴀꜰᴇ sᴘᴏᴛs ʟᴏᴄᴀᴛᴇᴅ, ᴛʀʏ ᴀɢᴀɪɴ");

            plugin.getTextService().sendTitle(player, title, subtitle,
                    titleFailedFadeIn, titleFailedStay, titleFailedFadeOut,
                    Map.of("%player%", player.getName()));
        }

        TextService.playSound(player, soundCancel, 1.0f, 0.7f);
    }

    public boolean isActionbarEnabled() {
        return actionbarEnabled;
    }

    public boolean isTitlesEnabled() {
        return titlesEnabled;
    }

    public boolean isSuppressChatMessages() {
        return titlesEnabled && suppressChatMessages;
    }
}
