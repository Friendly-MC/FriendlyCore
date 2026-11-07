package dev.lyy.friendlycore.modules.teams.manager;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.teams.model.Team;
import dev.lyy.friendlycore.modules.teams.model.TeamMember;
import dev.lyy.friendlycore.modules.teams.model.TeamPlayerStats;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Calculates dynamic team scores and maintains cached rankings across multiple leaderboards.
 */
public class TeamsLeaderboardManager {

    private final FriendlyCore plugin;
    private final TeamsManager teamsManager;

    private double deathFactor = 3.0;
    private double killFactor = 3.0;
    private double moneyFactor = 0.5;

    private final List<Team> topByScore = new CopyOnWriteArrayList<>();
    private final List<Team> topByBalance = new CopyOnWriteArrayList<>();
    private final List<Team> topByKills = new CopyOnWriteArrayList<>();
    private final List<Team> topByDeaths = new CopyOnWriteArrayList<>();
    private final List<Team> topByMembers = new CopyOnWriteArrayList<>();

    private Object recalculateTask;

    public TeamsLeaderboardManager(FriendlyCore plugin, TeamsManager teamsManager) {
        this.plugin = plugin;
        this.teamsManager = teamsManager;
    }

    public void initialize() {
        loadConfiguration();
        recalculate();
        // Recalculate all leaderboard categories asynchronously every 30 seconds
        this.recalculateTask = plugin.getScheduler().runAsyncTimer(this::recalculate, 600L, 600L);
    }

    public void shutdown() {
        if (recalculateTask != null) {
            plugin.getScheduler().cancel(recalculateTask);
            recalculateTask = null;
        }
        topByScore.clear();
        topByBalance.clear();
        topByKills.clear();
        topByDeaths.clear();
        topByMembers.clear();
    }

    public void loadConfiguration() {
        FileConfiguration config = plugin.getConfigService().getTeamsConfig();
        this.deathFactor = config.getDouble("Plugin-Settings.leaderboard.death-factor", 3.0);
        this.killFactor = config.getDouble("Plugin-Settings.leaderboard.kill-factor", 3.0);
        this.moneyFactor = config.getDouble("Plugin-Settings.leaderboard.money-factor", 0.5);
    }

    public void recalculate() {
        Collection<Team> allTeams = teamsManager.getAllTeams();
        if (allTeams.isEmpty()) return;

        List<Team> list = new ArrayList<>(allTeams);
        Map<UUID, Integer> teamKillsMap = new HashMap<>();
        Map<UUID, Integer> teamDeathsMap = new HashMap<>();

        // Aggregate member stats and compute overall team scores
        for (Team team : list) {
            int kills = 0;
            int deaths = 0;
            for (TeamMember m : team.getMemberList()) {
                TeamPlayerStats stats = teamsManager.getOrCreatePlayerStats(m.getUniqueId(), m.getUsername());
                kills += stats.getKills();
                deaths += stats.getDeaths();
            }
            teamKillsMap.put(team.getUniqueId(), kills);
            teamDeathsMap.put(team.getUniqueId(), deaths);

            double score = (kills * killFactor) - (deaths * deathFactor) + (team.getBankBalance() * moneyFactor);
            team.setScore(Math.round(score * 100.0) / 100.0);
        }

        list.sort((t1, t2) -> Double.compare(t2.getScore(), t1.getScore()));
        int rank = 1;
        for (Team t : list) {
            t.setRank(rank++);
        }
        topByScore.clear();
        topByScore.addAll(list);

        List<Team> balanceList = new ArrayList<>(allTeams);
        balanceList.sort((t1, t2) -> Double.compare(t2.getBankBalance(), t1.getBankBalance()));
        topByBalance.clear();
        topByBalance.addAll(balanceList);

        List<Team> killsList = new ArrayList<>(allTeams);
        killsList.sort((t1, t2) -> Integer.compare(teamKillsMap.getOrDefault(t2.getUniqueId(), 0), teamKillsMap.getOrDefault(t1.getUniqueId(), 0)));
        topByKills.clear();
        topByKills.addAll(killsList);

        List<Team> deathsList = new ArrayList<>(allTeams);
        deathsList.sort((t1, t2) -> Integer.compare(teamDeathsMap.getOrDefault(t2.getUniqueId(), 0), teamDeathsMap.getOrDefault(t1.getUniqueId(), 0)));
        topByDeaths.clear();
        topByDeaths.addAll(deathsList);

        List<Team> membersList = new ArrayList<>(allTeams);
        membersList.sort((t1, t2) -> Integer.compare(t2.getMemberCount(), t1.getMemberCount()));
        topByMembers.clear();
        topByMembers.addAll(membersList);
    }

    public List<Team> getTopByScore() {
        return topByScore;
    }

    public List<Team> getTopByBalance() {
        return topByBalance;
    }

    public List<Team> getTopByKills() {
        return topByKills;
    }

    public List<Team> getTopByDeaths() {
        return topByDeaths;
    }

    public List<Team> getTopByMembers() {
        return topByMembers;
    }

    public Team getTeamAtRank(int rank) {
        return getTeamAtRank(topByScore, rank);
    }

    public Team getTeamAtRank(List<Team> list, int rank) {
        if (list == null || rank <= 0 || rank > list.size()) return null;
        return list.get(rank - 1);
    }

    public int getTeamKills(Team team) {
        if (team == null) return 0;
        int kills = 0;
        for (TeamMember m : team.getMemberList()) {
            TeamPlayerStats stats = teamsManager.getOrCreatePlayerStats(m.getUniqueId(), m.getUsername());
            kills += stats.getKills();
        }
        return kills;
    }

    public int getTeamDeaths(Team team) {
        if (team == null) return 0;
        int deaths = 0;
        for (TeamMember m : team.getMemberList()) {
            TeamPlayerStats stats = teamsManager.getOrCreatePlayerStats(m.getUniqueId(), m.getUsername());
            deaths += stats.getDeaths();
        }
        return deaths;
    }
}
