# Diagnóstico

Os comandos só **leem o estado pronto**: não disparam coleta nem recalculam nada.

## ZwoPingState — `/pingstate` (alias `/zps`)

Permissões: `zwopingstate.staff` (ver) e `zwopingstate.admin` (reload). Ambas `op` por padrão.

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

- **resposta atual** é exatamente o que a ponte recebe agora (já inclui dados vencidos e servidor instável).
- **avaliação da coleta** é o estado calculado na última amostra do jogador.

| Motivo | Estado | Significado |
| --- | --- | --- |
| conexão estável | STABLE | amostras suficientes e recentes, todas dentro dos limites |
| ping alto | UNSTABLE | última amostra > `max-ping` ou média > `max-average-ping` |
| picos de ping | UNSTABLE | algum salto ≥ `spike-jump` ou amostra ≥ `spike-absolute` na janela |
| ping oscilando | UNSTABLE | jitter > `max-jitter` ou amplitude da janela > `max-range` |
| servidor instável | UNSTABLE | TPS curto < `min-tps`, MSPT > `max-mspt` ou travada > `stall-ms` (vale para todos) |
| em recuperação | UNSTABLE | voltou ao normal, mas ainda não completou `recovery.player-seconds` contínuos |
| dados insuficientes | UNKNOWN | acabou de entrar/reconectar, ou menos de `min-samples` amostras válidas |
| dados vencidos | UNKNOWN | a última avaliação tem mais de `stale-after-seconds` (lote atrasado/travada) |
| Bedrock | UNKNOWN | o Paper vê o ping do Geyser, não do cliente |
| falha na coleta | UNKNOWN | exceção ao ler o ping |
| não monitorado | UNKNOWN | o UUID não está online/cadastrado |

### `/pingstate stats`

```
Jogadores: 98 (88 STABLE 10 UNSTABLE 0 UNKNOWN)
Lote: 5 jogadores/tick (máx. 32) | intervalo: 20 ticks | amostras/s: 94.7
Custo da coleta por tick: média 3.8 µs (1 min) | EMA 4.6 µs | máx. 75.3 µs
Orçamento estourado: 0x | erros de leitura: 0
Idade das amostras: média 521 ms | mais antiga 1033 ms (vence em 5000 ms)
Servidor: estável | TPS curto 19.98 | MSPT 9.2 | última travada 294 ms há 137 s
```

(Saída real do teste de carga local com 98 bots; ver [VALIDACAO.md](VALIDACAO.md).)

O que observar:

- **amostras/s** ≈ número de jogadores (1 por segundo cada).
- **mais antiga** deve ficar perto de 1000 ms. Se passar de 5000 ms, todos viram UNKNOWN (vencidos).
- **Orçamento estourado** > 0 com frequência: aumente `max-micros-per-tick` ou investigue a máquina.

## ZwoVulcanBridge — `/zvb`

Permissões: `zwovulcanbridge.staff` (ver), `zwovulcanbridge.admin` (reload),
`zwovulcanbridge.alerts` (receber avisos de punições do Vulcan liberadas/bloqueadas).

- `/zvb status`: modo, Vulcan conectado, provedor, flags vistas, punições do Vulcan liberadas/bloqueadas por
  motivo (observação, instável, desconhecida, lag recente, sem provedor, proteção, Bedrock), quantas vezes o VL
  foi zerado e se o config do Vulcan tem comandos que banem IP.
- `/zvb jogador <nick>`: conexão que a ponte recebe, se uma punição do Vulcan **passaria agora**, proteção
  ativa (teleporte/respawn), flags com lag nesta sessão e as últimas decisões.
- `/zvb reload`: recarrega o config e confere de novo os comandos do Vulcan.

Cada punição do Vulcan (nunca cada flag) vira uma linha em `plugins/ZwoVulcanBridge/punicoes.log`, no máximo uma
por jogador e check a cada 30 s:

```
[2026-11-04 22:43:40] Punição do Vulcan LIBERADA: zfs0 (30dad699-...) — motion (E) VL 3/3 — conexão STABLE/OK — modo ENFORCE
```

## Problemas comuns

| Sintoma | Causa provável |
| --- | --- |
| `Provedor de conexão: nenhum provedor registrado` | ZwoPingState ausente/desligado → **todas as punições do Vulcan são bloqueadas** |
| Aviso "comando(s) que podem banir IP" | troque `mpban`/`ban-ip` por `mpbanperfil %player% <motivo>` no config do Vulcan |
| Jogador voando/andando livre com ping alto | com `filter.cancel-flags: true` o Vulcan provavelmente não dá setback nessas flags; deixe `false` |
| Todos UNKNOWN / "dados vencidos" | travada longa ou coleta parada; veja `/pingstate stats` |
| Todos UNSTABLE / "servidor instável" | TPS/MSPT ruins ou travadas recentes; veja a linha "Servidor" |
| Jogadores desconectados com "Invalid packet" | packetevents incompatível com o Vulcan (não é destes plugins) |
