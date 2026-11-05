# ZwoPingState + ZwoVulcanBridge

Plugins para **Paper 1.21.11 / Java 21** do servidor **Serpens New Era**. Objetivo: impedir que lag vire punição
injusta do anticheat, com custo baixo no servidor.

| Plugin | Papel |
| --- | --- |
| **ZwoPingState** | Responde, por UUID, `STABLE`, `UNSTABLE` ou `UNKNOWN` sobre a conexão do jogador. **Não pune ninguém.** |
| **ZwoVulcanBridge** | Recebe as flags do Vulcan e só as avalia quando a conexão é `STABLE`. Escada: kick → kick (outra sessão) → ban **por perfil** na 3ª ocorrência válida. **Nunca bane IP.** Começa em modo **observação**. |

```
Vulcan ──flag──▶ ZwoVulcanBridge ──connection(UUID)──▶ ZwoPingState (O(1), memória)
                    │  STABLE?  não → descarta a sequência de flags
                    │  sim → flag alta e repetida? → ocorrência
                    ▼
             OBSERVE: só registra/avisa   |   ENFORCE: kick, kick, ban por perfil
```

- Instalação: [docs/INSTALACAO.md](docs/INSTALACAO.md)
- Diagnóstico (`/pingstate`, `/zvb`): [docs/DIAGNOSTICO.md](docs/DIAGNOSTICO.md)
- O que foi testado e o que ficou pendente: [docs/VALIDACAO.md](docs/VALIDACAO.md)
- Configurações comentadas: [ZwoPingState](zwo-ping-state/src/main/resources/config.yml) ·
  [ZwoVulcanBridge](zwo-vulcan-bridge/src/main/resources/config.yml)

## Como o ZwoPingState decide

- **Uma única tarefa** síncrona por tick, com **orçamento fixo**: no máximo `max-players-per-tick` jogadores
  lidos por tick e um limite de tempo por tick. Os jogadores são lidos em rodízio: com 100 jogadores e
  intervalo de 20 ticks, ~5 leituras por tick; cada jogador é lido 1×/s.
- Lê `Player#getPing()` na thread do servidor. No Paper 1.21.11 esse valor é a **média dos keep-alives dos
  últimos 5 s** (um keep-alive por segundo), conferido no bytecode do servidor.
- Janela circular fixa por jogador (20 amostras). Métricas: último, média, mín/máx, jitter (diferença média
  entre amostras), amplitude (máx − mín) e picos.
- Motivos separados: ping alto, picos, ping oscilando, servidor instável, em recuperação, dados insuficientes,
  dados vencidos, Bedrock, falha na coleta.
- **Recuperação contínua**: depois de qualquer instabilidade, só volta a `STABLE` após `recovery.player-seconds`
  seguidos dentro dos limites. Uma amostra boa não libera nada.
- **Servidor**: TPS curto (medido pela própria tarefa), MSPT médio dos últimos 20 ticks (duração que o Paper informa
  no fim de cada tick, somada em janela fixa; sem chamar `getAverageTickTime`) e marcador global
  de travada (um tick longo). Nunca percorre jogadores para isso.
- **Consulta O(1)**: um `get` no mapa + leitura volátil + idade + saúde global. Não percorre a janela, não cria
  tarefa, não chama Bukkit. Dados mais velhos que `stale-after-seconds` = `UNKNOWN`.
- Entrada/reconexão começa em `UNKNOWN`. Bedrock = `UNKNOWN` (o Paper vê o ping do Geyser).
- Sem banco, HTTP, DNS, ICMP, injeção de pacotes, escrita em disco ou log no caminho de coleta/consulta.

## Como a ponte decide

1. Bedrock → ignorado (nunca punido automaticamente).
2. Check não punível no Vulcan ou ignorado no config → ignorado.
3. Proteção de teleporte/respawn/troca de mundo → sequência descartada.
4. Conexão diferente de `STABLE` **ou provedor ausente/desligado** → sequência descartada.
5. Flag "alta" = VL ≥ maxVl do check no Vulcan (`min-vl-ratio: 1.0`). 3 flags altas em 60 s → ocorrência.
6. No máximo uma ocorrência por sessão. Kicks só contam em sessões distintas. O modo observação nunca
   alimenta o modo de punição.
7. As punições internas do Vulcan são bloqueadas (`cancel-vulcan-punishments: true`) para que tudo passe
   por aqui.

> **Atenção:** o `/mpban` do ModPanel/SerpensModPanel também bane o último IP. A ponte recusa esse comando.
> Sem um comando de ban somente por perfil, a 3ª ocorrência fica **pendente para revisão manual**.

## Compilar

```bash
./gradlew release
```

Gera `build/release/ZwoVulcanBridge-1.0.0.jar` e `build/release/ZwoPingState-1.0.0.jar`. Os JARs já compilados
desta versão estão em [`dist/`](dist). O ZwoPingState
compila contra o JAR da ponte (`compileOnly`) e **não embute** a interface `PingStateProvider`.

## Estrutura

```
zwo-vulcan-bridge/   API br.com.serpens.vulcanbridge.api (PingStateProvider, ConnectionState...)
                     + plugin da ponte (core/ sem Bukkit, testável)
zwo-ping-state/      plugin do provedor (core/ sem Bukkit, testável)
docs/                instalação, diagnóstico, validação
dist/                JARs prontos desta versão
```
