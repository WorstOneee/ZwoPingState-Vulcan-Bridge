# Instalação, ativação e rollback

Requisitos: **Paper 1.21.11**, **Java 21**, **Vulcan 2.9.7.29** com **packetevents compatível**, **ModPanel 3.1.0**
e, na rede com Geyser, **Floodgate** instalado no Paper (backend), não só no Velocity.

> O Vulcan 2.9.7.29 **não funciona com packetevents 2.12.1** (todo jogador sai com "Invalid packet").
> Use o packetevents 2.14.0 ou mais novo.

> **Não ative punições (`mode: ENFORCE`) em produção** sem antes passar pelo período de observação (item 6).

## 1. Arquivos

Estão em [`dist/`](../dist), separados (sem ZIP):

| JAR | Função |
| --- | --- |
| `ZwoVulcanBridge-3.0.0.jar` | Escalada do anticheat. Contém a API `PingStateProvider`. |
| `ZwoPingState-1.0.0.jar` | Provedor da qualidade de conexão (o mesmo 1.0.0 de antes). Não pune ninguém. |
| `ModPanel-3.1.0.jar` | Painel de moderação com a `ProfileBanApi` (ban temporário só do perfil). Substitui o SerpensModPanel 2.0.0 / ModPanel 3.0.0. |

Ordem de carregamento automática: ZwoPingState `softdepend: [ZwoVulcanBridge]`; a ponte
`softdepend: [Vulcan, floodgate, ModPanel]`.

## 2. Passo a passo

1. **Backup** de `plugins/ZwoVulcanBridge/`, `plugins/Vulcan/config.yml`, da pasta do painel
   (`plugins/SerpensModPanel/` ou `plugins/ModPanel/`) e do banco do painel, se for MySQL.
2. Pare o servidor (não use `/reload`).
3. **ModPanel:** remova o JAR antigo do painel e coloque `ModPanel-3.1.0.jar`. Vindo do SerpensModPanel 2.0.0, o
   ModPanel importa sozinho o config e o histórico na primeira inicialização (ver README do ModPanel). Não apague
   a pasta antiga até conferir.
4. **Ponte:** troque `ZwoVulcanBridge-2.0.0.jar` (ou 1.0.0) por `ZwoVulcanBridge-3.0.0.jar`. **Renomeie** o
   `plugins/ZwoVulcanBridge/config.yml` antigo (as chaves mudaram) para gerar o novo. `punicoes.log` e
   `strikes.yml` antigos não são mais usados.
5. **Vulcan:** em `plugins/Vulcan/config.yml`, deixe **todos os checks com `punishable: false`**. A ponte
   continua recebendo as flags. Se algum check ainda punir, a ponte cancela a punição e avisa no console e no `/zvb status`.
6. Inicie o servidor. No console devem aparecer:

```
[ZwoVulcanBridge] Floodgate detectado: contas Java/Bedrock serão confirmadas pela API.
[ZwoVulcanBridge] Vulcan 2.9.7.29 conectado: lendo flags (VulcanFlagEvent) ...
[ZwoVulcanBridge] Modo: OBSERVE ...
[ZwoVulcanBridge] Checks aprovados: 0 em 0 família(s) ...
[ZwoVulcanBridge] ModPanel: ...
[ZwoPingState] Provedor PingStateProvider registrado para a ZwoVulcanBridge.
```

7. Rode `/zvb status`. Com o config novo, **"Automação: BLOQUEADA — nenhum check aprovado"** é o esperado: a lista
   `evidence.checks` vem vazia de propósito.

## 3. O que a automação exige

`/zvb status` lista cada bloqueio. A automação só age quando **todos** estão ok:

- checks aprovados em pelo menos 2 famílias diferentes (`evidence.checks`);
- Vulcan conectado (escuta do `VulcanFlagEvent`);
- provedor de conexão disponível (ZwoPingState ligado);
- ModPanel 3.1.0+ com a `ProfileBanApi` (versão 1). ModPanel ausente, desligado ou antigo = **BLOQUEADA**, com o
  motivo no status. Nunca há fallback para `/mpban`, `/mptempban` ou `/ban-ip`.

Mesmo com tudo ok:

- **Bedrock** fica fora (Floodgate, UUID Bedrock ou prefixo do nome).
- Conta que **não dá para confirmar como Java** (Floodgate ausente/com erro) gera só registro para revisão.
  `bedrock.assume-java-without-floodgate: true` é só para servidor **sem** Geyser; com Geyser deixe `false`.

## 4. Escolher os checks aprovados

Durante a observação, veja quais checks aparecem nas linhas `[OBSERVE]` do `decisoes.log` e nos alertas do
Vulcan para quem realmente trapaceia, e quais aparecem em jogadores legítimos. Aprove só checks de **alta
confiança**, com VL mínimo alto, e agrupe em famílias. Cada entrada de `evidence.checks` é
`nome` ou `'nome:tipo'` com `min-vl`, `min-flags` e `family` (veja os comentários do `config.yml`).

Problemas de config (check duplicado, `min-vl` inválido,
tipo inválido) aparecem no console e no `/zvb status`. Aplique com `/zvb reload` (as evidências em andamento
são descartadas).

## 5. Sequência

| Ocorrência (por UUID, dentro de `sequence.validity` = 7d a partir do 1º kick) | Ação |
| --- | --- |
| 1ª | kick com `messages.kick-1` |
| 2ª, em outra sessão | kick com `messages.kick-2` |
| 3ª, em outra sessão | ban temporário de `sequence.ban-duration` (24h) só do perfil, pelo ModPanel |
| depois do ban (aplicado ou em revisão) | só registro para revisão |

- Kick só conta com a desconexão confirmada. Kick cancelado por outro plugin não conta.
- Ban com falha, timeout (`ban-timeout-seconds`) ou resposta incerta vai para **revisão** e não é repetido. Confira
  no ModPanel e use `/zvb limpar <nick>` se quiser que a sequência recomece.
- `/zvb limpar` não desfaz bans: para isso, `/mpunban`.

## 6. Período de observação (obrigatório)

Deixe `mode: OBSERVE` (padrão) por alguns dias, já com os checks que pretende aprovar. Nesse modo a ponte:

- junta as evidências normalmente e grava em `plugins/ZwoVulcanBridge/decisoes.log` cada ocorrência como
  `[OBSERVE] ocorrência válida; teria aplicado: 1º kick ...`;
- **não** kicka, não bane e **não** altera o `historico.yml` (o "teria aplicado" sempre parte do histórico real);
- avisa a staff com `zwovulcanbridge.alerts`.

Revise as linhas `[OBSERVE]`: se aparecer alguém legítimo, aumente `min-vl`/`min-flags` ou tire o check.

## 7. Ativar (somente quando decidido)

`mode: ENFORCE` no `plugins/ZwoVulcanBridge/config.yml` e `/zvb reload`. Confira em `/zvb status` que a linha
"Automação" está **liberada**.

## 8. Rollback

**Desligar a automação na hora (sem reiniciar):** `mode: OBSERVE` e `/zvb reload`. Ou esvazie `evidence.checks`.

**Voltar para a ponte 2.0.0:**

1. Pare o servidor.
2. Troque `ZwoVulcanBridge-3.0.0.jar` por `ZwoVulcanBridge-2.0.0.jar` (está no histórico do repositório, commit
   `7b3f5da`, tag `v2.0.0`).
3. Restaure o `config.yml` da 2.0 do backup. Guarde `historico.yml` e `decisoes.log` (a 2.0 não os usa).
4. Restaure o `plugins/Vulcan/config.yml` do backup (a 2.0 dependia do Vulcan punir).

**Voltar o painel:** o ModPanel 3.1.0 só **acrescenta** a API, sem mudar o banco. Para voltar ao ModPanel 3.0.0,
troque o JAR; a ponte 3.0 vai mostrar "ModPanel: INCOMPATÍVEL" e bloquear a automação, sem quebrar nada. Os bans
já aplicados pela ponte continuam no histórico do painel (origem `API`).

**Remover tudo:** tire os JARs da ponte e do ZwoPingState. O Vulcan continua só detectando enquanto os checks
estiverem `punishable: false`; restaure o config dele se quiser que ele volte a punir sozinho.
