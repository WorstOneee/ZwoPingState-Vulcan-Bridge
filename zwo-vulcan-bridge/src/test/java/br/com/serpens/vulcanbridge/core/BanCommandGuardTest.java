package br.com.serpens.vulcanbridge.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BanCommandGuardTest {

    @ParameterizedTest
    @DisplayName("comandos que podem banir IP são reconhecidos")
    @ValueSource(strings = {
            "mpban %player% x", "/mpban %player% x", "mptempban %player% 7d x",
            "mpipban %player% x", "ban-ip %player%", "minecraft:ban-ip %player%", "essentials:banip %player%",
            "IPBAN %player%"})
    void detectsIpCommands(String cmd) {
        assertTrue(BanCommandGuard.bansIp(cmd), cmd);
    }

    @ParameterizedTest
    @DisplayName("ban só de perfil e kick não são marcados")
    @ValueSource(strings = {
            "mpbanperfil %player% Anticheat", "minecraft:ban %player% x", "ban %player% x",
            "kick %player% &c[Vulcan] Unfair Advantage", ""})
    void profileOnlyIsFine(String cmd) {
        assertFalse(BanCommandGuard.bansIp(cmd), cmd);
    }

    @Test
    void nullIsFine() {
        assertFalse(BanCommandGuard.bansIp(null));
    }

    @Test
    @DisplayName("lista os comandos de IP sem repetir")
    void listsUnique() {
        List<String> found = BanCommandGuard.ipBanCommands(List.of(
                "kick %player% x", "mpban %player% x", "mpban %player% x", "mpbanperfil %player% x", "ban-ip %player%"));
        assertEquals(List.of("mpban %player% x", "ban-ip %player%"), found);
    }
}
