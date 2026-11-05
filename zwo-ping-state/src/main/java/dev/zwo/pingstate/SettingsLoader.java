package dev.zwo.pingstate;

import dev.zwo.pingstate.core.PingSettings;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/** Lê o config.yml para {@link PingSettings}. */
final class SettingsLoader {

    private SettingsLoader() {
    }

    static PingSettings load(FileConfiguration c) {
        PingSettings d = PingSettings.defaults();
        return new PingSettings(
                c.getInt("collector.sample-interval-ticks", d.sampleIntervalTicks()),
                c.getInt("collector.max-players-per-tick", d.maxSamplesPerTick()),
                (long) (c.getDouble("collector.max-micros-per-tick", d.maxCollectorNanosPerTick() / 1000.0) * 1000L),
                c.getInt("window.size", d.windowSize()),
                c.getInt("window.min-samples", d.minSamples()),
                c.getInt("limits.max-ping", d.maxPing()),
                c.getInt("limits.max-average-ping", d.maxAveragePing()),
                c.getInt("limits.max-jitter", d.maxJitter()),
                c.getInt("limits.max-range", d.maxRange()),
                c.getInt("limits.spike-jump", d.spikeJump()),
                c.getInt("limits.spike-absolute", d.spikeAbsolute()),
                c.getInt("limits.max-spikes", d.maxSpikes()),
                PingSettings.seconds(c.getDouble("recovery.player-seconds", 10)),
                PingSettings.seconds(c.getDouble("freshness.stale-after-seconds", 5)),
                PingSettings.seconds(c.getDouble("freshness.reset-window-after-gap-seconds", 5)),
                PingSettings.seconds(c.getDouble("join.zero-ping-grace-seconds", 20)),
                c.getDouble("server.min-tps", d.minTps()),
                c.getDouble("server.max-mspt", d.maxMspt()),
                PingSettings.ms(c.getDouble("server.stall-ms", 250)),
                PingSettings.seconds(c.getDouble("recovery.server-seconds", 10)),
                c.getBoolean("bedrock.report-unknown", true));
    }

    static List<String> bedrockPrefixes(FileConfiguration c) {
        return c.getStringList("bedrock.name-prefixes");
    }
}
