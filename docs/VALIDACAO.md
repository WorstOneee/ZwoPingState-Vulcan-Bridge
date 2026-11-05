# Validação: o que foi testado e o que ficou pendente

Data: 04/11/2026. Versões: Paper 1.21.11, Java 21, Vulcan 2.9.7.29, packetevents 2.14.0, spark-paper 1.10.152
(o embutido no Paper).

**Resumo honesto:** a lógica foi testada com testes automatizados e com Vulcan real num servidor Paper local. O
custo foi medido com 98–100 bots. Os bots rodaram **na mesma máquina** que o servidor, e **não houve jogadores
reais nem Bedrock/Geyser**. O impacto não é zero: é pequeno e ficou abaixo do ruído do MSPT neste ambiente.

## 1. Testes automatizados (56, todos passando)

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

**ZwoVulcanBridge 2.0** (`GateTest`, `BanCommandGuardTest`). Quem pune é o Vulcan; os testes cobrem o filtro:

- ENFORCE com conexão STABLE: a punição do Vulcan passa.
- UNSTABLE, UNKNOWN e provedor ausente: a punição é cancelada.
- UNKNOWN logo após entrar bloqueia na hora, mas não conta como "lag recente".
- Flag com lag nos últimos 120 s bloqueia a punição mesmo já STABLE.
- Por padrão a flag com lag segue no Vulcan. Com `cancel-flags: true`, ela é cancelada.
- OBSERVE cancela toda punição ("teria passado") e nunca mexe nas flags.
- Bedrock nunca é punido. Proteção de teleporte/respawn bloqueia.
- O VL só é zerado em ENFORCE e só em bloqueios de lag, proteção ou Bedrock.
- Log e aviso limitados a 1 por check a cada 30 s.
- Reconhece comandos que banem IP (`mpban`, `mptempban`, `mpipban`, `ban-ip`...) e não marca `mpbanperfil`,
  `ban` nem `kick`.

## 2. Testes funcionais no servidor local (Vulcan real)

Servidor Paper 1.21.11 local (`online-mode=false`), bots mineflayer 4.39.0. A latência foi simulada atrasando as
respostas de keep-alive, em fila e na ordem certa.

- **Transições de estado** com bots nos perfis estável, ping alto (~300 ms), picos (650 ms por 1,5 s a cada
  30 s), jitter aleatório (20–280 ms) e "lagspike". Cada um foi para o motivo certo e voltou a `STABLE` só depois
  da recuperação contínua. O perfil de jitter aleatório inicialmente aparecia como `STABLE`, porque a média de
  5 s do Paper o suavizava. Daí veio o limite de **amplitude** (`max-range: 60`), e o caso foi retestado.
- **Servidor instável:** a entrada em massa de bots causou travadas (294–403 ms). Todos foram para `servidor
  instável` e voltaram após 10 s.
- **Provedor desligado (ponte 1.0):** só a ponte instalada, sem o ZwoPingState. Com um bot trapaceiro, foram 65
  flags recebidas, todas descartadas, e 0 ocorrências. `/zvb jogador` mostrou "UNKNOWN — provedor de estado
  indisponível". Na 2.0 o mesmo caso (`PROVIDER_UNAVAILABLE`) bloqueia a punição; isso foi coberto por teste
  automatizado e não repetido no servidor.

### Ponte 2.0: filtro das punições do Vulcan (ENFORCE só no servidor local)

Vulcan com os `punishment-commands` trocados para `mpbanperfil %player% [Vulcan] Unfair Advantage` e ModPanel com o
comando novo `/mpbanperfil`. Um `mpban` foi colocado de propósito na seção Judgement Day do config do Vulcan.

| Cenário | Resultado |
| --- | --- |
| Ligar o servidor | Aviso no console e no `/zvb status`: "o config do Vulcan tem 1 comando que pode banir IP: mpban ..." |
| A. Trapaceiro com ping alto (~300 ms) | 4 punições do Vulcan (speed B/C/D/E) **bloqueadas por "conexão instável"**, VL zerado; não foi banido |
| B. Trapaceiro com conexão estável | 1ª punição bloqueada (UNKNOWN logo após entrar). A seguinte foi **LIBERADA**: o Vulcan executou `/mpbanperfil`, o perfil foi banido e o `banned-ips.json` ficou **vazio** |
| D. Lag (~300 ms) e depois estável | Bloqueada enquanto UNSTABLE (ping alto, oscilação, recuperação). Depois de STABLE, bloqueada por **"lag recente"** (VL contaminado) durante a janela de 120 s |
| C. OBSERVE, trapaceiro estável | Punições bloqueadas como **"observação; teria passado"**; ninguém punido |

No total foram 21 punições do Vulcan: 1 liberada e 20 bloqueadas. O VL foi zerado 13 vezes, com 0 erros de
leitura e **0 IPs banidos**.

- **Correção feita durante o teste:** a primeira versão contava como "lag recente" as flags do período `UNKNOWN`
  logo após entrar. O trapaceiro estável ficava 2 min sem poder ser punido. Agora só flags com conexão
  `UNSTABLE` contam.
- **Não confirmado:** se cancelar a flag (`cancel-flags: true`) também cancela o setback do Vulcan. Os bots
  trapaceiros foram kickados por "flying" pelo vanilla com e sem o cancelamento, então o teste não diferencia os
  casos. Por isso o padrão é `false`.
- **Escada da ponte 1.0 (histórico):** antes da mudança de papel, a escada própria foi exercitada de ponta a ponta
  em ENFORCE local: KICK 1/2, KICK 2/2 e ban por `/mpbanperfil {uuid}`, sem banir IP.
- **Descoberta:** no Vulcan 2.9.7.29 o `VulcanPostFlagEvent` não foi disparado. A 2.0 usa `VulcanFlagEvent` e
  `VulcanPunishEvent`, os dois canceláveis.

### ModPanel: `/mpbanperfil`

Paper local com SQLite e dois bots no mesmo IP:

- `/mpbanperfil zpa0 ...` baniu só o perfil, e o `banned-ips.json` ficou vazio.
- O outro bot no mesmo IP continuou online.
- O banco ficou com uma única linha `PROFILE`, sem linha de IP.
- `/mpunban zpa0` removeu o ban.

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

> A carga foi medida com a ponte 1.0. A 2.0 faz menos trabalho por flag (um `get` no mapa e uma leitura do
> provedor, sem janela nem sequência) e não foi medida de novo sob carga.

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
- **Modo ENFORCE em produção:** foi exercitado só no servidor local, com bots. **Não habilite em produção** antes
  de rodar em observação e revisar o `punicoes.log`.
- **Ban por perfil:** o `/mpbanperfil` está no branch `feat/ban-somente-perfil` do ModPanel e ainda precisa ser
  revisado, mesclado e instalado. Até lá, nos `punishment-commands` do Vulcan, use `minecraft:ban %player% <motivo>`
  (só perfil) ou mantenha `kick`.
- **Profiler sem viés:** repetir a medição em Linux com o async-profiler do Spark daria um número mais fiel que
  o sampler Java do Windows.

## 5. Como repetir no seu servidor de teste

1. Instale os dois JARs (ver [INSTALACAO.md](INSTALACAO.md)) e deixe `mode: OBSERVE`.
2. Com carga normal, rode `/spark profiler start` por 5–10 min, depois `/spark profiler stop`.
3. Compare `/mspt` e o perfil com e sem os dois plugins, sob carga parecida (mesmo horário/número de jogadores).
4. Acompanhe `/pingstate stats`. O esperado: "Orçamento estourado: 0x", "mais antiga" perto de 1000 ms e
   amostras/s ≈ jogadores online.
