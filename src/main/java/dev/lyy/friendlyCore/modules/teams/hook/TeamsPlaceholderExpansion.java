package dev.lyy.friendlycore.modules.teams.hook;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.modules.teams.manager.TeamsLeaderboardManager;
import dev.lyy.friendlycore.modules.teams.manager.TeamsManager;
import dev.lyy.friendlycore.modules.teams.model.Team;
import dev.lyy.friendlycore.modules.teams.model.TeamColor;
import dev.lyy.friendlycore.modules.teams.model.TeamNameFormat;
import dev.lyy.friendlycore.modules.teams.model.TeamMember;
import dev.lyy.friendlycore.modules.teams.model.TeamPlayerStats;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;

/**
 * PlaceholderAPI expansion providing placeholders for team metadata, member stats,
 * and leaderboard rankings under the {@code %zelteams_...%} identifier.
 */
public class TeamsPlaceholderExpansion extends PlaceholderExpansion {

    private static final LegacyComponentSerializer DISPLAY_SERIALIZER = LegacyComponentSerializer.builder()
            .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    private static final TextColor DEFAULT_NAME_COLOR = TextColor.fromHexString(Team.DEFAULT_COLOR);

    private final FriendlyCore plugin;
    private final TeamsManager teamsManager;
    private final TeamsLeaderboardManager leaderboardManager;

    public TeamsPlaceholderExpansion(FriendlyCore plugin, TeamsManager teamsManager, TeamsLeaderboardManager leaderboardManager) {
        this.plugin = plugin;
        this.teamsManager = teamsManager;
        this.leaderboardManager = leaderboardManager;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "zelteams";
    }

    @Override
    public @NotNull String getAuthor() {
        return "FriendlyCore";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        return resolvePlaceholder(player, params);
    }

    public @Nullable String resolvePlaceholder(OfflinePlayer player, @NotNull String params) {
        String p = params.toLowerCase();
        if (p.startsWith("leaderboard_")) {
            int lastUnderscore = p.lastIndexOf('_');
            if (lastUnderscore > "leaderboard_".length() && lastUnderscore < p.length() - 1) {
                String type = p.substring("leaderboard_".length(), lastUnderscore);
                String posStr = p.substring(lastUnderscore + 1);
                try {
                    int pos = Integer.parseInt(posStr);
                    Team team;
                    if (type.startsWith("balance") || type.startsWith("bank")) {
                        team = leaderboardManager.getTeamAtRank(leaderboardManager.getTopByBalance(), pos);
                    } else if (type.startsWith("kill")) {
                        team = leaderboardManager.getTeamAtRank(leaderboardManager.getTopByKills(), pos);
                    } else if (type.startsWith("death")) {
                        team = leaderboardManager.getTeamAtRank(leaderboardManager.getTopByDeaths(), pos);
                    } else if (type.startsWith("member")) {
                        team = leaderboardManager.getTeamAtRank(leaderboardManager.getTopByMembers(), pos);
                    } else {
                        team = leaderboardManager.getTeamAtRank(leaderboardManager.getTopByScore(), pos);
                    }
                    if (team == null) return "---";
                    return switch (type) {
                        case "score" -> String.valueOf(team.getScore());
                        case "tag" -> team.getTag();
                        case "name" -> team.getName();
                        case "balance", "bank" -> String.format(Locale.US, "%,.2f", team.getBankBalance());
                        case "balance_short", "bank_short" -> plugin.getNumberFormatter().formatShort(team.getBankBalance());
                        case "balance_formatted", "bank_formatted" -> plugin.getNumberFormatter().format(team.getBankBalance());
                        case "balance_exact", "bank_exact" -> plugin.getNumberFormatter().formatExact(team.getBankBalance());
                        case "balance_raw", "bank_raw" -> String.valueOf(team.getBankBalance());
                        case "members", "members_count" -> String.valueOf(team.getMemberCount());
                        case "kills" -> String.valueOf(leaderboardManager.getTeamKills(team));
                        case "deaths" -> String.valueOf(leaderboardManager.getTeamDeaths(team));
                        default -> "---";
                    };
                } catch (Exception ignored) {}
            }
            return "---";
        }

        if (player == null) return "";

        Optional<Team> teamOpt = teamsManager.getTeamByPlayer(player.getUniqueId());
        boolean hasTeam = teamOpt.isPresent();

        TeamPlayerStats playerStats = teamsManager.getOrCreatePlayerStats(player.getUniqueId(), player.getName());

        switch (p) {
            case "has_team", "hasteam" -> {
                return String.valueOf(hasTeam);
            }
            case "team_name", "name" -> {
                return hasTeam ? teamOpt.get().getName() : "";
            }
            case "team_name_colored", "name_colored" -> {
                if (!hasTeam) return "";
                Team t = teamOpt.get();
                TeamColor c = teamsManager.getColor(t.getColorKey());
                String plain = TeamNameFormat.plain(t.getName());
                String defaultDisplay = DISPLAY_SERIALIZER.serialize(Component.text(plain, DEFAULT_NAME_COLOR)) + "§r";
                boolean formatted = !plain.equals(t.getName());
                if (formatted) {
                    return DISPLAY_SERIALIZER.serialize(TeamNameFormat.parse(t.getName()).colorIfAbsent(DEFAULT_NAME_COLOR)) + "§r";
                }
                if (c == null) return defaultDisplay;
                return DISPLAY_SERIALIZER.serialize(MiniMessage.miniMessage().deserialize(
                        TextService.convertLegacyToMiniMessage(c.getColorCode()) + "<team>",
                        Placeholder.unparsed("team", t.getName()))) + "§r";
            }
            case "team_tag", "tag" -> {
                return hasTeam ? teamOpt.get().getTag() : "";
            }
            case "team_tag_colored", "tag_colored" -> {
                if (!hasTeam) return "";
                dev.lyy.friendlycore.modules.teams.model.Team t = teamOpt.get();
                TeamColor c = teamsManager.getColor(t.getColorKey());
                return (c != null ? c.getColorCode() : "") + t.getTag();
            }
            case "team_tag_bracketed", "tag_bracketed" -> {
                if (!hasTeam) return "";
                dev.lyy.friendlycore.modules.teams.model.Team t = teamOpt.get();
                TeamColor c = teamsManager.getColor(t.getColorKey());
                String code = c != null ? c.getColorCode() : "&f";
                return "&8[" + code + t.getTag() + "&8] ";
            }
            case "team_color", "color" -> {
                return hasTeam ? teamOpt.get().getColorKey() : "";
            }
            case "team_color_code", "color_code" -> {
                if (!hasTeam) return "";
                dev.lyy.friendlycore.modules.teams.model.Team t = teamOpt.get();
                TeamColor c = teamsManager.getColor(t.getColorKey());
                return c != null ? c.getColorCode() : "&f";
            }
            case "team_rank", "rank" -> {
                return hasTeam ? String.valueOf(teamOpt.get().getRank()) : "0";
            }
            case "team_score", "score" -> {
                return hasTeam ? String.valueOf(teamOpt.get().getScore()) : "0.0";
            }
            case "team_balance", "balance", "team_bank", "bank" -> {
                return hasTeam ? String.format(Locale.US, "%,.2f", teamOpt.get().getBankBalance()) : "0.00";
            }
            case "team_balance_short", "balance_short", "team_bank_short", "bank_short" -> {
                return hasTeam ? plugin.getNumberFormatter().formatShort(teamOpt.get().getBankBalance()) : "0";
            }
            case "team_balance_formatted", "balance_formatted", "team_bank_formatted", "bank_formatted" -> {
                return hasTeam ? plugin.getNumberFormatter().format(teamOpt.get().getBankBalance()) : "0";
            }
            case "team_balance_exact", "balance_exact", "team_bank_exact", "bank_exact" -> {
                return hasTeam ? plugin.getNumberFormatter().formatExact(teamOpt.get().getBankBalance()) : "0.00";
            }
            case "team_balance_raw", "balance_raw", "team_bank_raw", "bank_raw" -> {
                return hasTeam ? String.valueOf(teamOpt.get().getBankBalance()) : "0.0";
            }
            case "team_members_count", "team_members", "members_count", "members" -> {
                return hasTeam ? String.valueOf(teamOpt.get().getMemberCount()) : "0";
            }
            case "team_allies_count", "team_allies", "allies_count", "allies" -> {
                return hasTeam ? String.valueOf(teamOpt.get().getAllies().size()) : "0";
            }
            case "player_role", "role" -> {
                if (hasTeam) {
                    TeamMember m = teamOpt.get().getMember(player.getUniqueId());
                    return m != null && m.getRole() != null ? m.getRole().getName() : "";
                }
                return "";
            }
            case "player_kills" -> {
                return String.valueOf(playerStats.getKills());
            }
            case "team_kills" -> {
                if (hasTeam) {
                    return String.valueOf(leaderboardManager.getTeamKills(teamOpt.get()));
                }
                return String.valueOf(playerStats.getKills());
            }
            case "player_deaths" -> {
                return String.valueOf(playerStats.getDeaths());
            }
            case "team_deaths" -> {
                if (hasTeam) {
                    return String.valueOf(leaderboardManager.getTeamDeaths(teamOpt.get()));
                }
                return String.valueOf(playerStats.getDeaths());
            }
            case "player_kdr" -> {
                return String.format(Locale.US, "%.2f", playerStats.getKdr());
            }
            case "team_kdr" -> {
                if (hasTeam) {
                    int tk = leaderboardManager.getTeamKills(teamOpt.get());
                    int td = leaderboardManager.getTeamDeaths(teamOpt.get());
                    double kdr = td == 0 ? tk : (double) tk / td;
                    return String.format(Locale.US, "%.2f", kdr);
                }
                return String.format(Locale.US, "%.2f", playerStats.getKdr());
            }
            case "player_mob_kills", "team_mob_kills" -> {
                return String.valueOf(playerStats.getMobKills());
            }
            case "player_blocks_broken", "team_blocks_broken" -> {
                return String.valueOf(playerStats.getBlocksBroken());
            }
            case "player_blocks_placed", "team_blocks_placed" -> {
                return String.valueOf(playerStats.getBlocksPlaced());
            }
            case "player_level", "team_player_level" -> {
                return String.valueOf(playerStats.getLevel());
            }
        }

        return null;
    }
}
