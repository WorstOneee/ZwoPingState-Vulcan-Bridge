# Diagnostics

The commands only **read the ready state**: they do not trigger sampling or recalculate anything.

The plugins print their output in Portuguese. The samples below are shown exactly as printed, with the meaning of
each field explained in English.

## ZwoPingState — `/pingstate` (alias `/zps`)

Permissions: `zwopingstate.staff` (view) and `zwopingstate.admin` (reload). Both default to `op`.

### `/pingstate <nick>`

```
Fulano — resposta atual: UNSTABLE (picos de ping)
Avaliação da coleta: UNSTABLE (picos de ping) | calculada há 412 ms
Idade da última amostra: 412 ms | conectado há 95 s
Ping: último 45 | média 61 | mín 44 | máx 167 ms
Oscilação (jitter): 7 ms | picos: 1 | amostras: 20/20 (mín. 8)
Recuperação: faltam 6400 ms de período bom contínuo
Limites: ping 250, média 200, jitter 25, amplitude 60, salto de pico 80, picos tolerados 0
Servidor: estável | TPS curto 20.00 | MSPT 3.1
```

- **resposta atual** (current answer) is exactly what the bridge receives right now (it already accounts for stale
  data and an unstable server).
- **avaliação da coleta** (sampler evaluation) is the state computed at the player's last sample.
- The other lines show the age of the last sample, the ping (last, average, min, max), jitter, spikes, sample count,
  the recovery time left, the configured limits and the server health (short TPS, MSPT).

| Reason (as printed) | State | Meaning |
| --- | --- | --- |
| conexão estável | STABLE | enough recent samples, all within the limits |
| ping alto | UNSTABLE | last sample > `max-ping` or average > `max-average-ping` |
| picos de ping | UNSTABLE | a jump ≥ `spike-jump` or a sample ≥ `spike-absolute` in the window |
| ping oscilando | UNSTABLE | jitter > `max-jitter` or window range > `max-range` |
| servidor instável | UNSTABLE | short TPS < `min-tps`, MSPT > `max-mspt` or a stall > `stall-ms` (applies to everyone) |
| em recuperação | UNSTABLE | back to normal, but has not yet completed `recovery.player-seconds` in a row |
| dados insuficientes | UNKNOWN | just joined/reconnected, or fewer than `min-samples` valid samples |
| dados vencidos | UNKNOWN | the last evaluation is older than `stale-after-seconds` (delayed batch/stall) |
| Bedrock | UNKNOWN | Paper sees Geyser's ping, not the client's |
| falha na coleta | UNKNOWN | exception while reading the ping |
| não monitorado | UNKNOWN | the UUID is not online/registered |

### `/pingstate stats`

```
Jogadores: 98 (88 STABLE 10 UNSTABLE 0 UNKNOWN)
Lote: 5 jogadores/tick (máx. 32) | intervalo: 20 ticks | amostras/s: 94.7
Custo da coleta por tick: média 3.8 µs (1 min) | EMA 4.6 µs | máx. 75.3 µs
Orçamento estourado: 0x | erros de leitura: 0
Idade das amostras: média 521 ms | mais antiga 1033 ms (vence em 5000 ms)
Servidor: estável | TPS curto 19.98 | MSPT 9.2 | última travada 294 ms há 137 s
```

The lines show: players per state; batch size per tick and samples per second; sampling cost per tick; budget
overruns and read errors; sample age (average and oldest); server health and the last stall.

What to watch:

- **amostras/s** (samples per second) ≈ number of players (1 per second each).
- **mais antiga** (oldest) should stay close to 1000 ms. Above 5000 ms, everyone becomes UNKNOWN (stale).
- **Orçamento estourado** (budget exceeded) > 0 often: raise `max-micros-per-tick` or look into the machine.

## ZwoVulcanBridge — `/zvb`

Permissions: `zwovulcanbridge.staff` (view), `zwovulcanbridge.admin` (reload and limpar),
`zwovulcanbridge.alerts` (receive the notice of each decision). All default to `op`.

### `/zvb status`

- **Modo** (mode: `OBSERVE`/`ENFORCE`) and **Automação** (automation): `liberada` (allowed) or
  `BLOQUEADA — <motivos>` (blocked, with reasons: no approved checks, fewer than 2 families, Vulcan disconnected,
  provider unavailable, ModPanel missing/incompatible).
- Approved checks, families and config problems.
- Vulcan: flags seen, Vulcan punishments cancelled, read errors.
- Connection provider (`ZwoPingState 1.1.0 (snapshot v1)`, or `INCOMPATÍVEL: sem snapshot com revisões` =
  incompatible, with 1.0.0), generation (changes whenever the provider is replaced/restarted), failures and invalid
  snapshots.
- ModPanel (`pronto — ModPanel 3.1.0 (API 1)` = ready, `AUSENTE` = missing or `INCOMPATÍVEL — motivo` =
  incompatible, with the reason) and Floodgate.
- Counters: approved flags, discards because of the connection, **discards because of a revision/session change**
  (`por revisão/sessão`: lag or stall between flags, provider replaced, reconnect), flags with an incompatible
  provider, flags during protection, occurrences, observation,
  revalidation, kicks sent/confirmed/cancelled, bans requested/applied/under review.
- History (UUIDs, validity) and write queue (pending, rejected, failed).
- Warnings: Vulcan checks with `punishable: true`, Vulcan commands that ban IPs.

### `/zvb jogador <nick>`

The connection state the bridge receives right now, account type (confirmed Java / Bedrock / uncertain, with the
reason), active protection, session stage (`IDLE`, `TRIGGERED`, `DONE`), evidence in the window, last discard and
this session's decision. Since 3.1:

```
Snapshot: sessão de coleta 2 | rev. jogador 49 | rev. servidor 4 | idade 175 ms
Âncora da sequência: sessão de coleta 2, rev. jogador 9, rev. servidor 4
Sessão: ... descartes 1 (último: instabilidade da conexão desde a 1ª evidência — conexão STABLE/OK, 2 s atrás)
```

(real output from the local test: the sequence started at revision 9; the player lagged without flags and
recovered; on the next flag the revision was already 49 and the old sequence was discarded, even with the
connection STABLE.)

- **Snapshot** is what the provider answers now. **Âncora** (anchor) is what was stored on the 1st piece of
  evidence of the current sequence. If they differ, the next approved flag discards the old sequence.
- Discard reasons: `instabilidade da conexão desde a 1ª evidência` (connection instability), `instabilidade do
  servidor desde a 1ª evidência` (server instability), `nova sessão de coleta` (new collection session),
  `provedor trocado ou reiniciado` (provider replaced/restarted), `telemetria vencida` (stale telemetry),
  `conexão não STABLE`, `provedor sem revisões` (provider without revisions).

### `/zvb historico <nick|uuid>`

Confirmed kicks, date of the 1st kick and how long it is valid, ban state (`NONE`, `PENDING`, `APPLIED`, `REVIEW`,
with the ModPanel detail) and the **next step** that would be applied.

### `/zvb limpar <nick|uuid>` and `/zvb reload`

`limpar` (clear) resets the UUID's history in the bridge (it does not remove bans: use `/mpunban`). `reload`
reloads the config, discards evidence in progress and checks ModPanel and the Vulcan config again.

## `decisoes.log`

`plugins/ZwoVulcanBridge/decisoes.log`. One line per **decision**, never per flag, written off the server thread,
in this format:

```
[date time] nick (uuid) — decision — evidence (check xN, VL, family) — mode
```

Search for `[revisão]` (review): these are the cases that need someone from staff (uncertain account, blocked
automation, cancelled kick, ban that failed/was rejected/is uncertain).

## `historico.yml`

`plugins/ZwoVulcanBridge/historico.yml`, per UUID: `nome`, `kicks`, `primeiro-kick`, `ultimo-kick`,
`ultima-sessao`, `ban`, `ban-em`, `pedido`, `ban-detalhe`. Written to a temporary file and swapped in one step (it
is never left half-written). On startup, expired entries are removed and a `PENDING` ban (server went down midway)
becomes `REVIEW`. **Do not edit it while the server is running**; use `/zvb limpar`.

## Common problems

| Symptom | Likely cause |
| --- | --- |
| `Automação: BLOQUEADA — nenhum check aprovado` (no approved check) | normal with a new config; approve checks in `evidence.checks` |
| `ModPanel: INCOMPATÍVEL` | ModPanel older than 3.1.0 (no `ProfileBanApi`); update the panel |
| `Provedor de conexão: nenhum provedor registrado` (no provider registered) | ZwoPingState missing/disabled → every flag is discarded |
| `Provedor de conexão: ... INCOMPATÍVEL: sem snapshot com revisões` | ZwoPingState 1.0.0 with bridge 3.1 → upgrade ZwoPingState to 1.1.0 |
| Many `por revisão/sessão` discards | frequent lag/stalls between flags; this is the expected behavior (see `/pingstate stats`) |
| Every occurrence is "conta incerta" (uncertain account) | Floodgate is not on the Paper backend, or its API failed |
| "O Vulcan tentou punir N vez(es)" (Vulcan tried to punish N times) | some check is still `punishable: true` in the Vulcan config |
| Warning "comando(s) que banem IP" (commands that ban IPs) | the Vulcan config has `mpban`/`ban-ip`...; with `punishable: false` they do not run, but remove them anyway |
| Ban "em revisão" (under review) | ModPanel refused (protected target), failed or did not answer in time; check the panel |
| Everyone UNKNOWN / "dados vencidos" (stale data) | long stall or sampling stopped; see `/pingstate stats` |
| Players disconnected with "Invalid packet" | packetevents incompatible with Vulcan (not caused by these plugins) |
