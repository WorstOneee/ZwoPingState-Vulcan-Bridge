# Instalação

Requisitos: **Paper 1.21.11**, **Java 21**, **Vulcan 2.9.7.x** com um **packetevents compatível**.

> No teste local, o Vulcan 2.9.7.29 **não funciona com packetevents 2.12.1**: todo jogador é desconectado
> com "Invalid packet" (`NoSuchFieldError: PacketType$Play$Client PUNCH`). Com o packetevents 2.14.0 funcionou.
> Confira a versão que está no servidor antes de atualizar o Vulcan.

## 1. Arquivos

Copie para `plugins/`:

| JAR | Obrigatório | Função |
| --- | --- | --- |
| `ZwoVulcanBridge-1.0.0.jar` | sim | Ponte Vulcan → SerpensModPanel. Contém a API `PingStateProvider`. |
| `ZwoPingState-1.0.0.jar` | sim | Provedor da qualidade de conexão. Não pune ninguém. |

A ordem de carregamento é automática: o ZwoPingState declara `softdepend: [ZwoVulcanBridge]`.

## 2. Primeiro boot

Reinicie o servidor (não use `/reload`). No console devem aparecer:

```
[ZwoVulcanBridge] Vulcan 2.9.7.29 conectado via VulcanFlagEvent.
[ZwoVulcanBridge] Modo: OBSERVE (observação: ninguém é punido)
[ZwoVulcanBridge] Ban automático desativado: comando de ban vazio ...
[ZwoPingState] Provedor PingStateProvider registrado para a ZwoVulcanBridge.
```

## 3. Conferência

```
/zvb status        -> Vulcan "conectado", provedor "ZwoPingState 1.0.0"
/pingstate stats   -> "provedor registrado na ponte", custo da coleta, idade das amostras
/pingstate <nick>  -> estado do jogador
```

## 4. Período de observação (obrigatório)

Deixe `mode: OBSERVE` (padrão) por alguns dias. Nesse modo a ponte:

- avalia as flags normalmente e bloqueia as punições internas do Vulcan (`cancel-vulcan-punishments: true`);
- grava cada ocorrência em `plugins/ZwoVulcanBridge/ocorrencias.log` como "teria recebido KICK 1/2", etc.;
- avisa a staff com a permissão `zwovulcanbridge.alerts`;
- **não kicka nem bane ninguém**.

Revise o `ocorrencias.log`. Se houver casos de jogadores legítimos, ajuste `flags.*` (ponte) e `limits.*` (ZwoPingState).

## 5. Ban por perfil — leia antes de ativar

O `/mpban` do ModPanel/SerpensModPanel **também bane o último IP** do jogador quando o IP tem menos contas que
`limits.ip-ban-account-limit` (mínimo 2, não dá para desligar pela config). Por isso a ponte **recusa**
`mpban`, `mptempban`, `mpipban`, `ban-ip`, `banip` e similares.

Enquanto `punishments.ban-command` estiver vazio, a terceira ocorrência válida **kicka o jogador e fica
PENDENTE para revisão manual** (aparece no log, no `ocorrencias.log` e para a staff).

Opções para o ban automático por perfil:

1. Adicionar ao ModPanel um comando de ban **somente de perfil** (sem IP) e configurar, por exemplo,
   `ban-command: "mpbanperfil {uuid} {reason}"` — recomendado, mantém o histórico no painel.
2. Usar o ban nativo do Paper, que é só de perfil: `ban-command: "minecraft:ban {player} {reason}"`
   (não passa pelo painel).

## 6. Ativar punições (somente quando decidido)

No `plugins/ZwoVulcanBridge/config.yml`: `mode: ENFORCE` e `/zvb reload`. Bedrock continua excluído.

## Atualização

Pare o servidor, troque os JARs e inicie. Os arquivos `strikes.yml` e `ocorrencias.log` são mantidos.
Novas chaves de config usam o valor padrão até você adicioná-las.
