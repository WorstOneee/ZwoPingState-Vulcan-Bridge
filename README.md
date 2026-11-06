# ZwoPingState + ZwoVulcanBridge

Plugins for **Paper 1.21.11 / Java 21**, built for the **Serpens New Era** server (Velocity + Geyser + Floodgate).
Goal: automatic anticheat punishment **only on strong evidence**, never because of lag, at a low cost.

| Plugin | Role |
| --- | --- |
| **ZwoPingState 1.1.0** | Reports, per UUID, a snapshot: `STABLE`/`UNSTABLE`/`UNKNOWN`, collection session, instability revisions (player and server) and freshness. **It never punishes anyone.** |
| **ZwoVulcanBridge 3.1.0** | **The bridge makes the decision.** It reads Vulcan's flags, gathers evidence from approved checks and applies the per-UUID sequence: **kick → kick (another session) → 24 h temporary ban of the profile only**, recorded through ModPanel. Starts in **observation** mode. |
| **ModPanel 3.1.0** | Moderation panel. Version 3.1.0 adds `ProfileBanApi`: a temporary ban by UUID with a verified result, **no IP ban**. |

```
Vulcan (punishable: false) ──VulcanFlagEvent──▶ ZwoVulcanBridge
                                                  │ O(1): 1 snapshot (ZwoPingState): STABLE, fresh, same revisions?
                                                  │       Java confirmed? no protection?
                                                  │       approved check? VL >= min-vl?  → store the flag (fixed memory)
                                                  │ >= 2 families, each check repeated min-flags times within the window
                                                  ▼ = OCCURRENCE (at most 1 per session)
                                  server thread: new snapshot, same anchor? protection? session?
                                                  │ OBSERVE → only logs "would have applied"
                                                  ▼ ENFORCE → 1st kick / 2nd kick / 24h ban via ProfileBanApi
                                  per-UUID history (historico.yml, written off the server thread)
```

- Installation, activation and rollback: [docs/INSTALLATION.md](docs/INSTALLATION.md)
- Diagnostics (`/pingstate`, `/zvb`, `decisoes.log`, `historico.yml`): [docs/DIAGNOSTICS.md](docs/DIAGNOSTICS.md)
- Commented configuration: [ZwoPingState](zwo-ping-state/src/main/resources/config.yml) ·
  [ZwoVulcanBridge](zwo-vulcan-bridge/src/main/resources/config.yml)

## How the bridge decides

**Evidence (path of each flag, O(1), on the thread where Vulcan fires it):**

1. Each flag reads **one snapshot** (state, collection session, player revision, global revision, freshness).
   `UNSTABLE`, `UNKNOWN`, stale or no provider → **discards the player's whole sequence**. A provider without
   revisions (ZwoPingState 1.0.0) → nothing counts and automation is blocked.
   - The 1st piece of evidence stores the **anchor** (provider, collection session and both revisions). If any of
     them changes — for example, the player lagged **without producing flags** and already recovered — the
     previous sequence is discarded and the current flag starts a **new** one. Recovery never completes the old one.
   - Revisions only grow: they advance on every instability, inconclusive data, read failure or loss of collection
     continuity. Server instability advances one **global** revision (no player scan).
2. Session that already had an occurrence → ignored (at most one per session).
3. Bedrock → excluded from automation.
4. Check not approved in `config.yml`, or VL below `min-vl` → does not count.
5. Teleport, respawn or world-change protection active → does not count.
6. Otherwise, the flag is stored in that check's ring buffer (only the last `min-flags`, max. 32).
7. Occurrence = at least `min-families` (minimum 2) families with a check that repeated `min-flags` times
   within `window-seconds`. A single flag, or many flags from the same check/family, is never enough.

No I/O, SQL, network, player scans or scheduled task per flag. Only an occurrence schedules **one** task.

**Action (server thread):**

1. Checks that the player is still in the **same session** and reads a new snapshot: it must be STABLE, fresh and
   carry the **same anchor** as the sequence (lag detected after the last flag cancels it), with no active
   protection. If that fails, it is discarded.
2. Account not confirmed as Java (Floodgate) → logged for review only. A name prefix does not prove Java.
3. `OBSERVE` → logs what it **would** have done, without touching the real history.
4. Automation blocked (no checks, Vulcan, provider or ModPanel 3.1) → logged for review, with the reason.
5. Otherwise, applies the next step of the UUID's history:
   - **1st kick** and **2nd kick** (in another session): they only count when the disconnect is confirmed as caused
     by the bridge (`PlayerQuitEvent` with reason KICKED after a kick that was not cancelled). A cancelled kick,
     a voluntary quit and a timeout do not count.
   - **Temporary ban** (`sequence.ban-duration`, default 24h) through `ProfileBanApi`. It is only marked as applied
     after an `APPLIED` response verified by ModPanel, with no IP ban. Failure, timeout or an uncertain response →
     **review**, with no automatic retry.
6. The history is valid for `sequence.validity` (7 days) from the 1st kick.

Vulcan only detects: every check is set to `punishable: false`. If any check still punishes, the bridge cancels
the punishment and warns in the console.

## Building

```bash
./gradlew release
```

Produces `build/release/ZwoVulcanBridge-3.1.0.jar` and `build/release/ZwoPingState-1.1.0.jar`. Prebuilt JARs are in
[`dist/`](dist). ModPanel 3.1.0 is built in its own repository (`mvn package`); its JAR is also in `dist/` for
convenience.

The bridge compiles against a copy of the `dev.zwo.modpanel.api` interface (the `modpanelApi` source set), which
**is not packaged in the JAR**: at runtime the classes come from ModPanel itself.

## Layout

```
zwo-vulcan-bridge/   API br.com.serpens.vulcanbridge.api (PingStateProvider...)
                     + escalation (core/ without Bukkit, testable) + Vulcan/ModPanel/Floodgate integration
zwo-ping-state/      provider plugin (core/ without Bukkit, testable)
docs/                installation and diagnostics
dist/                prebuilt JARs for this version
```
