# ZwoPingState + ZwoVulcanBridge

Plugins para **Paper 1.21.11 / Java 21** do servidor **Serpens New Era** (Velocity + Geyser + Floodgate).
Objetivo: punição automática do anticheat **só com evidência forte**, nunca por lag, e com custo baixo.

| Plugin | Papel |
| --- | --- |
| **ZwoPingState 1.0.0** | Responde, por UUID, `STABLE`, `UNSTABLE` ou `UNKNOWN` sobre a conexão do jogador. **Não pune ninguém.** |
| **ZwoVulcanBridge 3.0.0** | **Quem decide é a ponte.** Lê as flags do Vulcan, junta evidências de checks aprovados e aplica a sequência por UUID: **kick → kick (outra sessão) → ban temporário de 24 h só do perfil**, registrado pelo ModPanel. Começa em **observação**. |
| **ModPanel 3.1.0** | Painel de moderação. A 3.1.0 adiciona a `ProfileBanApi`: ban temporário por UUID com resultado conferido, **sem IP**. |

```
Vulcan (punishable: false) ──VulcanFlagEvent──▶ ZwoVulcanBridge
                                                  │ O(1): conexão STABLE? (ZwoPingState) Java confirmado? sem proteção?
                                                  │       check aprovado? VL >= min-vl?  → guarda a flag (memória fixa)
                                                  │ >= 2 famílias, cada check repetido min-flags vezes na janela
                                                  ▼ = OCORRÊNCIA (no máximo 1 por sessão)
                                  thread do servidor: revalida conexão, proteção, sessão
                                                  │ OBSERVE → só registra "teria aplicado"
                                                  ▼ ENFORCE → 1º kick / 2º kick / ban 24h via ProfileBanApi
                                  histórico por UUID (historico.yml, gravado fora da thread do servidor)
```

- Instalação, ativação e rollback: [docs/INSTALACAO.md](docs/INSTALACAO.md)
- Diagnóstico (`/pingstate`, `/zvb`, `decisoes.log`, `historico.yml`): [docs/DIAGNOSTICO.md](docs/DIAGNOSTICO.md)
- O que foi testado, custo medido e o que ficou pendente: [docs/VALIDACAO.md](docs/VALIDACAO.md)
- Configurações comentadas: [ZwoPingState](zwo-ping-state/src/main/resources/config.yml) ·
  [ZwoVulcanBridge](zwo-vulcan-bridge/src/main/resources/config.yml)

## Como a ponte decide

**Evidência (caminho de cada flag, O(1), na thread em que o Vulcan dispara):**

1. Conexão diferente de `STABLE`, ou provedor ausente → **descarta toda a sequência** do jogador.
2. Sessão que já teve ocorrência → ignora (no máximo uma por sessão).
3. Bedrock → fora da automação.
4. Check não aprovado no `config.yml` ou VL abaixo do `min-vl` → não conta.
5. Proteção de teleporte, respawn ou troca de mundo ativa → não conta.
6. Senão, guarda a flag no anel daquele check (só as últimas `min-flags`, máx. 32).
7. Ocorrência = pelo menos `min-families` (mínimo 2) famílias com um check que repetiu `min-flags` vezes
   dentro de `window-seconds`. Uma flag isolada, ou várias do mesmo check/família, nunca bastam.

Sem I/O, SQL, rede, varredura de jogadores nem tarefa agendada por flag. Só a ocorrência agenda **uma** tarefa.

**Ação (thread do servidor):**

1. Confere que o jogador ainda está na **mesma sessão**, e revalida conexão e proteção. Se falhar, descarta.
2. Conta não confirmada como Java (Floodgate) → só registro para revisão. Prefixo do nome não prova Java.
3. `OBSERVE` → registra o que **teria** feito, sem tocar no histórico real.
4. Automação bloqueada (sem checks, Vulcan, provedor ou ModPanel 3.1) → registro para revisão, com o motivo.
5. Senão, aplica o próximo passo do histórico do UUID:
   - **1º kick** e **2º kick** (em outra sessão): só contam quando a desconexão é confirmada como resultado da
     ponte (`PlayerQuitEvent` com motivo KICKED depois do kick não cancelado). Kick cancelado, saída
     voluntária e timeout não contam.
   - **Ban temporário** (`sequence.ban-duration`, padrão 24h) pela `ProfileBanApi`. Só marca como aplicado com
     resposta `APPLIED` conferida pelo ModPanel, sem IP. Falha, timeout ou resposta incerta → **revisão**, sem
     nova tentativa automática.
6. O histórico vale `sequence.validity` (7 dias) a partir do 1º kick.

O Vulcan só detecta: todos os checks com `punishable: false`. Se algum ainda punir, a ponte cancela a punição
e avisa no console.

## Compilar

```bash
./gradlew release
```

Gera `build/release/ZwoVulcanBridge-3.0.0.jar` e `build/release/ZwoPingState-1.0.0.jar`. Os JARs prontos estão em
[`dist/`](dist). O ModPanel 3.1.0 é compilado no repositório dele (`mvn package`); o JAR dele também está em
`dist/` por conveniência.

A ponte compila contra uma cópia da interface `dev.zwo.modpanel.api` (source set `modpanelApi`), que **não vai
no JAR**: em execução as classes vêm do próprio ModPanel.

## Estrutura

```
zwo-vulcan-bridge/   API br.com.serpens.vulcanbridge.api (PingStateProvider...)
                     + escalada (core/ sem Bukkit, testável) + integração Vulcan/ModPanel/Floodgate
zwo-ping-state/      plugin do provedor (core/ sem Bukkit, testável)
docs/                instalação, diagnóstico, validação
dist/                JARs prontos desta versão
```
