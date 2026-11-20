package br.com.serpens.vulcanbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tira o jogador da rede inteira (proxy), em vez de mandá-lo para o lobby.
 *
 * <p>Usa a subcanal {@code KickPlayer} do canal BungeeCord, que o Velocity atende quando
 * {@code bungee-plugin-message-channel = true} (padrão). A mensagem vai pela conexão do próprio
 * jogador <b>antes</b> do kick do servidor, na mesma conexão: o proxy desconecta o jogador e o kick
 * do servidor chega depois, sem efeito. Se o proxy não anunciou o canal, nada é enviado e o kick
 * normal acontece (o jogador vai para o servidor de fallback, como antes).</p>
 */
final class ProxyKick {

    static final String CHANNEL = "BungeeCord";
    private static final Set<String> PROXY_CHANNELS = Set.of("bungeecord:main", "BungeeCord");

    private final Plugin plugin;
    final LongAdder sent = new LongAdder();
    final LongAdder unavailable = new LongAdder();
    final LongAdder failed = new LongAdder();
    private volatile boolean lastSeenChannel;
    private volatile boolean everChecked;

    ProxyKick(Plugin plugin) {
        this.plugin = plugin;
    }

    void register() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
    }

    void unregister() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
    }

    /** O proxy anunciou o canal BungeeCord para este jogador? */
    static boolean proxySupports(Player p) {
        for (String c : p.getListeningPluginChannels()) {
            if (PROXY_CHANNELS.contains(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pede ao proxy para desconectar o jogador. Thread principal, com a conexão ainda aberta (por
     * exemplo, dentro do PlayerKickEvent). Devolve {@code false} se não deu para pedir.
     */
    boolean disconnect(Player p, Component reason) {
        everChecked = true;
        if (!proxySupports(p)) {
            lastSeenChannel = false;
            unavailable.increment();
            return false;
        }
        lastSeenChannel = true;
        try {
            p.sendPluginMessage(plugin, CHANNEL, encode(p.getName(), reason));
            sent.increment();
            return true;
        } catch (Throwable t) {
            failed.increment();
            return false;
        }
    }

    /** {@code KickPlayer}, nome, motivo (texto legado com §), no formato do canal BungeeCord. */
    static byte[] encode(String playerName, Component reason) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("KickPlayer");
            out.writeUTF(playerName);
            out.writeUTF(LegacyComponentSerializer.legacySection().serialize(reason));
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e); // não acontece em memória
        }
    }

    String describe() {
        if (!everChecked) {
            return "nenhum kick ainda (o canal é conferido no momento do kick)";
        }
        return (lastSeenChannel ? "canal do proxy disponível" : "canal do proxy AUSENTE no último kick "
                + "(confira bungee-plugin-message-channel = true no velocity.toml)")
                + " | pedidos " + sent.sum() + " | sem canal " + unavailable.sum() + " | falhas " + failed.sum();
    }
}
