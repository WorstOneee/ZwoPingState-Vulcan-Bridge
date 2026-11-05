package br.com.serpens.vulcanbridge.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BanCommandGuardTest {

    @ParameterizedTest
    @DisplayName("comandos que podem banir IP são recusados")
    @ValueSource(strings = {
            "mpban {player} {reason}", "/mpban {player} x", "mptempban {player} 7d x",
            "mpipban {player} x", "ban-ip {player}", "minecraft:ban-ip {player}", "essentials:banip {player}",
            "IPBAN {player}"})
    void refusesIpCommands(String cmd) {
        assertFalse(BanCommandGuard.check(cmd).allowed(), cmd);
    }

    @Test
    void emptyIsPendingNotAllowed() {
        assertFalse(BanCommandGuard.check("").allowed());
        assertFalse(BanCommandGuard.check(null).allowed());
    }

    @Test
    void requiresTarget() {
        assertFalse(BanCommandGuard.check("ban fulano").allowed());
    }

    @Test
    void acceptsProfileOnlyCommand() {
        assertTrue(BanCommandGuard.check("minecraft:ban {player} {reason}").allowed());
        assertTrue(BanCommandGuard.check("mpbanperfil {uuid} {reason}").allowed());
    }
}
