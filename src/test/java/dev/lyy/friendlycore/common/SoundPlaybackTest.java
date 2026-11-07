package dev.lyy.friendlycore.common;

import net.kyori.adventure.sound.Sound;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SoundPlaybackTest {
    @Test
    void playerAndLocationPlaybackPreserveResourceKeysAndCustomNamespaces() {
        List<Sound> playerSounds = new ArrayList<>();
        Player player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("isOnline")) return true;
                    if (method.getName().equals("playSound")) playerSounds.add((Sound) args[0]);
                    return null;
                });
        List<Sound> worldSounds = new ArrayList<>();
        World world = (World) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("playSound")) worldSounds.add((Sound) args[0]);
                    return null;
                });
        Location location = new Location(world, 1, 2, 3);
        for (String key : List.of("block.note_block.pling", "minecraft:entity.experience_orb.pickup", "pack:mention_ping")) {
            TextService.playSound(player, key, 0.5f, 1.5f);
            TextService.playSound(location, key, 0.5f, 1.5f);
        }
        List<String> expected = List.of("minecraft:block.note_block.pling", "minecraft:entity.experience_orb.pickup", "pack:mention_ping");
        assertEquals(expected, playerSounds.stream().map(sound -> sound.name().asString()).toList());
        assertEquals(expected, worldSounds.stream().map(sound -> sound.name().asString()).toList());
        assertEquals(Sound.Source.PLAYER, playerSounds.getFirst().source());
        assertEquals(Sound.Source.BLOCK, worldSounds.getFirst().source());
        assertEquals(0.5f, playerSounds.getFirst().volume());
        assertEquals(1.5f, playerSounds.getFirst().pitch());
        TextService.playSound(player, "none", 1, 1);
        TextService.playSound(location, "none", 1, 1);
        assertEquals(3, playerSounds.size());
        assertEquals(3, worldSounds.size());
    }
}
