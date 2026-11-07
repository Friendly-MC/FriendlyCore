package dev.lyy.friendlycore.modules.placeholders;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.economy.AccountData;
import dev.lyy.friendlycore.modules.kits.Kit;
import dev.lyy.friendlycore.modules.kits.model.PlayerKitData;
import dev.lyy.friendlycore.modules.pvp.PlayerStats;
import dev.lyy.friendlycore.modules.spawn.SpawnData;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * PlaceholderAPI expansion for FriendlyCore (including SwiftPay and MoneyTitles aliases).
 */
public class FriendlyExpansion extends PlaceholderExpansion {

    private final FriendlyCore plugin;

    public FriendlyExpansion(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "friendlycore";
    }

    @Override
    public @NotNull String getAuthor() {
        return "FriendlyDev";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        String p = params.toLowerCase();

        // Server & spawn global placeholders (no player context required)
        if (plugin.getSpawnManager() != null) {
            SpawnData data = plugin.getSpawnManager().getSpawnData();
            if (p.equals("spawn_is_set") || p.equals("spawn_set") || p.equals("spawn_exists")) {
                return (data != null && data.world() != null && !data.world().isEmpty()) ? "true" : "false";
            }
            if (p.equals("spawn_world")) {
                return (data != null && data.world() != null) ? data.world() : "none";
            }
            if (p.equals("spawn_server")) {
                return (data != null && data.server() != null) ? data.server() : plugin.getSpawnManager().resolveLocalServerId();
            }
            if (p.equals("spawn_x")) {
                return data != null ? String.valueOf(data.x()) : "0.0";
            }
            if (p.equals("spawn_y")) {
                return data != null ? String.valueOf(data.y()) : "64.0";
            }
            if (p.equals("spawn_z")) {
                return data != null ? String.valueOf(data.z()) : "0.0";
            }
            if (p.equals("spawn_yaw")) {
                return data != null ? String.valueOf(data.yaw()) : "0.0";
            }
            if (p.equals("spawn_pitch")) {
                return data != null ? String.valueOf(data.pitch()) : "0.0";
            }
            if (p.equals("spawn_warmup")) {
                return String.valueOf(plugin.getConfigService().getSpawnConfig().getInt("spawn.warmup-seconds", 3));
            }
        }

        if (plugin.getJoinQuitManager() != null) {
            if (p.equals("unique_joins") || p.equals("unique_players") || p.equals("total_joins")) {
                return String.valueOf(plugin.getJoinQuitManager().getUniqueJoinsCount());
            }
        }

        if (plugin.getTeamsModule() != null && plugin.getTeamsModule().getPlaceholderExpansion() != null) {
            if (p.startsWith("leaderboard_")) {
                return plugin.getTeamsModule().getPlaceholderExpansion().resolvePlaceholder(offlinePlayer, p);
            }
        }

        if (plugin.getEconomyManager() != null) {
            if (p.startsWith("baltop_player_")) {
                try {
                    int rank = Integer.parseInt(p.substring("baltop_player_".length()));
                    List<AccountData> baltop = plugin.getEconomyManager().getBaltop(100).getNow(List.of());
                    if (rank > 0 && rank <= baltop.size()) {
                        return baltop.get(rank - 1).getUsername();
                    }
                } catch (Exception ignored) {}
                return "---";
            }
            if (p.startsWith("baltop_balance_raw_")) {
                try {
                    int rank = Integer.parseInt(p.substring("baltop_balance_raw_".length()));
                    List<AccountData> baltop = plugin.getEconomyManager().getBaltop(100).getNow(List.of());
                    if (rank > 0 && rank <= baltop.size()) {
                        return String.valueOf(baltop.get(rank - 1).getBalance());
                    }
                } catch (Exception ignored) {}
                return "0.0";
            }
            if (p.startsWith("baltop_balance_")) {
                try {
                    int rank = Integer.parseInt(p.substring("baltop_balance_".length()));
                    List<AccountData> baltop = plugin.getEconomyManager().getBaltop(100).getNow(List.of());
                    if (rank > 0 && rank <= baltop.size()) {
                        return plugin.getNumberFormatter().format(baltop.get(rank - 1).getBalance());
                    }
                } catch (Exception ignored) {}
                return "0.00";
            }
        }

        if (offlinePlayer == null) return "";

        if (p.equals("player") || p.equals("player_name") || p.equals("name")) {
            return offlinePlayer.getName() != null ? offlinePlayer.getName() : "";
        }
        if (p.equals("player_uuid") || p.equals("uuid")) {
            return offlinePlayer.getUniqueId().toString();
        }
        if (p.equals("player_displayname") || p.equals("displayname") || p.equals("display_name")) {
            if (offlinePlayer.isOnline() && offlinePlayer.getPlayer() != null) {
                return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().serialize(offlinePlayer.getPlayer().displayName());
            }
            return offlinePlayer.getName() != null ? offlinePlayer.getName() : "";
        }

        if (p.equals("balance") || p.equals("bal") || p.equals("balance_formatted") || p.equals("bal_formatted") || p.equals("money")) {
            double bal = 0.0;
            if (plugin.getPvpManager() != null && plugin.getPvpManager().getPvpEconomyManager() != null) {
                bal = plugin.getPvpManager().getPvpEconomyManager().getBalance(offlinePlayer);
            } else if (plugin.getEconomyManager() != null) {
                bal = plugin.getEconomyManager().getBalance(offlinePlayer.getUniqueId());
            }
            return plugin.getNumberFormatter().format(bal);
        }
        if (p.equals("balance_short") || p.equals("bal_short")) {
            double bal = 0.0;
            if (plugin.getPvpManager() != null && plugin.getPvpManager().getPvpEconomyManager() != null) {
                bal = plugin.getPvpManager().getPvpEconomyManager().getBalance(offlinePlayer);
            } else if (plugin.getEconomyManager() != null) {
                bal = plugin.getEconomyManager().getBalance(offlinePlayer.getUniqueId());
            }
            return plugin.getNumberFormatter().formatShort(bal);
        }
        if (p.equals("balance_exact") || p.equals("bal_exact")) {
            double bal = 0.0;
            if (plugin.getPvpManager() != null && plugin.getPvpManager().getPvpEconomyManager() != null) {
                bal = plugin.getPvpManager().getPvpEconomyManager().getBalance(offlinePlayer);
            } else if (plugin.getEconomyManager() != null) {
                bal = plugin.getEconomyManager().getBalance(offlinePlayer.getUniqueId());
            }
            return plugin.getNumberFormatter().formatExact(bal);
        }
        if (p.equals("balance_raw") || p.equals("bal_raw")) {
            double bal = 0.0;
            if (plugin.getPvpManager() != null && plugin.getPvpManager().getPvpEconomyManager() != null) {
                bal = plugin.getPvpManager().getPvpEconomyManager().getBalance(offlinePlayer);
            } else if (plugin.getEconomyManager() != null) {
                bal = plugin.getEconomyManager().getBalance(offlinePlayer.getUniqueId());
            }
            return String.valueOf(bal);
        }
        if (p.equals("pay_enabled")) {
            if (plugin.getEconomyManager() != null) {
                AccountData acc = plugin.getEconomyManager().getCachedAccount(offlinePlayer.getUniqueId());
                return (acc == null || acc.isPayEnabled()) ? "true" : "false";
            }
            return "true";
        }

        if (plugin.getStatsManager() != null) {
            if (p.equals("kills")) {
                PlayerStats stats = plugin.getStatsManager().getStats(offlinePlayer.getUniqueId(), offlinePlayer.getName());
                return String.valueOf(stats.getKills());
            }
            if (p.equals("deaths")) {
                PlayerStats stats = plugin.getStatsManager().getStats(offlinePlayer.getUniqueId(), offlinePlayer.getName());
                return String.valueOf(stats.getDeaths());
            }
            if (p.equals("kd") || p.equals("kd_ratio")) {
                PlayerStats stats = plugin.getStatsManager().getStats(offlinePlayer.getUniqueId(), offlinePlayer.getName());
                return String.valueOf(stats.getKDRatio());
            }
            if (p.equals("streak") || p.equals("killstreak")) {
                PlayerStats stats = plugin.getStatsManager().getStats(offlinePlayer.getUniqueId(), offlinePlayer.getName());
                return String.valueOf(stats.getCurrentStreak());
            }
            if (p.equals("highest_streak") || p.equals("best_streak")) {
                PlayerStats stats = plugin.getStatsManager().getStats(offlinePlayer.getUniqueId(), offlinePlayer.getName());
                return String.valueOf(stats.getHighestStreak());
            }
        }

        if (plugin.getHomesManager() != null) {
            if (p.equals("in_combat") || p.equals("combat_tagged") || p.equals("is_in_combat")) {
                return plugin.getHomesManager().isInCombat(offlinePlayer.getUniqueId()) ? "true" : "false";
            }
            if (p.equals("combat_time") || p.equals("combat_seconds") || p.equals("combat_cooldown")) {
                int sec = plugin.getHomesManager().getRemainingCombatSeconds(offlinePlayer.getUniqueId());
                return sec > 0 ? sec + "s" : "0s";
            }
            if (p.equals("combat_time_raw") || p.equals("combat_seconds_raw")) {
                return String.valueOf(plugin.getHomesManager().getRemainingCombatSeconds(offlinePlayer.getUniqueId()));
            }
        }

        if (plugin.getSpawnManager() != null) {
            if (p.equals("spawn_is_teleporting") || p.equals("spawn_teleporting")) {
                return plugin.getSpawnManager().isTeleporting(offlinePlayer.getUniqueId()) ? "true" : "false";
            }
            if (p.equals("spawn_countdown") || p.equals("spawn_warmup_remaining")) {
                int rem = plugin.getSpawnManager().getRemainingWarmupSeconds(offlinePlayer.getUniqueId());
                return rem > 0 ? rem + "s" : "0s";
            }
            if (p.equals("spawn_countdown_raw") || p.equals("spawn_warmup_remaining_raw")) {
                return String.valueOf(plugin.getSpawnManager().getRemainingWarmupSeconds(offlinePlayer.getUniqueId()));
            }
            if (p.equals("spawn_on_cooldown") || p.equals("spawn_is_cooldown")) {
                return plugin.getSpawnManager().isOnCooldown(offlinePlayer.getUniqueId()) ? "true" : "false";
            }
            if (p.equals("spawn_cooldown")) {
                long cd = plugin.getSpawnManager().getRemainingCooldownSeconds(offlinePlayer.getUniqueId());
                return cd > 0 ? cd + "s" : "0s";
            }
            if (p.equals("spawn_cooldown_raw")) {
                return String.valueOf(plugin.getSpawnManager().getRemainingCooldownSeconds(offlinePlayer.getUniqueId()));
            }
        }

        if (plugin.getTpaManager() != null) {
            dev.lyy.friendlycore.modules.tpa.TpaProfile prof = plugin.getTpaManager().getProfile(offlinePlayer.getUniqueId(), offlinePlayer.getName());
            if (prof != null) {
                if (p.equals("tpa_enabled")) {
                    return prof.isTpaEnabled() ? "true" : "false";
                }
                if (p.equals("tpahere_enabled")) {
                    return prof.isTpaHereEnabled() ? "true" : "false";
                }
                if (p.equals("tpauto_enabled")) {
                    return prof.isTpAutoEnabled() ? "true" : "false";
                }
                if (p.equals("tpagui_enabled")) {
                    return prof.isShowGui() ? "true" : "false";
                }
                if (p.equals("tpa_pending_requests") || p.equals("tpa_requests")) {
                    return String.valueOf(prof.getIncomingRequests().size());
                }
                if (p.equals("tpa_daily_count")) {
                    return String.valueOf(prof.getDailyCount());
                }
            }
            if (p.equals("tpa_is_teleporting") || p.equals("tpa_teleporting")) {
                return plugin.getTpaManager().isTeleporting(offlinePlayer.getUniqueId()) ? "true" : "false";
            }
            if (p.equals("tpa_countdown") || p.equals("tpa_teleport_countdown")) {
                int rem = plugin.getTpaManager().getRemainingCountdownSeconds(offlinePlayer.getUniqueId());
                return rem > 0 ? rem + "s" : "0s";
            }
            if (p.equals("tpa_countdown_raw")) {
                return String.valueOf(plugin.getTpaManager().getRemainingCountdownSeconds(offlinePlayer.getUniqueId()));
            }
            if (p.equals("tpa_on_cooldown")) {
                return plugin.getTpaManager().isRequestOnCooldown(offlinePlayer.getUniqueId()) ? "true" : "false";
            }
            if (p.equals("tpa_cooldown")) {
                long cd = plugin.getTpaManager().getRemainingRequestCooldownSeconds(offlinePlayer.getUniqueId());
                return cd > 0 ? cd + "s" : "0s";
            }
            if (p.equals("tpa_cooldown_raw")) {
                return String.valueOf(plugin.getTpaManager().getRemainingRequestCooldownSeconds(offlinePlayer.getUniqueId()));
            }
        }

        if (plugin.getRtpManager() != null) {
            if (p.equals("rtp_is_teleporting") || p.equals("rtp_teleporting") || p.equals("rtp_warmup_active")) {
                return plugin.getRtpManager().isWarmupActive(offlinePlayer.getUniqueId()) ? "true" : "false";
            }
            if (p.equals("rtp_countdown") || p.equals("rtp_warmup") || p.equals("rtp_warmup_remaining")) {
                int rem = plugin.getRtpManager().getRemainingWarmupSeconds(offlinePlayer.getUniqueId());
                return rem > 0 ? rem + "s" : "0s";
            }
            if (p.equals("rtp_countdown_raw") || p.equals("rtp_warmup_raw")) {
                return String.valueOf(plugin.getRtpManager().getRemainingWarmupSeconds(offlinePlayer.getUniqueId()));
            }
            if (p.equals("rtp_on_cooldown") || p.equals("rtp_is_cooldown")) {
                return plugin.getRtpManager().isOnCooldown(offlinePlayer.getUniqueId()) ? "true" : "false";
            }
            if (p.equals("rtp_cooldown")) {
                long cd = plugin.getRtpManager().getRemainingCooldownSeconds(offlinePlayer.getUniqueId());
                return cd > 0 ? cd + "s" : "0s";
            }
            if (p.equals("rtp_cooldown_raw")) {
                return String.valueOf(plugin.getRtpManager().getRemainingCooldownSeconds(offlinePlayer.getUniqueId()));
            }
        }

        if (plugin.getKitManager() != null) {
            if (p.startsWith("kit_cooldown_raw_") || p.startsWith("cooldown_raw_")) {
                String kitName = p.startsWith("kit_cooldown_raw_") ? p.substring("kit_cooldown_raw_".length()) : p.substring("cooldown_raw_".length());
                Kit kit = plugin.getKitManager().getKit(kitName);
                if (kit != null) {
                    PlayerKitData data = plugin.getKitManager().getPlayerData(offlinePlayer.getUniqueId());
                    long cdUntil = data != null ? data.getCooldownUntil(kit.getName()) : 0L;
                    long now = System.currentTimeMillis();
                    return cdUntil > now ? String.valueOf((cdUntil - now) / 1000L) : "0";
                }
                return "0";
            }
            if (p.startsWith("kit_cooldown_") || p.startsWith("cooldown_")) {
                String kitName = p.startsWith("kit_cooldown_") ? p.substring("kit_cooldown_".length()) : p.substring("cooldown_".length());
                Kit kit = plugin.getKitManager().getKit(kitName);
                if (kit != null) {
                    PlayerKitData data = plugin.getKitManager().getPlayerData(offlinePlayer.getUniqueId());
                    long cdUntil = data != null ? data.getCooldownUntil(kit.getName()) : 0L;
                    long now = System.currentTimeMillis();
                    if (cdUntil > now) {
                        return plugin.getKitManager().formatCooldown((cdUntil - now) / 1000L);
                    }
                    return plugin.getConfigService().getMessages().getString("kits.ready", "Ready");
                }
                return "";
            }
            if (p.startsWith("kit_onetime_ready_") || p.startsWith("onetime_ready_")) {
                String kitName = p.startsWith("kit_onetime_ready_") ? p.substring("kit_onetime_ready_".length()) : p.substring("onetime_ready_".length());
                Kit kit = plugin.getKitManager().getKit(kitName);
                if (kit != null) {
                    PlayerKitData data = plugin.getKitManager().getPlayerData(offlinePlayer.getUniqueId());
                    boolean claimed = data != null && data.isOneTimeClaimed(kit.getName());
                    return claimed ? "false" : "true";
                }
                return "true";
            }
            if (p.startsWith("kit_status_") || p.startsWith("status_")) {
                String kitName = p.startsWith("kit_status_") ? p.substring("kit_status_".length()) : p.substring("status_".length());
                Kit kit = plugin.getKitManager().getKit(kitName);
                if (kit != null) {
                    if (offlinePlayer.isOnline() && offlinePlayer.getPlayer() != null && !kit.hasPermission(offlinePlayer.getPlayer())) {
                        return "NO_PERMISSION";
                    }
                    PlayerKitData data = plugin.getKitManager().getPlayerData(offlinePlayer.getUniqueId());
                    if (kit.isOneTime() && data != null && data.isOneTimeClaimed(kit.getName())) {
                        return "CLAIMED";
                    }
                    if (kit.getCooldownSeconds() > 0 && data != null && data.getCooldownUntil(kit.getName()) > System.currentTimeMillis()) {
                        return "COOLDOWN";
                    }
                    return "READY";
                }
                return "NOT_FOUND";
            }
            if (p.startsWith("kit_price_") || p.startsWith("price_")) {
                String kitName = p.startsWith("kit_price_") ? p.substring("kit_price_".length()) : p.substring("price_".length());
                Kit kit = plugin.getKitManager().getKit(kitName);
                if (kit != null && kit.getRequirements() != null) {
                    return String.valueOf(kit.getRequirements().getPrice());
                }
                return "0";
            }
        }

        if (p.equals("ping")) {
            if (offlinePlayer.isOnline() && offlinePlayer.getPlayer() != null) {
                return String.valueOf(offlinePlayer.getPlayer().getPing());
            }
            return "0";
        }

        if (plugin.getNightVisionManager() != null) {
            if (p.equals("nightvision") || p.equals("nv")) {
                dev.lyy.friendlycore.modules.nightvision.NightVisionProfile prof = plugin.getNightVisionManager().getCachedProfile(offlinePlayer.getUniqueId());
                return (prof != null && prof.isEnabled()) ? "enabled" : "disabled";
            }
            if (p.equals("nightvision_status") || p.equals("nv_status")) {
                dev.lyy.friendlycore.modules.nightvision.NightVisionProfile prof = plugin.getNightVisionManager().getCachedProfile(offlinePlayer.getUniqueId());
                return (prof != null && prof.isEnabled()) ? "Enabled" : "Disabled";
            }
            if (p.equals("nv_active") || p.equals("nightvision_active")) {
                if (offlinePlayer.isOnline() && offlinePlayer.getPlayer() != null) {
                    boolean active = plugin.getNightVisionManager().isNightVisionActive(offlinePlayer.getPlayer());
                    return active ? "true" : "false";
                }
                dev.lyy.friendlycore.modules.nightvision.NightVisionProfile prof = plugin.getNightVisionManager().getCachedProfile(offlinePlayer.getUniqueId());
                return (prof != null && prof.isEnabled()) ? "true" : "false";
            }
        }

        if (p.equals("is_bedrock") || p.equals("bedrock")) {
            if (plugin.getHookManager() != null && plugin.getHookManager().getFloodgateHook() != null) {
                boolean isBedrock = plugin.getHookManager().getFloodgateHook().isBedrockPlayer(offlinePlayer.getUniqueId());
                return isBedrock ? "true" : "false";
            }
            return "false";
        }

        if (plugin.getTeamsModule() != null && plugin.getTeamsModule().getPlaceholderExpansion() != null) {
            if (p.startsWith("team_") || p.startsWith("teams_")) {
                String sub = p.startsWith("team_") ? p.substring("team_".length()) : p.substring("teams_".length());
                String res = plugin.getTeamsModule().getPlaceholderExpansion().resolvePlaceholder(offlinePlayer, sub);
                if (res != null) return res;
                return plugin.getTeamsModule().getPlaceholderExpansion().resolvePlaceholder(offlinePlayer, p);
            }
        }

        if (plugin.getHomesManager() != null) {
            if (p.equals("homes_count") || p.equals("home_count") || p.equals("homes")) {
                dev.lyy.friendlycore.modules.homes.model.PlayerHomes profile = plugin.getHomesManager().getPlayerHomes(offlinePlayer.getUniqueId());
                return String.valueOf(profile != null ? profile.getHomesCount() : 0);
            }
            if (p.equals("homes_max") || p.equals("home_max") || p.equals("max_homes")) {
                return String.valueOf(plugin.getHomesManager().getMaxHomes(offlinePlayer.getUniqueId()));
            }
            if (p.equals("homes_list")) {
                dev.lyy.friendlycore.modules.homes.model.PlayerHomes profile = plugin.getHomesManager().getPlayerHomes(offlinePlayer.getUniqueId());
                if (profile == null || profile.getHomes().isEmpty()) return "None";
                List<String> names = new ArrayList<>();
                for (dev.lyy.friendlycore.modules.homes.model.HomeData h : profile.getHomes().values()) {
                    names.add(h.getDisplayName());
                }
                return String.join(", ", names);
            }
            if (p.equals("home_is_teleporting") || p.equals("homes_is_teleporting") || p.equals("home_teleporting") || p.equals("homes_teleporting")) {
                return plugin.getHomesManager().isTeleporting(offlinePlayer.getUniqueId()) ? "true" : "false";
            }
            if (p.equals("home_countdown") || p.equals("homes_countdown")) {
                int rem = plugin.getHomesManager().getRemainingCountdownSeconds(offlinePlayer.getUniqueId());
                return rem > 0 ? rem + "s" : "0s";
            }
            if (p.equals("home_countdown_raw") || p.equals("homes_countdown_raw")) {
                return String.valueOf(plugin.getHomesManager().getRemainingCountdownSeconds(offlinePlayer.getUniqueId()));
            }
            if (p.equals("home_in_combat") || p.equals("homes_in_combat") || p.equals("home_combat")) {
                return plugin.getHomesManager().isInCombat(offlinePlayer.getUniqueId()) ? "true" : "false";
            }
            if (p.equals("home_combat_time") || p.equals("homes_combat_time") || p.equals("home_combat_seconds") || p.equals("homes_combat_seconds")) {
                int sec = plugin.getHomesManager().getRemainingCombatSeconds(offlinePlayer.getUniqueId());
                return sec > 0 ? sec + "s" : "0s";
            }
            if (p.startsWith("home_name_") || p.startsWith("home_")) {
                String slotStr = p.startsWith("home_name_") ? p.substring("home_name_".length()) : p.substring("home_".length());
                if (slotStr.endsWith("_name")) slotStr = slotStr.substring(0, slotStr.length() - "_name".length());
                try {
                    int slot = Integer.parseInt(slotStr);
                    dev.lyy.friendlycore.modules.homes.model.PlayerHomes profile = plugin.getHomesManager().getPlayerHomes(offlinePlayer.getUniqueId());
                    if (profile != null) {
                        dev.lyy.friendlycore.modules.homes.model.HomeData h = profile.getHome(slot);
                        if (h != null) return h.getDisplayName();
                    }
                } catch (NumberFormatException ignored) {}
                return "";
            }
        }

        if (plugin.getPetManager() != null) {
            dev.lyy.friendlycore.modules.pets.model.PlayerPetData petData = plugin.getPetManager().getPlayerData(offlinePlayer.getUniqueId());
            if (p.equals("pet") || p.equals("active_pet") || p.equals("pet_name")) {
                if (petData != null && petData.getActivePet() != null) {
                    dev.lyy.friendlycore.modules.pets.model.Pet pet = plugin.getPetManager().getPet(petData.getActivePet());
                    return pet != null ? pet.getName() : petData.getActivePet();
                }
                return "";
            }
            if (p.equals("pet_id") || p.equals("active_pet_id")) {
                return (petData != null && petData.getActivePet() != null) ? petData.getActivePet() : "";
            }
            if (p.equals("has_active_pet") || p.equals("has_pet_active")) {
                return (petData != null && petData.getActivePet() != null) ? "true" : "false";
            }
            if (p.equals("total_pets") || p.equals("pets_total") || p.equals("unlocked_pets")) {
                return String.valueOf(petData != null ? petData.getUnlockedPets().size() : 0);
            }
            if (p.equals("pet_hidden") || p.equals("hide_pet")) {
                return (petData != null && petData.isHidePet()) ? "true" : "false";
            }
            if (p.startsWith("has_pet_") || p.startsWith("pet_unlocked_") || p.startsWith("owns_pet_")) {
                String petId = p.startsWith("has_pet_") ? p.substring("has_pet_".length()) :
                        (p.startsWith("pet_unlocked_") ? p.substring("pet_unlocked_".length()) : p.substring("owns_pet_".length()));
                return (petData != null && petData.hasPet(petId)) ? "true" : "false";
            }
            if (p.endsWith("_can_activate")) {
                String petId = p.substring(0, p.length() - "_can_activate".length());
                dev.lyy.friendlycore.modules.pets.model.Pet pet = plugin.getPetManager().getPet(petId);
                if (pet != null && offlinePlayer.isOnline() && offlinePlayer.getPlayer() != null) {
                    return pet.canActivate(offlinePlayer.getPlayer()) ? "true" : "false";
                }
                return "false";
            }
        }

        if (plugin.getPvpManager() != null) {
            if (p.equals("pvp_in_combat") || p.equals("in_combat") || p.equals("combat_tagged")) {
                return plugin.getPvpManager().getCombatTagManager().isTagged(offlinePlayer.getUniqueId()) ? "true" : "false";
            }
            if (p.equals("pvp_combat_time") || p.equals("combat_time") || p.equals("combat_seconds")) {
                int sec = plugin.getPvpManager().getCombatTagManager().getRemainingSeconds(offlinePlayer.getUniqueId());
                return sec > 0 ? sec + "s" : "0s";
            }
            if (p.equals("pvp_combat_time_raw") || p.equals("combat_time_raw")) {
                return String.valueOf(plugin.getPvpManager().getCombatTagManager().getRemainingSeconds(offlinePlayer.getUniqueId()));
            }
            if (p.equals("pvp_opponent") || p.equals("combat_opponent")) {
                java.util.UUID oppUuid = plugin.getPvpManager().getCombatTagManager().getOpponent(offlinePlayer.getUniqueId());
                if (oppUuid != null) {
                    org.bukkit.entity.Player opp = org.bukkit.Bukkit.getPlayer(oppUuid);
                    if (opp != null) return opp.getName();
                }
                return "None";
            }
            if (p.equals("pvp_newbie") || p.equals("newbie_protected") || p.equals("is_newbie")) {
                return plugin.getPvpManager().getNewbieProtectionManager().hasProtection(offlinePlayer.getUniqueId()) ? "true" : "false";
            }
            if (p.equals("pvp_newbie_time") || p.equals("newbie_time")) {
                int sec = plugin.getPvpManager().getNewbieProtectionManager().getRemainingSeconds(offlinePlayer.getUniqueId());
                return sec > 0 ? sec + "s" : "0s";
            }
            if (p.equals("pvp_newbie_time_raw") || p.equals("newbie_time_raw")) {
                return String.valueOf(plugin.getPvpManager().getNewbieProtectionManager().getRemainingSeconds(offlinePlayer.getUniqueId()));
            }
            if (p.equals("pvp_enabled") || p.equals("pvp_status")) {
                return plugin.getPvpManager().hasPvpEnabled(offlinePlayer.getUniqueId()) ? "true" : "false";
            }

            if (plugin.getPvpManager().getPvpEconomyManager() != null) {
                dev.lyy.friendlycore.modules.pvp.PvpEconomyManager.PvpEconomyRecord record =
                        plugin.getPvpManager().getPvpEconomyManager().getLastRecord(offlinePlayer.getUniqueId());

                if (p.equals("pvp_money_lost") || p.equals("money_lost") || p.equals("pvp_last_money_lost") || p.equals("victim_lost")) {
                    return record != null ? record.formattedLost() : "0";
                }
                if (p.equals("pvp_money_lost_raw") || p.equals("money_lost_raw")) {
                    return record != null ? String.valueOf(record.moneyLost()) : "0.0";
                }
                if (p.equals("pvp_money_gained") || p.equals("money_gained") || p.equals("pvp_last_money_gained") || p.equals("killer_gained")) {
                    return record != null ? record.formattedGained() : "0";
                }
                if (p.equals("pvp_money_gained_raw") || p.equals("money_gained_raw")) {
                    return record != null ? String.valueOf(record.moneyGained()) : "0.0";
                }
                if (p.equals("pvp_last_victim") || p.equals("last_victim") || p.equals("victim")) {
                    return (record != null && record.victimName() != null && !record.victimName().isEmpty()) ? record.victimName() : "None";
                }
                if (p.equals("pvp_last_killer") || p.equals("last_killer") || p.equals("killer")) {
                    return (record != null && record.killerName() != null && !record.killerName().isEmpty()) ? record.killerName() : "None";
                }
            }
        }

        if (plugin.getStatsManager() != null) {
            PlayerStats stats = plugin.getStatsManager().getStats(offlinePlayer.getUniqueId(), offlinePlayer.getName());
            if (p.equals("pvp_kills") || p.equals("kills")) {
                return String.valueOf(stats != null ? stats.getKills() : 0);
            }
            if (p.equals("pvp_deaths") || p.equals("deaths")) {
                return String.valueOf(stats != null ? stats.getDeaths() : 0);
            }
            if (p.equals("pvp_kdr") || p.equals("kdr") || p.equals("kd")) {
                return stats != null ? String.format("%.2f", stats.getKDRatio()) : "0.00";
            }
            if (p.equals("pvp_streak") || p.equals("killstreak") || p.equals("streak")) {
                return String.valueOf(stats != null ? stats.getCurrentStreak() : 0);
            }
            if (p.equals("pvp_highest_streak") || p.equals("best_streak") || p.equals("highest_killstreak")) {
                return String.valueOf(stats != null ? stats.getHighestStreak() : 0);
            }
        }

        // Auction house listings & limits (Full AxAuctions & FriendlyCore 1:1 Parity)
        if (plugin.getAuctionsModule() != null) {
            dev.lyy.friendlycore.modules.auctions.AuctionManager am = plugin.getAuctionsModule().getAuctionManager();
            if (p.equals("auctions_total_active_count") || p.equals("ah_total_active") || p.equals("axauctions_total_active_count")) {
                return String.valueOf(am.getActiveListingCount());
            }
            if (p.equals("auctions_total_active_count_raw") || p.equals("axauctions_total_active_count_raw")) {
                return String.valueOf(am.getActiveListingCount());
            }
            if (p.equals("auctions_total_items_sold") || p.equals("axauctions_total_items_sold")
                    || p.equals("auctions_total_items_sold_raw") || p.equals("axauctions_total_items_sold_raw")) {
                return String.valueOf(am.getGlobalStatistics().sold());
            }
            if (p.equals("auctions_total_items_purchased") || p.equals("axauctions_total_items_purchased")
                    || p.equals("auctions_total_items_purchased_raw") || p.equals("axauctions_total_items_purchased_raw")) {
                return String.valueOf(am.getGlobalStatistics().purchased());
            }
            if (p.equals("auctions_total_money_made") || p.equals("axauctions_total_money_made")) {
                return am.formatPrice(am.getGlobalStatistics().earned());
            }
            if (p.equals("auctions_total_money_made_raw") || p.equals("axauctions_total_money_made_raw")) {
                return String.valueOf(am.getGlobalStatistics().earned());
            }
            if (p.equals("auctions_total_money_spent") || p.equals("axauctions_total_money_spent")) {
                return am.formatPrice(am.getGlobalStatistics().spent());
            }
            if (p.equals("auctions_total_money_spent_raw") || p.equals("axauctions_total_money_spent_raw")) {
                return String.valueOf(am.getGlobalStatistics().spent());
            }
            if (p.equals("auctions_categories_enabled") || p.equals("axauctions_categories_enabled")) {
                return String.valueOf(am.getCategoryManager().isEnabled());
            }
            if (p.equals("auctions_expired_items_enabled") || p.equals("axauctions_expired_items_enabled")) {
                return String.valueOf(plugin.getConfigService().get("modules/auctions/config.yml").getBoolean("separate-expired-items", true));
            }
            if (p.equals("auctions_auction_listing_confirmation_enabled") || p.equals("axauctions_auction_listing_confirmation_enabled")) {
                return String.valueOf(plugin.getConfigService().get("modules/auctions/config.yml").getBoolean("auction-listing-confirmation", false));
            }

            if (offlinePlayer != null) {
                if (p.equals("auctions_active_count") || p.equals("ah_active") || p.equals("axauctions_active_count")
                        || p.equals("auctions_purchasable_count") || p.equals("axauctions_purchasable_count")) {
                    return String.valueOf(am.getPlayerActiveItemCount(offlinePlayer.getUniqueId()));
                }
                if (p.equals("auctions_active_count_raw") || p.equals("axauctions_active_count_raw")
                        || p.equals("auctions_purchasable_count_raw") || p.equals("axauctions_purchasable_count_raw")) {
                    return String.valueOf(am.getPlayerActiveItemCount(offlinePlayer.getUniqueId()));
                }
                if (p.equals("auctions_expired_count") || p.equals("ah_expired") || p.equals("axauctions_expired_count")) {
                    return String.valueOf(am.getPlayerExpiredListings(offlinePlayer.getUniqueId()).size());
                }
                if (p.equals("auctions_expired_count_raw") || p.equals("axauctions_expired_count_raw")) {
                    return String.valueOf(am.getPlayerExpiredListings(offlinePlayer.getUniqueId()).size());
                }
                if (p.equals("auctions_sell_count") || p.equals("ah_sell_count") || p.equals("axauctions_sell_count")) {
                    return String.valueOf(am.getPlayerTotalItemCount(offlinePlayer.getUniqueId()));
                }
                if (p.equals("auctions_sell_count_raw") || p.equals("axauctions_sell_count_raw")) {
                    return String.valueOf(am.getPlayerTotalItemCount(offlinePlayer.getUniqueId()));
                }
                if (p.equals("auctions_sell_limit") || p.equals("ah_limit") || p.equals("axauctions_sell_limit")) {
                    org.bukkit.entity.Player online = offlinePlayer.getPlayer();
                    if (online == null) return "";
                    int lim = am.getPlayerListingLimit(online);
                    if (lim == -1 || lim > 99999) {
                        org.bukkit.configuration.file.FileConfiguration msgs = plugin.getConfigService().get("modules/auctions/messages.yml");
                        return msgs != null ? msgs.getString("limit-bypass-icon", "∞") : "∞";
                    }
                    return String.valueOf(lim);
                }
                if (p.equals("auctions_sell_limit_raw") || p.equals("axauctions_sell_limit_raw")) {
                    org.bukkit.entity.Player online = offlinePlayer.getPlayer();
                    if (online == null) return "";
                    int lim = am.getPlayerListingLimit(online);
                    return String.valueOf(lim);
                }
                if (p.equals("auctions_selected_category") || p.equals("axauctions_selected_category")) {
                    dev.lyy.friendlycore.modules.auctions.model.AuctionUser user = am.getUser(offlinePlayer.getUniqueId(), offlinePlayer.getName());
                    dev.lyy.friendlycore.modules.auctions.category.AuctionCategory cat = am.getCategoryManager().getCategory(user.getSelectedCategory());
                    return cat != null ? cat.getName() : "All";
                }
                if (p.equals("auctions_selected_category_raw") || p.equals("axauctions_selected_category_raw")) {
                    dev.lyy.friendlycore.modules.auctions.model.AuctionUser user = am.getUser(offlinePlayer.getUniqueId(), offlinePlayer.getName());
                    return user.getSelectedCategory();
                }
                if (p.equals("auctions_selected_sorting") || p.equals("axauctions_selected_sorting")) {
                    dev.lyy.friendlycore.modules.auctions.model.AuctionUser user = am.getUser(offlinePlayer.getUniqueId(), offlinePlayer.getName());
                    org.bukkit.configuration.file.FileConfiguration msgs = plugin.getConfigService().get("modules/auctions/messages.yml");
                    String sortKey = user.getSelectedSorting().getId();
                    return msgs != null ? msgs.getString("sorting." + sortKey, user.getSelectedSorting().getDisplayName()) : user.getSelectedSorting().getDisplayName();
                }
                if (p.equals("auctions_selected_sorting_raw") || p.equals("axauctions_selected_sorting_raw")) {
                    dev.lyy.friendlycore.modules.auctions.model.AuctionUser user = am.getUser(offlinePlayer.getUniqueId(), offlinePlayer.getName());
                    return user.getSelectedSorting().getId();
                }
                if (p.equals("auctions_selected_currency") || p.equals("axauctions_selected_currency")) {
                    return "Vault";
                }
                if (p.equals("auctions_selected_currency_raw") || p.equals("axauctions_selected_currency_raw")) {
                    return "vault";
                }
                if (p.equals("auctions_items_sold") || p.equals("axauctions_items_sold")) {
                    return String.valueOf(am.getStatistics(offlinePlayer.getUniqueId()).sold());
                }
                if (p.equals("auctions_items_sold_raw") || p.equals("axauctions_items_sold_raw")) {
                    return String.valueOf(am.getStatistics(offlinePlayer.getUniqueId()).sold());
                }
                if (p.equals("auctions_items_purchased") || p.equals("axauctions_items_purchased")) {
                    return String.valueOf(am.getStatistics(offlinePlayer.getUniqueId()).purchased());
                }
                if (p.equals("auctions_items_purchased_raw") || p.equals("axauctions_items_purchased_raw")) {
                    return String.valueOf(am.getStatistics(offlinePlayer.getUniqueId()).purchased());
                }
                if (p.equals("auctions_money_made") || p.equals("axauctions_money_made")) {
                    return am.formatPrice(am.getStatistics(offlinePlayer.getUniqueId()).earned());
                }
                if (p.equals("auctions_money_made_raw") || p.equals("axauctions_money_made_raw")) {
                    return String.valueOf(am.getStatistics(offlinePlayer.getUniqueId()).earned());
                }
                if (p.equals("auctions_money_spent") || p.equals("axauctions_money_spent")) {
                    return am.formatPrice(am.getStatistics(offlinePlayer.getUniqueId()).spent());
                }
                if (p.equals("auctions_money_spent_raw") || p.equals("axauctions_money_spent_raw")) {
                    return String.valueOf(am.getStatistics(offlinePlayer.getUniqueId()).spent());
                }
            }
        }

        // Buy Orders placeholders (Full DonutOrder & FriendlyCore Parity)
        if (plugin.getOrdersModule() != null) {
            dev.lyy.friendlycore.modules.orders.OrderManager om = plugin.getOrdersModule().getOrderManager();
            if (p.equals("orders_active_count") || p.equals("orders_total_active") || p.equals("donutorder_active_orders") || p.equals("donutorder_active")) {
                return String.valueOf(om.getActiveOrders().size());
            }

            if (offlinePlayer != null) {
                if (p.equals("orders_player_active") || p.equals("donutorder_player_active_orders")) {
                    return String.valueOf(om.getPlayerActiveOrderCount(offlinePlayer.getUniqueId()));
                }
                if (p.equals("orders_player_max") || p.equals("donutorder_player_max_orders") || p.equals("donutorder_max_orders")) {
                    org.bukkit.entity.Player online = offlinePlayer.getPlayer();
                    return String.valueOf(om.getPlayerMaxOrders(online));
                }
            }
        }

        return null;
    }
}
