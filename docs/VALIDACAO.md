# Validação: o que foi testado e o que ficou pendente

Data: 04/11/2026. Versões: Paper 1.21.11, Java 21, Vulcan 2.9.7.29, packetevents 2.14.0, spark-paper 1.10.152
(o embutido no Paper).

**Resumo honesto:** a lógica foi testada com testes automatizados e com Vulcan real num servidor Paper local. O
custo foi medido com 98–100 bots. Os bots rodaram **na mesma máquina** que o servidor, e **não houve jogadores
reais nem Bedrock/Geyser**. O impacto não é zero: é pequeno e ficou abaixo do ruído do MSPT neste ambiente.

## 1. Testes automatizados (51, todos passando)

`./gradlew test`. O núcleo dos dois plugins não depende do Bukkit, então os cenários rodam com relógio simulado.

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

**ZwoVulcanBridge** (`FlagEngineTest`, `StrikeLedgerTest`, `BanCommandGuardTest`): STABLE confirma ocorrência;
UNSTABLE, UNKNOWN e provedor ausente descartam a sequência; proteção de teleporte/respawn descarta; Bedrock nunca
entra na escada; kick → kick em **sessões distintas** → ban só na 3ª; dois kicks na mesma sessão contam como um;
strikes do modo observação nunca contam no modo de punição; strikes vencidos deixam de contar; comandos que
podem banir IP (`ban-ip`, `/mpban` etc.) são recusados.

## 2. Testes funcionais no servidor local (Vulcan real)

Servidor Paper 1.21.11 local (`online-mode=false`), bots mineflayer 4.39.0. A latência foi simulada atrasando as
respostas de keep-alive, em fila e na ordem certa.

- **Transições de estado** com bots nos perfis estável, ping alto (~300 ms), picos (650 ms por 1,5 s a cada
  30 s), jitter aleatório (20–280 ms) e "lagspike". Cada um foi para o motivo certo e voltou a `STABLE` só depois
  da recuperação contínua. O perfil de jitter aleatório inicialmente aparecia como `STABLE`, porque a média de
  5 s do Paper o suavizava. Daí veio o limite de **amplitude** (`max-range: 60`), e o caso foi retestado.
- **Servidor instável:** a entrada em massa de bots causou travadas (294–403 ms). Todos foram para `servidor
  instável` e voltaram após 10 s.
- **Vulcan → ponte (modo OBSERVE):**
  - Um bot trapaceiro com conexão estável gerou flags reais do Vulcan e uma ocorrência registrada: "teria
    recebido KICK 1/2". Ninguém foi punido.
  - Um bot trapaceiro com ping alto teve 16 flags descartadas por conexão e nenhuma ocorrência.
  - As punições internas do Vulcan foram bloqueadas.
- **Provedor desligado** (só a ZwoVulcanBridge instalada, sem o ZwoPingState): com um bot trapaceiro, foram 65
  flags recebidas, **65 descartadas por conexão e 0 ocorrências**. `/zvb jogador` mostrou "UNKNOWN — provedor
  de estado indisponível".
- **Descoberta:** no Vulcan 2.9.7.29 o `VulcanPostFlagEvent` não foi disparado (0 flags recebidas). Por isso o
  padrão é `listen-event: FLAG`.

## 3. Custo sob carga (100 bots)

Mesma máquina, mesmo mundo, mesmos plugins de base (Vulcan + packetevents) e o mesmo roteiro
(`spark profiler --interval 4` por 3 min, depois de ~2 min de aquecimento). A única diferença foi a presença dos
dois plugins Zwo. Os bots de carga rodavam sem física, em dois processos Node. Os perfis do Spark foram salvos
localmente (`--save-to-file`) e lidos por um script local; **nada foi enviado ao spark.lucko.me**.

| Medida | Com ZwoPingState + ZwoVulcanBridge | Sem os dois (linha de base) |
| --- | --- | --- |
| Bots online durante a medição | 98 (2 kickados pelo vanilla por "flying", bots sem física) | 100 |
| TPS | 20,0 | 20,0 |
| `/mspt` 5 s (média de 6 leituras) | 10,9 ms | 11,6 ms |
| `/mspt` 1 min (faixa) | 10,3–11,7 ms | 10,3–12,8 ms |
| Spark: MSPT mediana | 9,76 ms | 10,67 ms |
| Spark: MSPT máximo | 97,6 ms | 54,2 ms |
| Spark: `dev.zwo.pingstate` na thread do servidor | 668 ms de 181 s (**0,37%**) | — |
| Spark: `br.com.serpens.vulcanbridge` | 0 ms amostrado | — |
| Spark: Vulcan / packetevents | 5,4% / 3,0% | 2,0% / 1,2% |

**Medição própria da coleta** (`/pingstate stats`, `System.nanoTime` em volta da tarefa inteira):

| Medida | Valor |
| --- | --- |
| Custo médio por tick (janela de 1 min) | 3,8–8,6 µs |
| Pior tick observado | 169 µs |
| Orçamento estourado | 0 vezes |
| Lote | 5 jogadores/tick; ~94 amostras/s para 98 jogadores |
| Idade das amostras | média ~500–540 ms; **mais antiga ~1,0–1,04 s** (vencimento em 5 s) |

**Como ler esses números:**

- **O MSPT não separa o custo do plugin do ruído.** Sem os plugins, o MSPT ficou até um pouco *maior*. A
  variação entre execuções (bots e servidor dividindo a mesma CPU) é maior que o custo medido do plugin.
- **O Spark e a medição própria divergem.** O Spark atribui ~180 µs/tick ao ZwoPingState, quase tudo a
  `PingWindow.compute` (um laço de 20 inteiros). A medição própria dá ~4–9 µs/tick. No Windows o Spark usa o
  sampler Java, que só tira amostras em *safepoints*. Laços curtos contêm pontos de safepoint e "absorvem" as
  amostras de trechos vizinhos sem safepoint. Por isso o 0,37% é um **teto pessimista**, não o custo real.
- **O Vulcan/packetevents custou mais na execução com plugins.** Não é tempo da ponte: o Spark amostrou 0 ms em
  `br.com.serpens.vulcanbridge`, contando também as chamadas feitas de dentro do Vulcan. A causa provável é a
  variação de carga entre execuções (os bots sem física geram flags/correções) e o bloqueio das punições internas
  do Vulcan, que mantém os bots flagando no servidor. Isso não foi investigado a fundo.
- **Otimização feita a partir do Spark:** a primeira medição mostrou `Server#getAverageTickTime()` custando
  ~196 ms por 3 min (o Paper 1.21.11 recalcula estatísticas a cada chamada). O plugin agora soma a duração
  informada pelo `ServerTickEndEvent` numa janela fixa (O(1)), e essa chamada sumiu do perfil.

## 4. O que ficou pendente

- **Bedrock / Geyser / Floodgate:** não testado em servidor. O caminho de detecção (API do Floodgate,
  UUID com msb 0, prefixo do nome) e o `UNKNOWN / Bedrock` foram cobertos só por testes automatizados.
- **Contas Java reais (online-mode):** não testado. Todos os testes usaram bots offline-mode na mesma máquina.
- **Rede real:** latência e perda foram simuladas pelo atraso de keep-alive. Perda de pacote real não é medida
  pelo Paper; o plugin só vê o efeito dela no RTT.
- **100 jogadores reais:** a carga foi de 98–100 bots simples (sem física, parados). Jogadores de verdade custam
  mais ao servidor, mas não mudam o custo do ZwoPingState, que depende só do número de jogadores.
- **Modo ENFORCE:** os kicks/ban de verdade não foram exercitados no servidor, só nos testes automatizados.
  **Não habilite em produção** antes de rodar em observação e revisar o `ocorrencias.log`.
- **Ban por perfil:** o `/mpban` do SerpensModPanel também bane o IP e por isso é recusado. Até existir um
  comando de ban somente por perfil, a 3ª ocorrência fica pendente para revisão manual.
- **Profiler sem viés:** repetir a medição em Linux com o async-profiler do Spark daria um número mais fiel que
  o sampler Java do Windows.

## 5. Como repetir no seu servidor de teste

1. Instale os dois JARs (ver [INSTALACAO.md](INSTALACAO.md)) e deixe `mode: OBSERVE`.
2. Com carga normal, rode `/spark profiler start` por 5–10 min, depois `/spark profiler stop`.
3. Compare `/mspt` e o perfil com e sem os dois plugins, sob carga parecida (mesmo horário/número de jogadores).
4. Acompanhe `/pingstate stats`. O esperado: "Orçamento estourado: 0x", "mais antiga" perto de 1000 ms e
   amostras/s ≈ jogadores online.
