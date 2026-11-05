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

Permissões: `zwovulcanbridge.staff` (ver), `zwovulcanbridge.admin` (reload/limpar),
`zwovulcanbridge.alerts` (receber avisos).

- `/zvb status`: modo, Vulcan conectado e por qual evento, provedor, contadores (flags recebidas, descartadas
  por conexão/proteção, ocorrências, punições internas do Vulcan bloqueadas), e se o ban automático está ativo.
- `/zvb jogador <nick>`: conexão que a ponte recebe, sequência atual (x/3), proteção ativa (teleporte/respawn),
  último motivo de descarte, última ocorrência, kicks válidos.
- `/zvb strikes <nick|uuid>`: histórico por perfil (também offline).
- `/zvb limpar <nick|uuid>`: apaga o histórico (fica registrado no `ocorrencias.log`).
- `/zvb reload`: recarrega o config (troca de evento do Vulcan refaz o hook).

## Problemas comuns

| Sintoma | Causa provável |
| --- | --- |
| `Flags recebidas: 0` com o Vulcan alertando | `listen-event: POST_FLAG` no Vulcan 2.9.7.29 → use `FLAG` |
| `Provedor de conexão: nenhum provedor registrado` | ZwoPingState ausente/desligado → **todas as flags são descartadas** |
| Todos UNKNOWN / "dados vencidos" | travada longa ou coleta parada; veja `/pingstate stats` |
| Todos UNSTABLE / "servidor instável" | TPS/MSPT ruins ou travadas recentes; veja a linha "Servidor" |
| Jogadores desconectados com "Invalid packet" | packetevents incompatível com o Vulcan (não é destes plugins) |
