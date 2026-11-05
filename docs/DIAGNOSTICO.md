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

Permissões: `zwovulcanbridge.staff` (ver), `zwovulcanbridge.admin` (reload e limpar),
`zwovulcanbridge.alerts` (receber o aviso de cada decisão). Todas `op` por padrão.

### `/zvb status`

- **Modo** (`OBSERVE`/`ENFORCE`) e **Automação**: `liberada` ou `BLOQUEADA — <motivos>` (sem checks aprovados,
  menos de 2 famílias, Vulcan desconectado, provedor indisponível, ModPanel ausente/incompatível).
- Checks aprovados, famílias e problemas do config.
- Vulcan: flags vistas, punições do Vulcan canceladas, erros de leitura.
- Provedor de conexão, ModPanel (`pronto — ModPanel 3.1.0 (API 1)`, `AUSENTE` ou `INCOMPATÍVEL — motivo`) e
  Floodgate.
- Contadores: flags aprovadas, descartes por conexão, flags em proteção, ocorrências, observação, revalidação,
  kicks enviados/confirmados/cancelados, bans pedidos/aplicados/em revisão.
- Histórico (UUIDs, validade) e fila de gravação (pendentes, recusadas, falhas).
- Avisos: checks do Vulcan com `punishable: true`, comandos do Vulcan que banem IP.

### `/zvb jogador <nick>`

Conexão que a ponte recebe agora, tipo de conta (Java confirmado / Bedrock / incerto, com o motivo), proteção
ativa, estágio da sessão (`IDLE`, `TRIGGERED`, `DONE`), evidências na janela, último descarte e a decisão desta
sessão.

### `/zvb historico <nick|uuid>`

Kicks confirmados, data do 1º kick e até quando vale, estado do ban (`NONE`, `PENDING`, `APPLIED`, `REVIEW`, com
o detalhe do ModPanel) e o **próximo passo** que seria aplicado.

### `/zvb limpar <nick|uuid>` e `/zvb reload`

`limpar` zera o histórico do UUID na ponte (não remove bans: use `/mpunban`). `reload` recarrega o config,
descarta as evidências em andamento e confere de novo o ModPanel e o config do Vulcan.

## `decisoes.log`

`plugins/ZwoVulcanBridge/decisoes.log`. Uma linha por **decisão**, nunca por flag, gravada fora da thread do
servidor. Exemplos reais do teste local:

```
[2026-11-05 02:04:03] zqc3 (6af5e353-...) — [OBSERVE] ocorrência válida; teria aplicado: 1º kick (kicks confirmados no histórico real: 0; nada foi aplicado) — evidências: speed x2 (VL até 11, família speed), motion x2 (VL até 3, família motion) — modo OBSERVE
[2026-11-05 02:05:47] zqe1 (3cd2bfb1-...) — kick 1 CONFIRMADO (desconexão pela ponte); kicks no histórico: 1; válido até ... — modo ENFORCE
[2026-11-05 02:20:28] zqi1 (077681e0-...) — [revisão] ocorrência válida, mas a automação está BLOQUEADA (ModPanel: INCOMPATÍVEL — ...); próximo passo seria: 1º kick — ...
[2026-11-05 02:29:24] zlx0 (6f4ad5b8-...) — ocorrência DESCARTADA na revalidação: conexão UNSTABLE/SERVER_UNSTABLE — ...
[2026-11-05 01:56:38] zqb1 (bd1485cd-...) — [revisão] ocorrência registrada só para revisão — conta incerto (só revisão) (Floodgate ausente: não dá para confirmar Java); ...
```

Procure por `[revisão]`: são os casos que precisam de alguém da staff (conta incerta, automação bloqueada, kick
cancelado, ban com falha/rejeitado/incerto).

## `historico.yml`

`plugins/ZwoVulcanBridge/historico.yml`, por UUID: `nome`, `kicks`, `primeiro-kick`, `ultimo-kick`,
`ultima-sessao`, `ban`, `ban-em`, `pedido`, `ban-detalhe`. Gravado em arquivo temporário e trocado de uma vez
(não fica pela metade). Ao ligar, entradas vencidas são removidas e um ban `PENDING` (servidor caiu no meio)
vira `REVIEW`. **Não edite com o servidor ligado**; use `/zvb limpar`.

## Problemas comuns

| Sintoma | Causa provável |
| --- | --- |
| `Automação: BLOQUEADA — nenhum check aprovado` | normal no config novo; aprove checks em `evidence.checks` |
| `ModPanel: INCOMPATÍVEL` | ModPanel anterior ao 3.1.0 (sem a `ProfileBanApi`); atualize o painel |
| `Provedor de conexão: nenhum provedor registrado` | ZwoPingState ausente/desligado → todas as flags são descartadas |
| Todas as ocorrências "conta incerta" | Floodgate não está no Paper (backend), ou a API dele falhou |
| "O Vulcan tentou punir N vez(es)" | algum check ainda está com `punishable: true` no config do Vulcan |
| Aviso "comando(s) que banem IP" | o config do Vulcan tem `mpban`/`ban-ip`...; com `punishable: false` não roda, mas convém remover |
| Ban "em revisão" | ModPanel recusou (alvo protegido), falhou ou não respondeu a tempo; confira no painel |
| Todos UNKNOWN / "dados vencidos" | travada longa ou coleta parada; veja `/pingstate stats` |
| Jogadores desconectados com "Invalid packet" | packetevents incompatível com o Vulcan (não é destes plugins) |
