# Installation, activation and rollback

Requirements: **Paper 1.21.11**, **Java 21**, **Vulcan 2.9.7.29** with a **compatible packetevents**, **ModPanel 3.1.0**
and, on a network with Geyser, **Floodgate** installed on the Paper backend, not only on Velocity.

> Vulcan 2.9.7.29 **does not work with packetevents 2.12.1** (every player is disconnected with "Invalid packet").
> Use packetevents 2.14.0 or newer.

> **Do not enable punishments (`mode: ENFORCE`) in production** before going through the observation period (section 6).

The plugins print their console and chat messages in Portuguese. The messages quoted below are shown exactly as the
plugins print them.

## 1. Files

They are in [`dist/`](../dist), as separate files (no ZIP):

| JAR | Purpose |
| --- | --- |
| `ZwoVulcanBridge-3.0.0.jar` | Anticheat escalation. Contains the `PingStateProvider` API. |
| `ZwoPingState-1.0.0.jar` | Connection quality provider (the same 1.0.0 as before). Never punishes anyone. |
| `ModPanel-3.1.0.jar` | Moderation panel with `ProfileBanApi` (temporary ban of the profile only). Replaces SerpensModPanel 2.0.0 / ModPanel 3.0.0. |

Load order is automatic: ZwoPingState has `softdepend: [ZwoVulcanBridge]`; the bridge has
`softdepend: [Vulcan, floodgate, ModPanel]`.

## 2. Step by step

1. **Back up** `plugins/ZwoVulcanBridge/`, `plugins/Vulcan/config.yml`, the panel folder
   (`plugins/SerpensModPanel/` or `plugins/ModPanel/`) and the panel database, if it is MySQL.
2. Stop the server (do not use `/reload`).
3. **ModPanel:** remove the old panel JAR and add `ModPanel-3.1.0.jar`. Coming from SerpensModPanel 2.0.0,
   ModPanel imports the config and the history on its own on first startup (see the ModPanel README). Do not delete
   the old folder until you have checked it.
4. **Bridge:** replace `ZwoVulcanBridge-2.0.0.jar` (or 1.0.0) with `ZwoVulcanBridge-3.0.0.jar`. **Rename** the old
   `plugins/ZwoVulcanBridge/config.yml` (the keys changed) so a new one is generated. The old `punicoes.log` and
   `strikes.yml` are no longer used.
5. **Vulcan:** in `plugins/Vulcan/config.yml`, set **every check to `punishable: false`**. The bridge keeps
   receiving the flags. If any check still punishes, the bridge cancels the punishment and warns in the console and
   in `/zvb status`.
6. Start the server. The console should show:

```
[ZwoVulcanBridge] Floodgate detectado: contas Java/Bedrock serão confirmadas pela API.
[ZwoVulcanBridge] Vulcan 2.9.7.29 conectado: lendo flags (VulcanFlagEvent) ...
[ZwoVulcanBridge] Modo: OBSERVE ...
[ZwoVulcanBridge] Checks aprovados: 0 em 0 família(s) ...
[ZwoVulcanBridge] ModPanel: ...
[ZwoPingState] Provedor PingStateProvider registrado para a ZwoVulcanBridge.
```

7. Run `/zvb status`. With a new config, **"Automação: BLOQUEADA — nenhum check aprovado"** (automation blocked, no
   approved check) is expected: the `evidence.checks` list ships empty on purpose.

## 3. What automation requires

`/zvb status` lists every block. Automation only acts when **all** of these are fine:

- approved checks in at least 2 different families (`evidence.checks`);
- Vulcan connected (listening to `VulcanFlagEvent`);
- connection provider available (ZwoPingState enabled);
- ModPanel 3.1.0+ with `ProfileBanApi` (version 1). ModPanel missing, disabled or outdated = **BLOCKED**, with the
  reason in the status. There is never a fallback to `/mpban`, `/mptempban` or `/ban-ip`.

Even when everything is fine:

- **Bedrock** is excluded (Floodgate, Bedrock UUID or name prefix).
- An account that **cannot be confirmed as Java** (Floodgate missing or failing) is only logged for review.
  `bedrock.assume-java-without-floodgate: true` is only for servers **without** Geyser; with Geyser keep it `false`.

## 4. Choosing the approved checks

During observation, look at which checks show up in the `[OBSERVE]` lines of `decisoes.log` and in Vulcan's alerts
for players who really cheat, and which ones show up for legitimate players. Approve only **high-confidence**
checks, with a high minimum VL, and group them into families. Each `evidence.checks` entry is `name` or
`'name:type'` with `min-vl`, `min-flags` and `family` (see the comments in `config.yml`).

Config problems (duplicate check, invalid `min-vl`, invalid type) are shown in the console and in `/zvb status`.
Apply changes with `/zvb reload` (evidence in progress is discarded).

## 5. Sequence

| Occurrence (per UUID, within `sequence.validity` = 7d from the 1st kick) | Action |
| --- | --- |
| 1st | kick with `messages.kick-1` |
| 2nd, in another session | kick with `messages.kick-2` |
| 3rd, in another session | temporary ban of the profile only for `sequence.ban-duration` (24h), through ModPanel |
| after the ban (applied or under review) | logged for review only |

- A kick only counts once the disconnect is confirmed. A kick cancelled by another plugin does not count.
- A ban that fails, times out (`ban-timeout-seconds`) or gets an uncertain response goes to **review** and is not
  retried. Check it in ModPanel and use `/zvb limpar <nick>` if you want the sequence to start over.
- `/zvb limpar` does not undo bans: use `/mpunban` for that.

## 6. Observation period (mandatory)

Keep `mode: OBSERVE` (the default) for a few days, already with the checks you intend to approve. In this mode the
bridge:

- gathers evidence normally and writes each occurrence to `plugins/ZwoVulcanBridge/decisoes.log` as
  `[OBSERVE] ocorrência válida; teria aplicado: 1º kick ...` (valid occurrence; would have applied: 1st kick);
- does **not** kick, does not ban and does **not** change `historico.yml` (the "would have applied" step is always
  based on the real history);
- notifies staff with `zwovulcanbridge.alerts`.

Review the `[OBSERVE]` lines: if a legitimate player shows up, raise `min-vl`/`min-flags` or remove the check.

## 7. Enabling (only once decided)

Set `mode: ENFORCE` in `plugins/ZwoVulcanBridge/config.yml` and run `/zvb reload`. Check in `/zvb status` that the
"Automação" line says **liberada** (allowed).

## 8. Rollback

**Turn automation off right away (no restart):** `mode: OBSERVE` and `/zvb reload`. Or empty `evidence.checks`.

**Go back to bridge 2.0.0:**

1. Stop the server.
2. Replace `ZwoVulcanBridge-3.0.0.jar` with `ZwoVulcanBridge-2.0.0.jar` (it is in the repository history, commit
   `7b3f5da`, tag `v2.0.0`).
3. Restore the 2.0 `config.yml` from the backup. Keep `historico.yml` and `decisoes.log` (2.0 does not use them).
4. Restore `plugins/Vulcan/config.yml` from the backup (2.0 relied on Vulcan punishing).

**Go back to the old panel:** ModPanel 3.1.0 only **adds** the API, without changing the database. To go back to
ModPanel 3.0.0, swap the JAR; bridge 3.0 will show "ModPanel: INCOMPATÍVEL" (incompatible) and block automation,
without breaking anything. Bans already applied by the bridge stay in the panel history (source `API`).

**Remove everything:** remove the bridge and ZwoPingState JARs. Vulcan keeps only detecting while its checks are
`punishable: false`; restore its config if you want it to punish on its own again.
