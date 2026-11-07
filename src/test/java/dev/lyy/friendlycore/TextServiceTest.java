package dev.lyy.friendlycore;

import dev.lyy.friendlycore.common.TextService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TextServiceTest {

    @Test
    public void testLegacyColorCodesConversion() {
        String legacy = "&0&1&2&3&4&5&6&7&8&9&a&b&c&d&e&f";
        String converted = TextService.convertLegacyToMiniMessage(legacy);

        Assertions.assertTrue(converted.contains("<black>"));
        Assertions.assertTrue(converted.contains("<dark_blue>"));
        Assertions.assertTrue(converted.contains("<dark_green>"));
        Assertions.assertTrue(converted.contains("<dark_aqua>"));
        Assertions.assertTrue(converted.contains("<dark_red>"));
        Assertions.assertTrue(converted.contains("<dark_purple>"));
        Assertions.assertTrue(converted.contains("<gold>"));
        Assertions.assertTrue(converted.contains("<gray>"));
        Assertions.assertTrue(converted.contains("<dark_gray>"));
        Assertions.assertTrue(converted.contains("<blue>"));
        Assertions.assertTrue(converted.contains("<green>"));
        Assertions.assertTrue(converted.contains("<aqua>"));
        Assertions.assertTrue(converted.contains("<red>"));
        Assertions.assertTrue(converted.contains("<light_purple>"));
        Assertions.assertTrue(converted.contains("<yellow>"));
        Assertions.assertTrue(converted.contains("<white>"));
    }

    @Test
    public void testFormattingCodesConversion() {
        String legacy = "&lBold &oItalic &nUnderline &mStrike &kObf &rReset";
        String converted = TextService.convertLegacyToMiniMessage(legacy);

        Assertions.assertTrue(converted.contains("<bold>"));
        Assertions.assertTrue(converted.contains("<italic>"));
        Assertions.assertTrue(converted.contains("<underlined>"));
        Assertions.assertTrue(converted.contains("<strikethrough>"));
        Assertions.assertTrue(converted.contains("<obfuscated>"));
        Assertions.assertTrue(converted.contains("<!bold><!italic><!underlined><!strikethrough><!obfuscated><white>"));
    }

    @Test
    public void testSectionSymbolConversion() {
        String legacy = "§aGreen §lBold §cRed";
        String converted = TextService.convertLegacyToMiniMessage(legacy);

        Assertions.assertTrue(converted.contains("<green>"));
        Assertions.assertTrue(converted.contains("<bold>"));
        Assertions.assertTrue(converted.contains("<red>"));
    }

    @Test
    public void testHexColorConversion() {
        String hex = "&#FF5733Custom Hex &aText";
        String converted = TextService.convertLegacyToMiniMessage(hex);

        Assertions.assertTrue(converted.contains("<#FF5733>"));
        Assertions.assertTrue(converted.contains("<green>Text"));
    }

    @Test
    public void testRawHexAndSmallCapsFormat() {
        String itemName = "#a503fcғʀɪᴇɴᴅʟʏ ᴅʀɪʟʟ";
        String convertedItem = TextService.convertLegacyToMiniMessage(itemName);
        Assertions.assertEquals("<#a503fc>ғʀɪᴇɴᴅʟʏ ᴅʀɪʟʟ", convertedItem);

        String guiTitle = "&8✦ #a503fcғʀɪᴇɴᴅʟʏ ᴛᴏᴏʟs &8✦";
        String convertedTitle = TextService.convertLegacyToMiniMessage(guiTitle);
        Assertions.assertEquals("<dark_gray>✦ <#a503fc>ғʀɪᴇɴᴅʟʏ ᴛᴏᴏʟs <dark_gray>✦", convertedTitle);

        String lore = " #a503fc❙ &fᴛɪᴍᴇ ʟᴇғᴛ: #a503fc2031";
        String convertedLore = TextService.convertLegacyToMiniMessage(lore);
        Assertions.assertEquals(" <#a503fc>❙ <white>ᴛɪᴍᴇ ʟᴇғᴛ: <#a503fc>2031", convertedLore);

        String gradient = "<gradient:#00d2ff:#3a7bd5><bold>Gradient</bold></gradient>";
        String convertedGradient = TextService.convertLegacyToMiniMessage(gradient);
        Assertions.assertEquals("<gradient:#00d2ff:#3a7bd5><bold>Gradient</bold></gradient>", convertedGradient);
    }

    @Test
    public void testRawHexAfterSlashFraction() {
        String fractionLore = "#E63A350/#A1444464 #858585Delivered";
        String converted = TextService.convertLegacyToMiniMessage(fractionLore);
        Assertions.assertEquals("<#E63A35>0/<#A14444>64 <#858585>Delivered", converted);

        String paidLore = "#E63A35$0/#A14444$64,000 #858585Paid";
        String convertedPaid = TextService.convertLegacyToMiniMessage(paidLore);
        Assertions.assertEquals("<#E63A35>$0/<#A14444>$64,000 <#858585>Paid", convertedPaid);

        // Ensure MiniMessage closing tags are not corrupted
        String closingTag = "<#A14444>Text</#A14444>";
        String convertedClosing = TextService.convertLegacyToMiniMessage(closingTag);
        Assertions.assertEquals("<#A14444>Text</#A14444>", convertedClosing);
    }

    @Test
    public void testMiniMessagePassthrough() {
        String miniMessage = "<gradient:#00d2ff:#3a7bd5><bold>FriendlyCore</bold></gradient>";
        String converted = TextService.convertLegacyToMiniMessage(miniMessage);

        Assertions.assertEquals(miniMessage, converted);
    }

    @Test
    public void testCombinedLegacyAndMiniMessage() {
        String combined = "&cError: <yellow>Invalid number &a(Retry)";
        String converted = TextService.convertLegacyToMiniMessage(combined);

        Assertions.assertTrue(converted.contains("<red>Error: "));
        Assertions.assertTrue(converted.contains("<yellow>Invalid number "));
        Assertions.assertTrue(converted.contains("<green>(Retry)"));
    }

    @Test
    public void testPlaceholderInjectionProtection() {
        TextService service = new TextService();
        String template = "<gray>Hello, %player%!";
        String maliciousPlayer = "<click:run_command:'/op Attacker'><red>Hacked</click>";

        net.kyori.adventure.text.Component parsed = service.parse(template, java.util.Map.of("%player%", maliciousPlayer));
        Assertions.assertNotNull(parsed);
        Assertions.assertNull(parsed.clickEvent(), "Parsed component root should not contain click event from placeholder injection");
    }

    @Test
    public void testPlayerPlaceholderResolution() {
        TextService service = new TextService();
        String template = "<green>Welcome %player%! Check %PLAYER%'s balance {player_name}.";
        net.kyori.adventure.text.Component parsed = service.parse(template, java.util.Map.of("%player%", "Eangly"));

        String plainText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(parsed);
        Assertions.assertTrue(plainText.contains("Welcome Eangly! Check Eangly's balance Eangly."));
    }

    @Test
    public void testHexClosingTags() {
        TextService service = new TextService();
        String msg = "<gradient:#00e676:#00b0ff><bold>FriendlyCore</bold></gradient> <dark_gray>»</dark_gray> <gray>Configurations reloaded in <#00e676>12ms</#00e676>!</gray>";
        net.kyori.adventure.text.Component parsed = service.parse(msg);
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(parsed);
        System.out.println("Parsed plain: '" + plain + "'");
        Assertions.assertFalse(plain.contains("</#00e676>"), "Plain text should not contain raw closing tag");
        Assertions.assertTrue(plain.contains("12ms!"), "Plain text should contain 12ms!");
    }

    @Test
    public void testSilentJoinTagFormatting() {
        TextService service = new TextService();
        String rawTemplate = "<dark_gray>[<color:#00d2ff>Silent</color>]</dark_gray> <gray>%player% joined %server%.</gray>";
        net.kyori.adventure.text.Component parsed = service.parse(rawTemplate, java.util.Map.of("%player%", "NarutoLunakai", "%server%", "server-2"));
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(parsed);
        Assertions.assertEquals("[Silent] NarutoLunakai joined server-2.", plain);
        Assertions.assertFalse(plain.contains("</dark_gray>"), "Should not contain unparsed closing tags");
    }

    @Test
    public void testChatFormatRendering() {
        TextService service = new TextService();
        String chatFormat = "<gray>%luckperms_prefix%</gray><white>%player%</white><dark_gray> » </dark_gray><white>{message}</white>";

        // Test without player (null) - %luckperms_prefix% should be stripped
        String withoutPlayer = service.applyPlayerDefaults(null, chatFormat);
        Assertions.assertFalse(withoutPlayer.contains("%luckperms_prefix%"));

        net.kyori.adventure.text.Component msgComp = service.parse("hi");
        net.kyori.adventure.text.Component parsed = service.parseForPlayerWithComponent(null, chatFormat, java.util.Map.of("%player%", "NarutoLunakai"), "{message}", msgComp);
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(parsed);
        Assertions.assertEquals("NarutoLunakai » hi", plain);
        Assertions.assertFalse(plain.contains("</gray>"), "Should not contain trailing unparsed closing tags");
        Assertions.assertFalse(plain.contains("%luckperms_prefix%"), "Should not contain unparsed luckperms prefix placeholder");
    }

    @Test
    public void testRichStructuredChatFormatWithHoverAndAction() {
        TextService service = new TextService();
        String msg = "%luckperms_prefix% &f%customfontlegacy_style:1,{player_name}% &8➠ ";
        String hover = "&#25c8fa&lINFO<br>&#25c8fa&l▮&r &7ʟɪғᴇ &f%player_health_scale% &c❤<br>&#25c8fa&l▮&r &7ᴘɪɴɢ &f%player_ping%ms &b☄<br>&#25c8fa&l▮&r &7ʙᴀʟᴀɴᴄᴇ &f%vault_eco_balance_formatted% &e$<br>&#25c8fa&l▮&r &7ʀᴀɴᴋ &f%luckperms_primary_group_name% &6🛡<newline>&#25c8fa&l→ &rᴄʟɪᴄᴋ ᴛᴏ sᴇɴᴅ ᴀ ᴘʀɪᴠᴀᴛᴇ ᴍᴇssᴀɢᴇ";
        String action = "SUGGEST_COMMAND:/msg %player_name%";

        String built = dev.lyy.friendlycore.modules.chat.ChatModule.buildStructuredChatFormat(msg, hover, action);
        net.kyori.adventure.text.Component msgComp = service.parse("Hello everyone!");
        net.kyori.adventure.text.Component parsed = service.parseForPlayerWithComponent(null, built, java.util.Map.of("%player_name%", "NarutoLunakai", "{player_name}", "NarutoLunakai"), "{message}", msgComp);

        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(parsed);
        Assertions.assertTrue(plain.contains("NarutoLunakai ➠ Hello everyone!"));
        Assertions.assertFalse(plain.contains("%customfontlegacy"));
        Assertions.assertFalse(plain.contains("%player_health_scale%"));
        Assertions.assertNotNull(parsed);
    }

    @Test
    public void testTpaAcceptClickActionFormatting() {
        TextService service = new TextService();
        String message = "<gradient:#6EDBFF:#A7F3FF><bold>⇄ TELEPORT</bold></gradient> <dark_gray>» <color:#6EDBFF>%player%<color:#D8D0C3> wants to teleport.\n<click:run_command:'/tpaccept %player%'><hover:show_text:'Accept'><color:#8DE969>[ACCEPT]</color></hover></click>";

        // Parse with explicit sender placeholder
        net.kyori.adventure.text.Component parsed = service.parse(message, java.util.Map.of("%player%", "SenderBob"));
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(parsed);

        Assertions.assertTrue(plain.contains("SenderBob"));
        Assertions.assertTrue(plain.contains("[ACCEPT]"));
    }

    @Test
    public void testCommaSeparatedGradientPreservation() {
        String msg = "<gradient:#00d2ff,#3a7bd5><bold>FriendlyCore</bold></gradient>";
        String converted = TextService.convertLegacyToMiniMessage(msg);
        Assertions.assertEquals("<gradient:#00d2ff:#3a7bd5><bold>FriendlyCore</bold></gradient>", converted);

        TextService service = new TextService();
        net.kyori.adventure.text.Component parsed = service.parse(msg);
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(parsed);
        Assertions.assertEquals("FriendlyCore", plain);
    }

    @Test
    public void testHoverAndQuotesSafety() {
        String hoverWithApostrophe = "Click to view Naruto's #123456 balance";
        String msg = "%player% &8» ";
        String action = "RUN_COMMAND:/pay Naruto 100";

        String built = dev.lyy.friendlycore.modules.chat.ChatModule.buildStructuredChatFormat(msg, hoverWithApostrophe, action);
        TextService service = new TextService();
        net.kyori.adventure.text.Component msgComp = service.parse("Hello!");
        net.kyori.adventure.text.Component parsed = service.parseForPlayerWithComponent(null, built, java.util.Map.of("%player%", "Naruto"), "{message}", msgComp);

        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(parsed);
        Assertions.assertTrue(plain.contains("Naruto » Hello!"));
        Assertions.assertNotNull(parsed);
    }

    @Test
    public void testResolveMessageWithoutConfig() {
        TextService service = new TextService();
        Assertions.assertEquals("combat.status-safe", service.resolveMessage("combat.status-safe"));
        Assertions.assertEquals("<red>Hello World", service.resolveMessage("<red>Hello World"));
    }

    @Test
    public void testExplicitMoneyPlaceholderNotOverwrittenByPlayerDefaults() {
        TextService service = new TextService();
        String titleTemplate = "&0⏵ Team | Bank: $%money%";
        net.kyori.adventure.text.Component parsed = service.parseForPlayer(null, titleTemplate, java.util.Map.of("%money%", "50,000.00"));
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(parsed);
        Assertions.assertEquals("⏵ Team | Bank: $50,000.00", plain);
    }

    @Test
    public void testStaffPrefixFormattingAndNoRawTags() {
        TextService service = new TextService();
        String chatFormat = "%luckperms_prefix% &f%player_name% &8➠ <white>{message}</white>";

        // Prefix with MiniMessage tags
        String miniPrefix = "<#FF0000>*<newline><#FF0000><bold>s<bold>T<bold>A<bold>F<bold>F<#FF8989> ";
        net.kyori.adventure.text.Component msgComp = service.parse("sadsa");
        net.kyori.adventure.text.Component parsedMini = service.parseForPlayerWithComponent(
                null,
                chatFormat,
                java.util.Map.of("%luckperms_prefix%", miniPrefix, "%player_name%", "yutu_friendly"),
                "{message}",
                msgComp
        );

        String plainMini = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(parsedMini);
        Assertions.assertFalse(plainMini.contains("<#FF0000>"), "Should not contain raw hex tags");
        Assertions.assertFalse(plainMini.contains("<bold>"), "Should not contain raw bold tags");
        Assertions.assertFalse(plainMini.contains("<#FF8989>"), "Should not contain raw color tags");
        Assertions.assertTrue(plainMini.contains("*"), "Should contain asterisk");
        Assertions.assertTrue(plainMini.contains("sTAFF"), "Should contain sTAFF text");
        Assertions.assertTrue(plainMini.contains("yutu_friendly ➠ sadsa"), "Should contain player name and message");

        // Prefix with legacy hex codes &#FF0000 and formatting
        String legacyPrefix = "&#FF0000*\n&#FF0000&ls&lT&lA&lF&lF&#FF8989 ";
        net.kyori.adventure.text.Component parsedLegacy = service.parseForPlayerWithComponent(
                null,
                chatFormat,
                java.util.Map.of("%luckperms_prefix%", legacyPrefix, "%player_name%", "yutu_friendly"),
                "{message}",
                msgComp
        );

        String plainLegacy = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(parsedLegacy);
        Assertions.assertFalse(plainLegacy.contains("&#FF0000"), "Should not contain raw hex codes");
        Assertions.assertFalse(plainLegacy.contains("<bold>"), "Should not contain raw bold tags");
        Assertions.assertTrue(plainLegacy.contains("*"));
        Assertions.assertTrue(plainLegacy.contains("sTAFF"));
        Assertions.assertTrue(plainLegacy.contains("yutu_friendly ➠ sadsa"));
    }

    @Test
    public void testBracketAndSemicolonHexVariants() {
        String msg1 = "{#FF5733}Bracket Hex Text";
        String converted1 = TextService.convertLegacyToMiniMessage(msg1);
        Assertions.assertEquals("<#FF5733>Bracket Hex Text", converted1);

        String msg2 = "&#FF5733;Semicolon Hex Text";
        String converted2 = TextService.convertLegacyToMiniMessage(msg2);
        Assertions.assertEquals("<#FF5733>Semicolon Hex Text", converted2);

        String msg3 = "§#FF5733;Section Semicolon Hex Text";
        String converted3 = TextService.convertLegacyToMiniMessage(msg3);
        Assertions.assertEquals("<#FF5733>Section Semicolon Hex Text", converted3);
    }

    @Test
    public void testPrefixWithResetDoesNotEmitDanglingTags() {
        TextService service = new TextService();
        String msg = "%luckperms_prefix% &f%player_name% &8➠ ";
        String hover = "&#25c8fa&lINFO<br>&#25c8fa&l▮&r &7ʟɪғᴇ &f20 &c❤";
        String action = "SUGGEST_COMMAND:/msg %player_name%";

        String built = dev.lyy.friendlycore.modules.chat.ChatModule.buildStructuredChatFormat(msg, hover, action);

        // Prefix containing &r or §r
        String prefixWithReset = "&r🔥 <#d199ff>MVP ";
        net.kyori.adventure.text.Component msgComp = service.parse("tysm skyneverdark358");
        net.kyori.adventure.text.Component parsed = service.parseForPlayerWithComponent(
                null,
                built,
                java.util.Map.of("%luckperms_prefix%", prefixWithReset, "%player_name%", "PUKMIND"),
                "{message}",
                msgComp
        );

        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(parsed);
        System.out.println("Plain output: '" + plain + "'");
        Assertions.assertFalse(plain.contains("</hover>"), "Should not contain dangling </hover>");
        Assertions.assertFalse(plain.contains("</click>"), "Should not contain dangling </click>");
        Assertions.assertTrue(plain.contains("PUKMIND ➠ tysm skyneverdark358"), "Should contain proper name and message");
    }

    @Test
    public void testTimeAndSecondsPlaceholderResolution() {
        TextService service = new TextService();
        String rtpWarmupMsg = "&e⚡ &6&lRTP &8» &fᴛᴇʟᴇᴘᴏʀᴛɪɴɢ ɪɴ #fbf100%time% ⌚ &fsᴇᴄᴏɴᴅs...";

        // Test passing %seconds% into template containing %time%
        net.kyori.adventure.text.Component parsed1 = service.parse(rtpWarmupMsg, java.util.Map.of("%seconds%", "3"));
        String plain1 = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(parsed1);
        Assertions.assertTrue(plain1.contains("3"), "Plain text should contain '3'");
        Assertions.assertFalse(plain1.contains("%time%"), "Plain text should not contain literal '%time%'");
        Assertions.assertFalse(plain1.contains("%seconds%"), "Plain text should not contain literal '%seconds%'");

        // Test passing %time% into template containing %seconds%
        String rtpCooldownMsg = "<gradient:#ffa726:#fb8c00><bold>COOLDOWN</bold></gradient> <dark_gray>»</dark_gray> <gray>Please wait <#ffa726>%seconds%s</#ffa726> before using RTP again.</gray>";
        net.kyori.adventure.text.Component parsed2 = service.parse(rtpCooldownMsg, java.util.Map.of("%time%", "10"));
        String plain2 = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(parsed2);
        Assertions.assertTrue(plain2.contains("10s"), "Plain text should contain '10s'");
        Assertions.assertFalse(plain2.contains("%seconds%"), "Plain text should not contain literal '%seconds%'");

        // Test applyPlayerDefaults alias substitution
        String defaulted = service.applyPlayerDefaults(null, rtpWarmupMsg, java.util.Map.of("%seconds%", "5"));
        Assertions.assertFalse(defaulted.contains("%time%"));
        Assertions.assertTrue(defaulted.contains("5"));
    }

    @Test
    public void testPlaceholderApiInjectionPrevented() {
        TextService service = new TextService();
        String template = "<gray>Payment sent to <white>%target%</white>: <green>$%amount%</green> (<yellow>{reason}</yellow>)";
        String userReason = "%vault_eco_balance% %javascript_eval:1+1%";

        String resolved = service.applyPlayerDefaults(null, template, java.util.Map.of(
                "%target%", "Notch",
                "%amount%", "100",
                "{reason}", userReason
        ));

        // The user input should be escaped and retained literally, not evaluated into PAPI values
        Assertions.assertTrue(resolved.contains("%vault_eco_balance%"));
        Assertions.assertTrue(resolved.contains("%javascript_eval:1+1%"));
    }

    @Test
    public void testMiniMessageTagEscapingPreservedInConvertLegacy() {
        TextService service = new TextService();
        String rawUserInput = "<click:run_command:\"/op Hacker\">Click me</click>";
        String escaped = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(rawUserInput);

        Assertions.assertEquals("\\<click:run_command:\"/op Hacker\">Click me\\</click>", escaped);

        String converted = TextService.convertLegacyToMiniMessage(escaped);
        net.kyori.adventure.text.Component parsed = service.parse(converted);
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(parsed);

        Assertions.assertTrue(plain.contains("<click:run_command:\"/op Hacker\">"));
        Assertions.assertTrue(plain.contains("Click me"));
        Assertions.assertTrue(plain.contains("</click>"));
    }

    @Test
    public void testSanitizeChatInputStripsUnauthorizedFormatting() {
        String inputWithHex = "&#FF5555Red &lBold text &x&1&2&3&4&5&6Hex";
        String sanitized = TextService.sanitizeChatInput(null, inputWithHex);

        Assertions.assertFalse(sanitized.contains("&#FF5555"));
        Assertions.assertFalse(sanitized.contains("&l"));
        Assertions.assertFalse(sanitized.contains("&x&1&2&3&4&5&6"));
    }

    @Test
    public void testParseForPlayerRetainsAnglePlaceholderCompatibility() {
        TextService service = new TextService();
        net.kyori.adventure.text.Component parsed = service.parseForPlayer(
                null, "<gray>Hello <player></gray>", java.util.Map.of("%player%", "Eangly"));

        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(parsed);
        Assertions.assertEquals("Hello Eangly", plain);
    }

    @Test
    public void testParseForPlayerStillEscapesExplicitValues() {
        TextService service = new TextService();
        String malicious = "<click:run_command:'/op Attacker'><red>Hacked</red></click>";
        net.kyori.adventure.text.Component parsed = service.parseForPlayer(
                null, "<gray>%player%</gray>", java.util.Map.of("%player%", malicious));

        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(parsed);
        Assertions.assertTrue(plain.contains("Hacked"));
        assertNoClickEvents(parsed);

        // Verify %message% and %reason% are also escaped against click event injection
        net.kyori.adventure.text.Component parsedMsg = service.parse(
                "<dark_gray>[Alert]</dark_gray> <red>%message%</red>", java.util.Map.of("%message%", malicious));
        assertNoClickEvents(parsedMsg);

        net.kyori.adventure.text.Component parsedReason = service.parse(
                "<red>Reason: %reason%</red>", java.util.Map.of("%reason%", malicious));
        assertNoClickEvents(parsedReason);
    }

    @Test
    public void testPlayerMetadataProvidersAreResolvedOnDemand() {
        int[] lookups = new int[4];
        TextService service = new TextService() {
            @Override
            public String getPlayerPrefix(org.bukkit.entity.Player player) {
                lookups[0]++;
                return "<green>VIP</green> ";
            }

            @Override
            public String getPlayerSuffix(org.bukkit.entity.Player player) {
                lookups[1]++;
                return "suffix";
            }

            @Override
            public String getPlayerPrimaryGroup(org.bukkit.entity.Player player) {
                lookups[2]++;
                return "vip";
            }

            @Override
            public String getPlayerBalance(org.bukkit.entity.Player player) {
                lookups[3]++;
                return "100.00";
            }
        };
        org.bukkit.entity.Player player = fakePlayer();

        String staticText = service.applyPlayerDefaults(player, "Static GUI lore", java.util.Map.of("%money%", "50.00"));
        Assertions.assertEquals("Static GUI lore", staticText);
        Assertions.assertArrayEquals(new int[]{0, 0, 0, 0}, lookups,
                "Unreferenced providers must not be queried during GUI rendering");

        String prefixed = service.applyPlayerDefaults(player, "%prefix%Welcome");
        Assertions.assertTrue(prefixed.contains("VIP"));
        Assertions.assertArrayEquals(new int[]{1, 0, 0, 0}, lookups,
                "Only the provider referenced by the template should be queried");
    }

    @Test
    public void testGuiLoreBatchParsingPerformanceAndCorrectness() {
        TextService service = new TextService();
        org.bukkit.entity.Player player = fakePlayer();

        java.util.Map<String, String> placeholders = new java.util.HashMap<>();
        placeholders.put("%money%", "12.5k");
        placeholders.put("%money_formatted%", "12,500.00");
        placeholders.put("%money_exact%", "12500");
        placeholders.put("%balance%", "12.5k");
        placeholders.put("%balance_formatted%", "12,500.00");
        placeholders.put("%team_name%", "Dragons");
        placeholders.put("%team_tag%", "DRG");
        placeholders.put("%team_color%", "gold");
        placeholders.put("%player%", "TestPlayer");
        placeholders.put("%bank_limit%", "100.0k");
        placeholders.put("%kills%", "42");
        placeholders.put("%deaths%", "3");
        placeholders.put("%rank%", "1");

        java.util.List<String> loreTemplate = java.util.List.of(
                "&7Status: &aActive",
                "&7Balance: &e$%money_formatted%",
                "&7Team: &6%team_name% &7(&b%team_tag%&7)",
                "&7Kills: &f%kills% &7| Deaths: &f%deaths%",
                "&7Rank: &6#%rank%",
                "&7Bank Limit: &e$%bank_limit%",
                "&eClick here to deposit &6$%money%",
                "&8<!italic>ID: #12345"
        );

        // Verify correctness
        java.util.List<net.kyori.adventure.text.Component> parsed = service.parseListForPlayer(player, loreTemplate, placeholders);
        Assertions.assertEquals(8, parsed.size());

        // Warm up JIT
        for (int i = 0; i < 20; i++) {
            service.parseListForPlayer(player, loreTemplate, placeholders);
        }

        // Benchmark 54 items x 8 lines = 432 lines per GUI, over 50 GUI opens = 21,600 lines
        long start = System.nanoTime();
        for (int gui = 0; gui < 50; gui++) {
            for (int slot = 0; slot < 54; slot++) {
                service.parseListForPlayer(player, loreTemplate, placeholders);
            }
        }
        long durationMs = (System.nanoTime() - start) / 1_000_000;

        // Even in CI / test environments, 21,600 lines with cached placeholders should easily run under 2500ms (50ms per GUI open)
        Assertions.assertTrue(durationMs < 2500, "GUI parsing took too long: " + durationMs + "ms for 21,600 lines");
    }

    @Test
    public void testReplacePlaceholdersFastEdgeCases() {
        java.util.Map<String, String> lookup = java.util.Map.of(
                "player", "Bob",
                "team", "Red",
                "self_ref", "%player%",
                "num", "100"
        );

        // Null and empty strings
        Assertions.assertNull(TextService.replacePlaceholdersFast(null, lookup));
        Assertions.assertEquals("", TextService.replacePlaceholdersFast("", lookup));
        Assertions.assertEquals("No tokens", TextService.replacePlaceholdersFast("No tokens", lookup));

        // Malformed and single % or { tokens
        Assertions.assertEquals("%", TextService.replacePlaceholdersFast("%", lookup));
        Assertions.assertEquals("%%", TextService.replacePlaceholdersFast("%%", lookup));
        Assertions.assertEquals("%%%", TextService.replacePlaceholdersFast("%%%", lookup));
        Assertions.assertEquals("%player", TextService.replacePlaceholdersFast("%player", lookup));
        Assertions.assertEquals("{player", TextService.replacePlaceholdersFast("{player", lookup));
        Assertions.assertEquals("}", TextService.replacePlaceholdersFast("}", lookup));
        Assertions.assertEquals("{}", TextService.replacePlaceholdersFast("{}", lookup));

        // Standard replacements
        Assertions.assertEquals("Bob", TextService.replacePlaceholdersFast("%player%", lookup));
        Assertions.assertEquals("Bob", TextService.replacePlaceholdersFast("{player}", lookup));
        Assertions.assertEquals("Bob", TextService.replacePlaceholdersFast("%PLAYER%", lookup));
        Assertions.assertEquals("Hello Bob on Red!", TextService.replacePlaceholdersFast("Hello %player% on %team%!", lookup));
        Assertions.assertEquals("Hello Bob on Red!", TextService.replacePlaceholdersFast("Hello {player} on {team}!", lookup));

        // Adjacent tokens
        Assertions.assertEquals("BobRed", TextService.replacePlaceholdersFast("%player%%team%", lookup));
        Assertions.assertEquals("BobRed", TextService.replacePlaceholdersFast("{player}{team}", lookup));
        Assertions.assertEquals("BobRed", TextService.replacePlaceholdersFast("%player%{team}", lookup));

        // Recursive placeholder in value should not re-expand or cause infinite loops
        Assertions.assertEquals("%player%", TextService.replacePlaceholdersFast("%self_ref%", lookup));

        // Number token
        Assertions.assertEquals("100 coins", TextService.replacePlaceholdersFast("%num% coins", lookup));
    }

    private static org.bukkit.entity.Player fakePlayer() {
        return (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(
                TextServiceTest.class.getClassLoader(),
                new Class<?>[]{org.bukkit.entity.Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName", "getDisplayName" -> "TestPlayer";
                    case "getUniqueId" -> java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
                    case "isOnline" -> true;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "TestPlayer";
                    default -> {
                        Class<?> type = method.getReturnType();
                        if (type == boolean.class) yield false;
                        if (type == byte.class) yield (byte) 0;
                        if (type == short.class) yield (short) 0;
                        if (type == int.class) yield 0;
                        if (type == long.class) yield 0L;
                        if (type == float.class) yield 0.0f;
                        if (type == double.class) yield 0.0d;
                        if (type == char.class) yield '\0';
                        yield null;
                    }
                });
    }

    private static void assertNoClickEvents(net.kyori.adventure.text.Component component) {
        Assertions.assertNull(component.clickEvent(), "Explicit player text must not create click events");
        for (net.kyori.adventure.text.Component child : component.children()) {
            assertNoClickEvents(child);
        }
    }

    @Test
    public void testCrossServerPlaceholderResolutionWithNullPlayer() {
        TextService service = new TextService();
        java.util.Map<String, String> placeholders = java.util.Map.of(
                "player", "Steve",
                "displayname", "SuperSteve",
                "server", "survival",
                "prefix", "[Admin]",
                "suffix", "[VIP]",
                "group", "admin",
                "health", "18",
                "ping", "42",
                "balance", "1,250.50"
        );

        String template = "%luckperms_prefix% %player% (%player_displayname%) [%group%] - HP: %health% Ping: %ping%ms Bal: %balance% on %server%";
        String resolved = service.applyPlayerDefaults(null, template, placeholders);

        Assertions.assertTrue(resolved.contains("[Admin]"));
        Assertions.assertTrue(resolved.contains("Steve"));
        Assertions.assertTrue(resolved.contains("SuperSteve"));
        Assertions.assertTrue(resolved.contains("admin"));
        Assertions.assertTrue(resolved.contains("18"));
        Assertions.assertTrue(resolved.contains("42ms"));
        Assertions.assertTrue(resolved.contains("1,250.50"));
        Assertions.assertTrue(resolved.contains("survival"));
    }

    @Test
    public void testCrossServerFallbackWithNullPlayer() {
        TextService service = new TextService();
        java.util.Map<String, String> placeholders = java.util.Map.of("player", "Alex");

        String template = "Prefix: '%prefix%' Display: '%player_displayname%' HP: '%health%' Ping: '%ping%' Bal: '%balance%'";
        String resolved = service.applyPlayerDefaults(null, template, placeholders);

        Assertions.assertTrue(resolved.contains("Prefix: ''"));
        Assertions.assertTrue(resolved.contains("Display: 'Alex'"));
        Assertions.assertTrue(resolved.contains("HP: '20'"));
        Assertions.assertTrue(resolved.contains("Ping: '0'"));
        Assertions.assertTrue(resolved.contains("Bal: '0.00'"));
    }

    @Test
    public void testHoverTextCRLFAndBrNormalization() {
        String input = "Line1\r\nLine2\rLine3<br>Line4<br/>Line5";
        String converted = TextService.convertLegacyToMiniMessage(input);

        Assertions.assertFalse(converted.contains("\r"), "CR characters should be stripped or normalized");
        Assertions.assertTrue(converted.contains("Line1\nLine2\nLine3<newline>Line4<newline>Line5"));
    }

    @Test
    public void testCrossServerServerAndDisplayNameAliases() {
        TextService service = new TextService();
        java.util.Map<String, String> placeholders = java.util.Map.of(
                "player", "Notch",
                "displayname", "CreatorNotch",
                "server_id", "lobby-1"
        );

        String template = "%player_display_name% / {player_displayname} / {player_display_name} on %server_id% / {server_id} / %server_name% / {server_name}";
        String resolved = service.applyPlayerDefaults(null, template, placeholders);

        Assertions.assertTrue(resolved.contains("CreatorNotch / CreatorNotch / CreatorNotch on lobby-1 / lobby-1 / lobby-1 / lobby-1"));
    }

    @Test
    public void testNullPlayerFullFallbackCoverage() {
        TextService service = new TextService();
        java.util.Map<String, String> placeholders = java.util.Map.of("player", "Jeb");

        String template = "HP:%player_health_scale%|PING:%player_ping%|BAL:%vault_eco_balance%|BALF:%vault_eco_balance_formatted%|GRP:%luckperms_primary_group_name%|PRE:%luckperms_prefix%|SUF:%luckperms_suffix%|DISP:%player_display_name%";
        String resolved = service.applyPlayerDefaults(null, template, placeholders);

        Assertions.assertEquals("HP:20|PING:0|BAL:0.00|BALF:0.00|GRP:|PRE:|SUF:|DISP:Jeb", resolved);
    }

    @Test
    public void testHoverTextExtendedBrAndWhitespaceNormalization() {
        String input = "A<br />B<BR>C<BR/>D<BR />E\r\nF\rG";
        String converted = TextService.convertLegacyToMiniMessage(input);

        Assertions.assertFalse(converted.contains("\r"), "No CR characters should remain");
        Assertions.assertEquals("A<newline>B<newline>C<newline>D<newline>E\nF\nG", converted);
    }

    @Test
    public void testParseCachedMatchesParseAndReusesResult() {
        TextService text = new TextService();
        String line = "&8▪ &7Price: #E63A35$1,000";
        Assertions.assertEquals(text.parse(line), text.parseCached(line));
        Assertions.assertSame(text.parseCached(line), text.parseCached(line));
        String longLine = "x".repeat(300);
        Assertions.assertEquals(text.parse(longLine), text.parseCached(longLine));
    }
}
