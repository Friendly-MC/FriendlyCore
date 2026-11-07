package dev.lyy.friendlycore;

import dev.lyy.friendlycore.common.ConfigService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive Audit Test for FriendlyCore Modular Configuration Architecture.
 */
public class ConfigAuditTest {

    private static final String[] ALL_RESOURCE_FILES = {
            "config.yml",
            "messages.yml",
            "plugin.yml",
            // Economy
            "modules/economy/config.yml",
            "modules/economy/messages.yml",
            // Homes
            "modules/homes/config.yml",
            "modules/homes/messages.yml",
            "modules/homes/sounds.yml",
            "modules/homes/inventories/home.yml",
            "modules/homes/inventories/confirm.yml",
            // JoinQuit
            "modules/joinquit/config.yml",
            "modules/joinquit/messages.yml",
            // Kits
            "modules/kits/config.yml",
            "modules/kits/messages.yml",
            "modules/kits/inventories/kits.yml",
            // NightVision
            "modules/nightvision/config.yml",
            "modules/nightvision/messages.yml",
            // PvP
            "modules/pvp/config.yml",
            "modules/pvp/messages.yml",
            // RTP
            "modules/rtp/config.yml",
            "modules/rtp/messages.yml",
            // Spawn
            "modules/spawn/config.yml",
            "modules/spawn/messages.yml",
            // Teams
            "modules/teams/config.yml",
            "modules/teams/messages.yml",
            "modules/teams/inventories/allies-gui.yml",
            "modules/teams/inventories/bank-gui.yml",
            "modules/teams/inventories/color-gui.yml",
            "modules/teams/inventories/confirm-gui.yml",
            "modules/teams/inventories/enderchest-gui.yml",
            "modules/teams/inventories/home-gui.yml",
            "modules/teams/inventories/leaderboard-gui.yml",
            "modules/teams/inventories/mailbox-gui.yml",
            "modules/teams/inventories/members-gui.yml",
            "modules/teams/inventories/playerstats-gui.yml",
            "modules/teams/inventories/settings-gui.yml",
            "modules/teams/inventories/stats-gui.yml",
            "modules/teams/inventories/team-gui.yml",
            // Tools
            "modules/tools/config.yml",
            "modules/tools/messages.yml",
            // TPA
            "modules/tpa/config.yml",
            "modules/tpa/messages.yml",
            "modules/tpa/inventories/tpa-admin.yml",
            "modules/tpa/inventories/tpa-here-inventory.yml",
            "modules/tpa/inventories/tpa-inventory.yml",
            "modules/tpa/inventories/tpa-safe-teleport-settings.yml",
            "modules/tpa/inventories/tpa-settings.yml",
            // Essentials
            "modules/essentials/config.yml",
            "modules/essentials/messages.yml",
            // Pets
            "modules/pets/config.yml",
            "modules/pets/messages.yml",
            "modules/pets/inventories/pets.yml",
            "modules/pets/inventories/confirm.yml",
            "modules/pets/pets/_example.yml",
            "modules/pets/pets/king.yml",
            "modules/pets/pets/mvp.yml",
            "modules/pets/pets/mvpp.yml",
            "modules/pets/pets/vip.yml",
            // Chat
            "modules/chat/chat-filter.yml",
            "modules/chat/config.yml",
            "modules/chat/filter-rules.yml",
            "modules/chat/filter-whitelist.yml",
            "modules/chat/leetmap.yml",
            "modules/chat/messages.yml",
            "modules/chat/placeholders.yml",
            "modules/chat/renders-gui.yml"
    };

    private InputStream getResourceStream(String path) {
        InputStream stream = getClass().getClassLoader().getResourceAsStream(path);
        assertNotNull(stream, "Resource file not found on classpath: " + path);
        return stream;
    }

    private YamlConfiguration loadYaml(String path) {
        try (InputStream stream = getResourceStream(path);
             InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (Exception e) {
            fail("Failed to load YAML configuration: " + path + " -> " + e.getMessage());
            return null;
        }
    }

    @Nested
    @DisplayName("1. YAML Syntax & Modular Structure Audit")
    class YamlSyntaxAudit {

        @Test
        @DisplayName("All modular resource files must be valid SnakeYAML and Bukkit YamlConfiguration")
        void testAllYamlFilesSyntax() {
            Yaml snakeYaml = new Yaml();
            for (String file : ALL_RESOURCE_FILES) {
                // 1. Test standard SnakeYAML parse
                try (InputStream stream = getResourceStream(file);
                     InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    Object parsed = snakeYaml.load(reader);
                    assertNotNull(parsed, "Parsed object is null for " + file);
                } catch (Exception e) {
                    fail("SnakeYAML syntax parsing error in file: " + file + " -> " + e.getMessage());
                }

                // 2. Test Bukkit YamlConfiguration parse
                YamlConfiguration bukkitConfig = loadYaml(file);
                assertNotNull(bukkitConfig, "Bukkit YamlConfiguration is null for " + file);
                assertFalse(bukkitConfig.getKeys(false).isEmpty(), "File appears empty or unparsed: " + file);
            }
        }

        @Test
        @DisplayName("Verify root config.yml contains global infrastructure (MySQL, Redis, Modules, Optimizations)")
        void testConfigYmlStructure() {
            YamlConfiguration config = loadYaml("config.yml");
            assertTrue(config.contains("debug"));
            assertTrue(config.contains("prefix"));
            assertTrue(config.contains("modules.economy"));
            assertTrue(config.contains("modules.homes"));
            assertTrue(config.contains("modules.kits"));
            assertTrue(config.contains("modules.tools"));
            assertTrue(config.contains("modules.pvp"));
            assertTrue(config.contains("modules.spawn"));
            assertTrue(config.contains("modules.tpa"));
            assertTrue(config.contains("modules.rtp"));
            assertTrue(config.contains("modules.nightvision"));
            assertTrue(config.contains("modules.teams"));
            assertFalse(config.getBoolean("modules.auctions"),
                    "Experimental Auctions module must be disabled by default");
            assertFalse(config.getBoolean("modules.orders"),
                    "Experimental Orders module must be disabled by default");
            assertTrue(config.contains("database.type"));
            assertTrue(config.contains("database.mysql.host"));
            assertTrue(config.contains("database.mysql.pool.maximum-pool-size"));
            assertTrue(config.contains("redis.enabled"));
            assertTrue(config.contains("redis.host"));
            assertTrue(config.contains("redis.security.shared-secret"));
            assertEquals(30, config.getInt("redis.security.max-clock-skew-seconds"));
            assertEquals(262144, config.getInt("redis.security.max-message-bytes"));
            assertTrue(config.contains("redis.channels.sync"));
            assertTrue(config.contains("optimization.save-interval-seconds"));
            assertTrue(config.contains("optimization.baltop-cache-seconds"));
        }

        @Test
        @DisplayName("Verify modular kits/config.yml contains settings and kit definitions")
        void testKitsConfigStructure() {
            YamlConfiguration kitsConfig = loadYaml("modules/kits/config.yml");
            assertTrue(kitsConfig.contains("settings.drop-items-if-full-inventory"));
            assertTrue(kitsConfig.contains("settings.close-inventory-on-claim"));
            assertTrue(kitsConfig.contains("settings.kit-preview"));
            assertTrue(kitsConfig.contains("settings.first-join-kit"));
            assertTrue(kitsConfig.contains("kits.starter"));
            assertTrue(kitsConfig.contains("kits.pvp"));
        }

        @Test
        @DisplayName("Verify plugin.yml structure, commands, and permissions")
        void testPluginYmlStructure() {
            YamlConfiguration plugin = loadYaml("plugin.yml");
            assertEquals("FriendlyCore", plugin.getString("name"));
            assertNotNull(plugin.getConfigurationSection("commands"));
            assertNotNull(plugin.getConfigurationSection("permissions"));
            assertTrue(plugin.getBoolean("folia-supported"));
            assertTrue(plugin.getStringList("softdepend").contains("floodgate"));
            assertTrue(plugin.getStringList("softdepend").contains("Geyser-Spigot"));
            assertTrue(plugin.getBoolean("permissions.friendlycore.use.default"));
            assertEquals("op", plugin.getString("permissions.friendlycore.spawn.joinbypass.default"));
            assertTrue(plugin.getBoolean("permissions.friendlycore.admin.children.friendlycore.spawn.joinbypass"));
            assertFalse(plugin.getStringList("commands.home.aliases").contains("homes"));
            assertFalse(plugin.getStringList("commands.tec.aliases").contains("tvault"));
            assertDoesNotThrow(() -> Class.forName("com.mysql.cj.jdbc.Driver"));

            ConfigurationSection cmds = plugin.getConfigurationSection("commands");
            assertTrue(cmds.contains("friendlycore"));
            assertTrue(cmds.contains("bal"));
            assertTrue(cmds.contains("baltop"));
            assertTrue(cmds.contains("pay"));
            assertTrue(cmds.contains("paytoggle"));
            assertTrue(cmds.contains("payhistory"));
            assertTrue(cmds.contains("economy"));
            assertTrue(cmds.contains("payreload"));
            assertTrue(cmds.contains("tools"));
            assertTrue(cmds.contains("kit"));
            assertTrue(cmds.contains("setspawn"));
            assertTrue(cmds.contains("spawn"));
            assertTrue(cmds.contains("telegram"));
            assertTrue(cmds.contains("tpa"));
            assertTrue(cmds.contains("tpahere"));
            assertTrue(cmds.contains("tpaccept"));
            assertTrue(cmds.contains("tpdeny"));
            assertTrue(cmds.contains("tpacancel"));
            assertTrue(cmds.contains("tpaall"));
            assertTrue(cmds.contains("tphereall"));
            assertTrue(cmds.contains("tpatoggle"));
            assertTrue(cmds.contains("tpaheretoggle"));
            assertTrue(cmds.contains("tpauto"));
            assertTrue(cmds.contains("tpaguitoggle"));
            assertTrue(cmds.contains("tpasetting"));
            assertTrue(cmds.contains("tpaadmin"));
            assertTrue(cmds.contains("rtp"));
            assertTrue(cmds.contains("nv"));
            assertTrue(cmds.contains("joinquit"));
            assertTrue(cmds.contains("togglejoinquit"));
        }
    }

    @Nested
    @DisplayName("2. ConfigService & MergedMessageConfiguration Audit")
    class ConfigServiceAudit {

        private ConfigService.MergedMessageConfiguration createCompositeMessages() {
            String[] messageFiles = {
                    "messages.yml",
                    "modules/economy/messages.yml",
                    "modules/homes/messages.yml",
                    "modules/joinquit/messages.yml",
                    "modules/kits/messages.yml",
                    "modules/nightvision/messages.yml",
                    "modules/pvp/messages.yml",
                    "modules/rtp/messages.yml",
                    "modules/spawn/messages.yml",
                    "modules/teams/messages.yml",
                    "modules/tools/messages.yml",
                    "modules/tpa/messages.yml",
                    "modules/essentials/messages.yml",
                    "modules/pets/messages.yml",
                    "modules/chat/messages.yml"
            };

            FileConfiguration[] sources = new FileConfiguration[messageFiles.length];
            for (int i = 0; i < messageFiles.length; i++) {
                sources[i] = loadYaml(messageFiles[i]);
            }
            return new ConfigService.MergedMessageConfiguration(sources);
        }

        @Test
        @DisplayName("Verify composite messages direct lookup (without module prefix)")
        void testDirectMessageLookup() {
            ConfigService.MergedMessageConfiguration composite = createCompositeMessages();

            // From root messages.yml
            assertNotNull(composite.getString("no-permission"));
            assertNotNull(composite.getString("player-not-found"));
            assertNotNull(composite.getString("invalid-number"));
            assertNotNull(composite.getString("command-reloaded"));

            // From joinquit
            assertNotNull(composite.getString("joinquit.reloaded"));

            // From economy
            assertNotNull(composite.getString("balance-self"));
            assertNotNull(composite.getString("cant-pay-yourself"));
            assertNotNull(composite.getString("payment-sent"));
            assertNotNull(composite.getString("actionbar-sent"));

            // From kits
            assertNotNull(composite.getString("kit-created"));
            assertNotNull(composite.getString("kit-cooldown"));
            assertNotNull(composite.getString("ready"));

            // From nightvision
            assertNotNull(composite.getString("toggled-on"));
            assertNotNull(composite.getString("actionbar-on"));

            // From rtp
            assertNotNull(composite.getString("actionbar-searching"));
            assertNotNull(composite.getString("teleporting"));

            // From spawn
            assertNotNull(composite.getString("spawn-set"));
            assertNotNull(composite.getString("teleport-success"));

            // From tools
            assertNotNull(composite.getString("given-drill"));
            assertNotNull(composite.getString("given-chopper"));

            // From teams
            assertNotNull(composite.getString("info-team-disbanded"));
        }

        @Test
        @DisplayName("Verify composite messages prefixed lookup (with module prefix)")
        void testPrefixedMessageLookup() {
            ConfigService.MergedMessageConfiguration composite = createCompositeMessages();

            // Economy prefix
            assertEquals(composite.getString("balance-self"), composite.getString("economy.balance-self"));
            assertEquals(composite.getString("payment-sent"), composite.getString("economy.payment-sent"));
            assertEquals(composite.getString("admin-give"), composite.getString("economy.admin-give"));

            // Kits prefix
            assertEquals(composite.getString("kit-created"), composite.getString("kits.kit-created"));
            assertEquals(composite.getString("kit-cooldown"), composite.getString("kits.kit-cooldown"));
            assertEquals(composite.getString("ready"), composite.getString("kits.ready"));

            // NightVision prefix
            assertEquals(composite.getString("toggled-on"), composite.getString("nightvision.toggled-on"));
            assertEquals(composite.getString("status-self"), composite.getString("nightvision.status-self"));

            // RTP prefix
            assertEquals(composite.getString("actionbar-searching"), composite.getString("rtp.actionbar-searching"));
            assertEquals(composite.getString("teleporting"), composite.getString("rtp.teleporting"));
            assertEquals(composite.getString("not-safe"), composite.getString("rtp.not-safe"));

            // Spawn prefix
            assertEquals(composite.getString("spawn-set"), composite.getString("spawn.spawn-set"));
            assertEquals(composite.getString("teleport-success"), composite.getString("spawn.teleport-success"));
            assertNotNull(composite.getString("spawn.teleporting"));
            assertNotNull(composite.getString("rtp.teleporting"));
            assertNotEquals(composite.getString("spawn.teleporting"), composite.getString("rtp.teleporting"));

            // Tools prefix
            assertEquals(composite.getString("given-drill"), composite.getString("tools.given-drill"));
            assertEquals(composite.getString("given-chopper"), composite.getString("tools.given-chopper"));
        }

        @Test
        @DisplayName("Verify nested sections in composite messages")
        void testNestedSectionsInMessages() {
            ConfigService.MergedMessageConfiguration composite = createCompositeMessages();

            // RTP Titles nested section
            assertNotNull(composite.getString("titles.searching.title"));
            assertNotNull(composite.getString("rtp.titles.searching.title"));
            assertEquals(composite.getString("titles.searching.title"), composite.getString("rtp.titles.searching.title"));

            assertNotNull(composite.getString("titles.warmup.title"));
            assertNotNull(composite.getString("rtp.titles.warmup.title"));

            // TPA complex sound / message structure
            assertTrue(composite.contains("receive-request"));
            assertTrue(composite.contains("receive-request.message"));
            assertTrue(composite.contains("receive-request.sound.key"));
            assertTrue(composite.contains("tpa.receive-request.message"));
            assertTrue(composite.contains("tpa.receive-request.sound.key"));

            assertEquals("entity.experience_orb.pickup", composite.getString("receive-request.sound.key"));
            assertEquals("entity.experience_orb.pickup", composite.getString("tpa.receive-request.sound.key"));
        }

        @Test
        @DisplayName("Verify getStringList in composite messages")
        void testGetStringListInCompositeMessages() {
            ConfigService.MergedMessageConfiguration composite = createCompositeMessages();

            List<String> nvHelpDirect = composite.getStringList("help-lines");
            List<String> nvHelpPrefixed = composite.getStringList("nightvision.help-lines");
            assertFalse(nvHelpDirect.isEmpty());
            assertEquals(nvHelpDirect, nvHelpPrefixed);

            List<String> tgMessageDirect = composite.getStringList("telegram.message");
            List<String> tgMessagePrefixed = composite.getStringList("spawn.telegram.message");
            assertFalse(tgMessageDirect.isEmpty());
        }

        @Test
        @DisplayName("Verify getConfigurationSection in composite messages")
        void testGetConfigurationSection() {
            ConfigService.MergedMessageConfiguration composite = createCompositeMessages();

            ConfigurationSection sec1 = composite.getConfigurationSection("receive-request");
            assertNotNull(sec1);
            assertTrue(sec1.contains("message"));

            ConfigurationSection sec2 = composite.getConfigurationSection("tpa.receive-request");
            assertNotNull(sec2);
            assertTrue(sec2.contains("message"));
        }
    }

    @Nested
    @DisplayName("3. Codebase Reference Verification")
    class CodebaseReferenceAudit {

        @Test
        @DisplayName("Scan all Java files in src/main/java and ensure all queried configuration keys exist in resource files")
        void testAllQueriedKeysExistInResources() throws Exception {
            Path srcDir = Paths.get("src/main/java");
            assertTrue(Files.exists(srcDir), "Source directory src/main/java does not exist");

            // Load all YAML resource configurations
            Map<String, YamlConfiguration> configs = new HashMap<>();
            for (String file : ALL_RESOURCE_FILES) {
                configs.put(file, loadYaml(file));
            }

            // Create composite message config
            FileConfiguration[] msgSources = new FileConfiguration[]{
                    configs.get("messages.yml"),
                    configs.get("modules/economy/messages.yml"),
                    configs.get("modules/homes/messages.yml"),
                    configs.get("modules/joinquit/messages.yml"),
                    configs.get("modules/kits/messages.yml"),
                    configs.get("modules/nightvision/messages.yml"),
                    configs.get("modules/pvp/messages.yml"),
                    configs.get("modules/rtp/messages.yml"),
                    configs.get("modules/spawn/messages.yml"),
                    configs.get("modules/teams/messages.yml"),
                    configs.get("modules/tools/messages.yml"),
                    configs.get("modules/tpa/messages.yml"),
                    configs.get("modules/essentials/messages.yml"),
                    configs.get("modules/pets/messages.yml"),
                    configs.get("modules/chat/messages.yml")
            };
            ConfigService.MergedMessageConfiguration compositeMessages = new ConfigService.MergedMessageConfiguration(msgSources);

            // Patterns to detect config lookups in Java code
            Pattern getMessagesPattern = Pattern.compile("getMessages\\(\\)\\.(?:getString|getStringList|getBoolean|getInt|getDouble|getLong|getConfigurationSection|get|contains)\\s*\\(\\s*\"([^\"]+)\"");
            Pattern getMainConfigPattern = Pattern.compile("getMainConfig\\(\\)\\.(?:getString|getStringList|getBoolean|getInt|getDouble|getLong|getConfigurationSection|get|contains)\\s*\\(\\s*\"([^\"]+)\"");
            Pattern getEconomyConfigPattern = Pattern.compile("getEconomyConfig\\(\\)\\.(?:getString|getStringList|getBoolean|getInt|getDouble|getLong|getConfigurationSection|get|contains)\\s*\\(\\s*\"([^\"]+)\"");
            Pattern getHomesConfigPattern = Pattern.compile("getHomesConfig\\(\\)\\.(?:getString|getStringList|getBoolean|getInt|getDouble|getLong|getConfigurationSection|get|contains)\\s*\\(\\s*\"([^\"]+)\"");
            Pattern getJoinQuitConfigPattern = Pattern.compile("getJoinQuitConfig\\(\\)\\.(?:getString|getStringList|getBoolean|getInt|getDouble|getLong|getConfigurationSection|get|contains)\\s*\\(\\s*\"([^\"]+)\"");
            Pattern getKitsConfigPattern = Pattern.compile("getKitsConfig\\(\\)\\.(?:getString|getStringList|getBoolean|getInt|getDouble|getLong|getConfigurationSection|get|contains)\\s*\\(\\s*\"([^\"]+)\"");
            Pattern getNightVisionConfigPattern = Pattern.compile("getNightVisionConfig\\(\\)\\.(?:getString|getStringList|getBoolean|getInt|getDouble|getLong|getConfigurationSection|get|contains)\\s*\\(\\s*\"([^\"]+)\"");
            Pattern getPvpConfigPattern = Pattern.compile("getPvpConfig\\(\\)\\.(?:getString|getStringList|getBoolean|getInt|getDouble|getLong|getConfigurationSection|get|contains)\\s*\\(\\s*\"([^\"]+)\"");
            Pattern getRedisConfigPattern = Pattern.compile("getRedisConfig\\(\\)\\.(?:getString|getStringList|getBoolean|getInt|getDouble|getLong|getConfigurationSection|get|contains)\\s*\\(\\s*\"([^\"]+)\"");
            Pattern getRtpConfigPattern = Pattern.compile("getRtpConfig\\(\\)\\.(?:getString|getStringList|getBoolean|getInt|getDouble|getLong|getConfigurationSection|get|contains)\\s*\\(\\s*\"([^\"]+)\"");
            Pattern getSpawnConfigPattern = Pattern.compile("getSpawnConfig\\(\\)\\.(?:getString|getStringList|getBoolean|getInt|getDouble|getLong|getConfigurationSection|get|contains)\\s*\\(\\s*\"([^\"]+)\"");
            Pattern getToolsConfigPattern = Pattern.compile("getToolsConfig\\(\\)\\.(?:getString|getStringList|getBoolean|getInt|getDouble|getLong|getConfigurationSection|get|contains)\\s*\\(\\s*\"([^\"]+)\"");
            Pattern getTpaConfigPattern = Pattern.compile("getTpaConfig\\(\\)\\.(?:getString|getStringList|getBoolean|getInt|getDouble|getLong|getConfigurationSection|get|contains)\\s*\\(\\s*\"([^\"]+)\"");

            List<String> missingKeys = new ArrayList<>();

            try (Stream<Path> stream = Files.walk(srcDir)) {
                List<Path> javaFiles = stream.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());

                for (Path path : javaFiles) {
                    String content = Files.readString(path, StandardCharsets.UTF_8);
                    String fileName = path.getFileName().toString();

                    // Check getMessages() keys
                    Matcher msgMatcher = getMessagesPattern.matcher(content);
                    while (msgMatcher.find()) {
                        String key = msgMatcher.group(1);
                        if (!compositeMessages.contains(key)) {
                            missingKeys.add("File [" + fileName + "] getMessages() key not found: '" + key + "'");
                        }
                    }

                    // Check getMainConfig() keys
                    Matcher mainMatcher = getMainConfigPattern.matcher(content);
                    while (mainMatcher.find()) {
                        String key = mainMatcher.group(1);
                        if (!configs.get("config.yml").contains(key)) {
                            missingKeys.add("File [" + fileName + "] getMainConfig() key not found: '" + key + "'");
                        }
                    }

                    // Check getEconomyConfig() keys
                    Matcher ecoMatcher = getEconomyConfigPattern.matcher(content);
                    while (ecoMatcher.find()) {
                        String key = ecoMatcher.group(1);
                        if (!configs.get("modules/economy/config.yml").contains(key)) {
                            missingKeys.add("File [" + fileName + "] getEconomyConfig() key not found: '" + key + "'");
                        }
                    }

                    // Check getHomesConfig() keys
                    Matcher homesMatcher = getHomesConfigPattern.matcher(content);
                    while (homesMatcher.find()) {
                        String key = homesMatcher.group(1);
                        if (!configs.get("modules/homes/config.yml").contains(key)) {
                            missingKeys.add("File [" + fileName + "] getHomesConfig() key not found: '" + key + "'");
                        }
                    }

                    // Check getJoinQuitConfig() keys
                    Matcher jqMatcher = getJoinQuitConfigPattern.matcher(content);
                    while (jqMatcher.find()) {
                        String key = jqMatcher.group(1);
                        if (!configs.get("modules/joinquit/config.yml").contains(key)) {
                            missingKeys.add("File [" + fileName + "] getJoinQuitConfig() key not found: '" + key + "'");
                        }
                    }

                    // Check getKitsConfig() keys
                    Matcher kitsMatcher = getKitsConfigPattern.matcher(content);
                    while (kitsMatcher.find()) {
                        String key = kitsMatcher.group(1);
                        if (!configs.get("modules/kits/config.yml").contains(key)) {
                            missingKeys.add("File [" + fileName + "] getKitsConfig() key not found: '" + key + "'");
                        }
                    }

                    // Check getNightVisionConfig() keys
                    Matcher nvMatcher = getNightVisionConfigPattern.matcher(content);
                    while (nvMatcher.find()) {
                        String key = nvMatcher.group(1);
                        if (!configs.get("modules/nightvision/config.yml").contains(key)) {
                            missingKeys.add("File [" + fileName + "] getNightVisionConfig() key not found: '" + key + "'");
                        }
                    }

                    // Check getPvpConfig() keys
                    Matcher pvpMatcher = getPvpConfigPattern.matcher(content);
                    while (pvpMatcher.find()) {
                        String key = pvpMatcher.group(1);
                        if (!configs.get("modules/pvp/config.yml").contains(key)) {
                            missingKeys.add("File [" + fileName + "] getPvpConfig() key not found: '" + key + "'");
                        }
                    }

                    // Check getRedisConfig() keys
                    Matcher redisMatcher = getRedisConfigPattern.matcher(content);
                    while (redisMatcher.find()) {
                        String key = redisMatcher.group(1);
                        if (!configs.get("config.yml").contains(key)) {
                            missingKeys.add("File [" + fileName + "] getRedisConfig() key not found: '" + key + "'");
                        }
                    }

                    // Check getRtpConfig() keys
                    Matcher rtpMatcher = getRtpConfigPattern.matcher(content);
                    while (rtpMatcher.find()) {
                        String key = rtpMatcher.group(1);
                        if (!configs.get("modules/rtp/config.yml").contains(key)) {
                            missingKeys.add("File [" + fileName + "] getRtpConfig() key not found: '" + key + "'");
                        }
                    }

                    // Check getSpawnConfig() keys
                    Matcher spawnMatcher = getSpawnConfigPattern.matcher(content);
                    while (spawnMatcher.find()) {
                        String key = spawnMatcher.group(1);
                        if (!configs.get("modules/spawn/config.yml").contains(key)) {
                            missingKeys.add("File [" + fileName + "] getSpawnConfig() key not found: '" + key + "'");
                        }
                    }

                    // Check getToolsConfig() keys
                    Matcher toolsMatcher = getToolsConfigPattern.matcher(content);
                    while (toolsMatcher.find()) {
                        String key = toolsMatcher.group(1);
                        if (!configs.get("modules/tools/config.yml").contains(key)) {
                            missingKeys.add("File [" + fileName + "] getToolsConfig() key not found: '" + key + "'");
                        }
                    }

                    // Check getTpaConfig() keys
                    Matcher tpaMatcher = getTpaConfigPattern.matcher(content);
                    while (tpaMatcher.find()) {
                        String key = tpaMatcher.group(1);
                        if (!configs.get("modules/tpa/config.yml").contains(key)) {
                            missingKeys.add("File [" + fileName + "] getTpaConfig() key not found: '" + key + "'");
                        }
                    }
                }
            }

            if (!missingKeys.isEmpty()) {
                String report = String.join("\n", missingKeys);
                fail("Found missing configuration keys:\n" + report);
            }
        }
    }
}
