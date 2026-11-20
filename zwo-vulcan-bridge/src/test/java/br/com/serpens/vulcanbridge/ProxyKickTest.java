package br.com.serpens.vulcanbridge;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Formato lido pelo Velocity no canal BungeeCord: readUTF subcanal, readUTF nome, readUTF motivo. */
class ProxyKickTest {

    @Test
    void kickPlayerFormat() throws IOException {
        byte[] data = ProxyKick.encode("HeathCliff",
                ZwoVulcanBridgePlugin.text("&cVocê foi desconectado pelo anticheat.\n&7Comportamento irregular detectado."));
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        assertEquals("KickPlayer", in.readUTF());
        assertEquals("HeathCliff", in.readUTF());
        assertEquals("§cVocê foi desconectado pelo anticheat.\n§7Comportamento irregular detectado.", in.readUTF());
        assertEquals(0, in.available());
    }
}
