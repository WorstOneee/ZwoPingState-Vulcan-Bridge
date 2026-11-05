# Validação: o que foi testado e o que ficou pendente

Data: 05/11/2026. Versões: Paper 1.21.11, Java 21, Vulcan 2.9.7.29, packetevents 2.14.0, Floodgate 2.2.5 (b140),
ModPanel 3.1.0 (SQLite), ZwoPingState 1.0.0, ZwoVulcanBridge 3.0.0, spark-paper 1.10.152 (o embutido no Paper).

**Resumo honesto:** a lógica foi testada com testes automatizados e com o Vulcan real num servidor Paper local
(offline-mode), com bots mineflayer na mesma máquina. **Não houve jogadores reais, clientes Bedrock, Velocity nem
MySQL.** O custo foi medido com ~100 bots. O impacto não é zero, mas o da ponte ficou abaixo do que o Spark
consegue enxergar neste ambiente. **Nada foi ativado em produção.**

## 1. Testes automatizados (65, todos passando)

`./gradlew test`. O núcleo dos dois plugins não depende do Bukkit, e os cenários rodam com relógio simulado.

**ZwoPingState** (`PingTrackerTest`, `PingWindowTest`, `CollectorCostTest`, `BridgeProviderTest`):

| Cenário pedido | Resultado esperado e verificado |
| --- | --- |
| Entrada sem histórico | `UNKNOWN / dados insuficientes` até 8 amostras, depois `STABLE` |
| Ping alto constante | `UNSTABLE / ping alto` |
| Picos de ping | `UNSTABLE / picos`, depois **recuperação contínua** antes de `STABLE`; uma amostra boa não libera |
| Ping oscilando | `UNSTABLE / ping oscilando` (jitter e também amplitude, por causa da média de 5 s do Paper) |
| Queda de TPS | todos `UNSTABLE / servidor instável`, com recuperação de 10 s |
| MSPT alto | `servidor instável` (causa `HIGH_MSPT`) |
| Travada isolada | um tick de 600 ms marca o marcador global, sem percorrer jogadores |
| Amostras vencidas | sem coleta por mais de 5 s → `UNKNOWN / dados vencidos` |
| Travada longa | a janela recomeça; dados velhos não são reaproveitados |
| Reconexão | começa de novo em `UNKNOWN`, sem herdar a janela |
| Bedrock | `UNKNOWN / Bedrock` |
| Falha ao ler o ping | `UNKNOWN / falha na coleta` |
| Provedor desligado | `BridgeProviderTest`: depois de desligar, a ponte recebe `UNKNOWN` |
| Orçamento | com 100 jogadores, no máximo 5 leituras por tick e cada um lido 1×/s; com muitos, o lote é limitado |

**ZwoVulcanBridge 3.0** (`EscalationTest` 19, `LadderTest` 6, `BanCommandGuardTest` 15):

| Cenário | Verificado |
| --- | --- |
| Duas famílias completas | gera **uma** ocorrência, com o resumo das evidências |
| Flag isolada / muitas flags do mesmo check / checks da mesma família | nunca geram ocorrência |
| VL abaixo do mínimo, check não aprovado, tipo não aprovado (`killaura:b`) | não contam |
| `min-flags` por check | cada check aprovado precisa se repetir |
| Lag no meio da sequência | flag com UNSTABLE descarta tudo; a sequência recomeça do zero |
| Provedor ausente / status nulo | descarta (`PROVIDER_UNAVAILABLE`) |
| Proteção (teleporte/respawn/mundo) | flags não contam |
| Bedrock | excluído. Conta incerta junta evidências, mas a ação vira revisão (no plugin) |
| Janela | evidência velha sai; `min-flags` precisa caber na janela |
| Uma ocorrência por sessão | segunda sequência na mesma sessão é ignorada |
| Check com centenas de flags | não apaga as evidências do outro check (memória fixa por check) |
| Reload | descarta evidências das regras antigas; política vazia nunca dispara |
| Flags simultâneas | 200 rodadas × 8 threads: exatamente 1 ocorrência por sessão |
| Sequência | kick 1 → kick 2 (outra sessão) → ban. Nunca duas na mesma sessão. Depois do ban (PENDING/REVIEW/APPLIED), só revisão |
| Validade | conta do 1º kick; vencida, recomeça no kick 1 |
| Config | `min-families` forçado em 2; duplicatas, `min-vl` 0 e tipo inválido viram problema listado |

## 2. Testes funcionais no servidor local (Vulcan real)

A latência foi simulada atrasando as respostas de keep-alive dos bots. Os bots "trapaceiros" andam 0,7
bloco/tick, o que dispara speed e motion/strafe no Vulcan. Os checks aprovados no teste foram `speed`, `motion` e
`strafe`, com VL e repetições baixos, só para gerar ocorrências rápido. Um plugin auxiliar **só local**
(`ZvbTestHelper`) cancelou kicks, teleportou e deu permissão de protegido.

### ZwoPingState

- **Transições de estado** com bots nos perfis estável, ping alto (~300 ms), picos (650 ms por 1,5 s a cada
  30 s), jitter aleatório (20–280 ms) e "lagspike". Cada um foi para o motivo certo e voltou a `STABLE` só depois
  da recuperação contínua. O perfil de jitter aleatório inicialmente aparecia como `STABLE`, porque a média de
  5 s do Paper o suavizava. Daí veio o limite de **amplitude** (`max-range: 60`), e o caso foi retestado.
- **Servidor instável:** a entrada em massa de bots causou travadas (294–403 ms). Todos foram para `servidor
  instável` e voltaram após 10 s.

### ZwoVulcanBridge 3.0

| Cenário pedido | Resultado |
| --- | --- |
| Vulcan com todos os checks `punishable: false` | o `VulcanFlagEvent` continua chegando: **364 flags, 0 punições do Vulcan** |
| OBSERVE (padrão) | `[OBSERVE] ... teria aplicado: 1º kick`. Ninguém foi kickado e o `historico.yml` ficou **intocado** |
| Sem Floodgate | conta "incerta": só registro para revisão, nenhum kick |
| Bedrock (prefixo `.` com Floodgate) | conta Bedrock: 0 flags aprovadas, nenhuma ocorrência |
| Teleporte a cada 0,5 s | 51 flags ignoradas pela proteção; nenhuma ocorrência |
| Ping alto desde a entrada | nenhuma evidência (UNSTABLE descarta) |
| **Lag no meio da sequência** (`min-flags` 30) | 59 evidências, depois UNSTABLE/oscilação: **descartadas**. Depois de estabilizar, nova sequência e kick 1 |
| Revalidação | servidor instável no momento de agir: ocorrência **DESCARTADA na revalidação** |
| Provedor ausente (sem ZwoPingState) | flags com UNKNOWN/provedor: 0 evidências; o status lista o bloqueio |
| **Reconexão** | kick 1; reconectou; kick 2 em outra sessão. Os dois **CONFIRMADOS** |
| **Reinício** | histórico persistido (2 UUIDs) e usado depois de reiniciar |
| **Kick cancelado** por outro plugin | "kick 2 cancelado: não contou". Histórico sem mudança; sessão encerrada |
| **Falha do ModPanel** (alvo protegido) | `REJECTED`: ban em **revisão**. A próxima ocorrência é só revisão, **sem nova tentativa** |
| ModPanel 3.0.0 (sem API) | "ModPanel: INCOMPATÍVEL", automação **BLOQUEADA**; a ocorrência é registrada para revisão, com o motivo |
| Sem ModPanel | automação bloqueada, com o motivo no `/zvb status` |
| **Fluxo completo** | kick 1 → kick 2 (outra sessão) → "ban temporário APLICADO e conferido (APPLIED #5)" |

Conferência do fluxo completo:

- `banned-ips.json` = `[]` (**nenhum IP banido**).
- `banned-players.json` com o perfil, origem `ZwoVulcanBridge` e expiração em +24h. Ao reconectar, o jogador vê
  "Você foi banido temporariamente".
- Banco do ModPanel, linha 5: escopo `PROFILE`, `target_ip` NULL, ator `ZwoVulcanBridge`, origem `API`, status
  `ACTIVE`, 86 400 000 ms, `legacy_key` `api:zvb-<uuid>-<1º kick>`. Nenhuma linha para o pedido recusado.

## 3. Custo sob carga (~100 bots)

Ponte 3.0 em OBSERVE, com 3 checks aprovados. Carga: 100 bots (sem física) e 2 trapaceiros com física, com
ModPanel 3.1, Floodgate e o plugin auxiliar. `spark profiler --interval 4` por 3 min depois do aquecimento, salvo
em arquivo local e lido por script (nada enviado ao spark.lucko.me).

| Medida | Execução 1 (thread do servidor) | Execução 2 (`--thread *`, todas as threads) |
| --- | --- | --- |
| TPS | 20,0 | 20,0 |
| `/mspt` (faixa das leituras) | 13,4–15,3 ms | 13,1–14,5 ms |
| Spark: MSPT mediana | 14,20 ms | 12,35 ms |
| Flags do Vulcan / aprovadas | 12 548 / 2 689 | 11 780 / 1 388 |
| `br.com.serpens.vulcanbridge`, thread do servidor | 4 ms de 181 s (0,0022%) | 0 ms amostrado |
| `br.com.serpens.vulcanbridge`, threads Netty (onde o Vulcan dispara as flags) | não medido | **0 ms amostrado** em 725 s de 4 threads |
| `dev.zwo.pingstate`, thread do servidor | 144 ms (0,08%) | 88 ms (0,05%) |
| Vulcan, thread do servidor / Netty | 1 708 ms (0,94%) / não medido | 372 ms (0,21%) / 30 728 ms (4,24%) |

**Como ler:**

- "0 ms amostrado" **não é custo zero**. Com uma amostra a cada 4 ms, trechos muito curtos não aparecem. O que dá
  para afirmar é que o caminho da flag ficou abaixo da resolução do Spark, enquanto o Vulcan, no mesmo caminho,
  apareceu com 4,2%. Esse caminho é um `get` no mapa, uma consulta O(1) ao provedor e uma escrita num anel fixo.
- **O MSPT não serve para comparar versões.** A medição antiga (ponte 2.0, sem trapaceiros com física, sem
  ModPanel/Floodgate) deu mediana 9,76 ms; a linha de base sem plugins, 11,6 ms. As condições não são
  equivalentes (bots com física, mais plugins, notebook dividindo a CPU com os bots), então a diferença não deve
  ser atribuída à ponte.
- No Windows o Spark usa o sampler Java (amostra em *safepoints*), que distorce trechos curtos. Veja a nota do
  ZwoPingState abaixo.

**Medição anterior do ZwoPingState** (sem mudança de código desde então): com 98 bots, `/pingstate stats` mediu
3,8–8,6 µs por tick (pior tick 169 µs, orçamento nunca estourado, amostras com ~1 s de idade). O Spark atribuiu
mais (0,37% numa execução antiga, 0,05–0,08% nestas), concentrado num laço de 20 inteiros: provável viés de
safepoint.

## 4. O que **não** foi testado

- **Saída voluntária e timeout durante o kick:** não reproduzidos com bots no servidor. Pela lógica, o kick só
  conta se foi aceito **e** o `PlayerQuitEvent` vier com motivo `KICKED`; o resto é registrado como "não
  confirmado".
- **Clientes Bedrock reais / Geyser:** só foram testados o caminho do Floodgate com nome de prefixo e os testes
  automatizados. Nenhum cliente Bedrock conectou.
- **Velocity e contas Java reais (online-mode):** não testado. Com Velocity, o Floodgate precisa estar no Paper.
- **MySQL** no ModPanel com a API: só SQLite. Vários servidores no mesmo banco não foram testados com a API.
- **SerpensModPanel 2.0.0:** não tem a API; a ponte exige o ModPanel 3.1.0. O 3.1.0 importa os dados do 2.0.0,
  mas essa importação não foi refeita neste teste.
- **Rede real / 100 jogadores reais:** a latência foi simulada, e bots custam menos que jogadores.
- **Escolha dos checks aprovados:** os checks e VLs do teste servem só para gerar ocorrências e não são
  recomendação. A lista padrão é vazia.
- **ENFORCE em produção:** não foi ativado; só rodou no servidor local.
- **Profiler sem viés:** repetir em Linux com o async-profiler do Spark daria números mais fiéis.

## 5. Como repetir no seu servidor de teste

1. Instale como em [INSTALACAO.md](INSTALACAO.md), em `mode: OBSERVE`, com os checks que pretende aprovar.
2. Com carga normal, rode `/spark profiler start --thread * --interval 4` por 5–10 min e depois
   `/spark profiler stop`.
3. Procure `br.com.serpens.vulcanbridge` e `dev.zwo.pingstate` em todas as threads (as flags do Vulcan rodam nas
   threads Netty).
4. Acompanhe `/zvb status` (fila de gravação sem recusas nem falhas) e `/pingstate stats` ("Orçamento estourado:
   0x").
