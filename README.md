# ZwoPingState + ZwoVulcanBridge

Plugins para **Paper 1.21.11 / Java 21** do servidor **Serpens New Era**. Objetivo: impedir que lag vire punição
injusta do anticheat, com custo baixo no servidor.

| Plugin | Papel |
| --- | --- |
| **ZwoPingState** | Responde, por UUID, `STABLE`, `UNSTABLE` ou `UNKNOWN` sobre a conexão do jogador. **Não pune ninguém.** |
| **ZwoVulcanBridge** | Filtro de lag: **quem pune é o Vulcan**; a ponte só deixa a punição do Vulcan passar quando a conexão é `STABLE` e cancela nos outros casos (lag, sem provedor, Bedrock, teleporte/respawn). Avisa se o Vulcan estiver configurado com comando que bane IP. Começa em modo **observação** (bloqueia tudo e só registra). |

```
Vulcan ──VL máximo──▶ VulcanPunishEvent ──▶ ZwoVulcanBridge ──connection(UUID)──▶ ZwoPingState (O(1), memória)
                                              │ STABLE, sem lag recente, Java, sem proteção?
                                              │   não → cancela a punição (e zera o VL do check)
                                              ▼   sim → OBSERVE: cancela e registra "teria passado"
                                                        ENFORCE: o Vulcan executa os punishment-commands
                                                                 (ex.: /mpbanperfil %player% ... = ban só por perfil)
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

1. Bedrock → punição do Vulcan cancelada (nunca punido automaticamente). As flags seguem para a staff ver.
2. Proteção de teleporte/respawn/troca de mundo → cancelada.
3. Conexão diferente de `STABLE` **ou provedor ausente/desligado** → cancelada.
4. Flag com lag nos últimos `filter.recent-lag-seconds` (120 s) → cancelada: o VL foi inflado por lag.
5. Senão: em `ENFORCE` a punição do Vulcan passa; em `OBSERVE` (padrão) é cancelada e registrada como "teria
   passado" em `punicoes.log`.
6. Ao cancelar por lag/proteção/Bedrock em `ENFORCE`, o VL do check é zerado no Vulcan.
7. As flags **não** são canceladas por padrão (`filter.cancel-flags: false`): a flag segue inteira no Vulcan
   (alerta e setback) e só a punição é filtrada. Cancelar a flag provavelmente também cancela o setback, e quem
   forja lag andaria livre (não confirmado no teste local).

> **Ban só por perfil:** o `/mpban` do ModPanel também bane o último IP. Nos `punishment-commands` do Vulcan use
> `/mpbanperfil %player% <motivo>` (comando novo do ModPanel). A ponte avisa se achar comandos de IP no config
> do Vulcan.

## Compilar

```bash
./gradlew release
```

Gera `build/release/ZwoVulcanBridge-2.0.0.jar` e `build/release/ZwoPingState-1.0.0.jar`. Os JARs já compilados
desta versão estão em [`dist/`](dist). O ZwoPingState
compila contra o JAR da ponte (`compileOnly`) e **não embute** a interface `PingStateProvider`.

## Estrutura

```
zwo-vulcan-bridge/   API br.com.serpens.vulcanbridge.api (PingStateProvider, ConnectionState...)
                     + filtro de punições do Vulcan (core/ sem Bukkit, testável)
zwo-ping-state/      plugin do provedor (core/ sem Bukkit, testável)
docs/                instalação, diagnóstico, validação
dist/                JARs prontos desta versão
```
