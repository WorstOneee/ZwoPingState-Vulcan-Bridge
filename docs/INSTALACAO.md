# Instalação

Requisitos: **Paper 1.21.11**, **Java 21**, **Vulcan 2.9.7.x** com um **packetevents compatível**. Para o ban
só por perfil: **ModPanel com o comando `/mpbanperfil`** (ver o item 4).

> No teste local, o Vulcan 2.9.7.29 **não funciona com packetevents 2.12.1**: todo jogador é desconectado
> com "Invalid packet" (`NoSuchFieldError: PacketType$Play$Client PUNCH`). Com o packetevents 2.14.0 funcionou.
> Confira a versão que está no servidor antes de atualizar o Vulcan.

## 1. Arquivos

Copie para `plugins/` (estão em [`dist/`](../dist)):

| JAR | Obrigatório | Função |
| --- | --- | --- |
| `ZwoVulcanBridge-2.0.0.jar` | sim | Filtro de lag para as punições do Vulcan. Contém a API `PingStateProvider`. |
| `ZwoPingState-1.0.0.jar` | sim | Provedor da qualidade de conexão. Não pune ninguém. |

A ordem de carregamento é automática: o ZwoPingState declara `softdepend: [ZwoVulcanBridge]` e a ponte
declara `softdepend: [Vulcan]`.

## 2. Quem pune

**Quem pune é o Vulcan**, pelos `punishment-commands` de cada check no `plugins/Vulcan/config.yml`. A ponte
não kicka nem bane ninguém. Ela só cancela a punição do Vulcan (`VulcanPunishEvent`) quando:

- a conexão do jogador não está `STABLE` (ping alto, picos, oscilação, servidor instável, dados insuficientes
  ou vencidos);
- o provedor (ZwoPingState) não está disponível;
- houve flag com lag nos últimos `filter.recent-lag-seconds` (o VL daquele momento foi inflado por lag);
- o jogador é Bedrock;
- o jogador acabou de teleportar, renascer ou trocar de mundo.

Quando bloqueia (no modo ENFORCE), a ponte zera o VL daquele check no Vulcan, para o lag não ficar "guardado".

## 3. Primeiro boot

Reinicie o servidor (não use `/reload`). No console devem aparecer:

```
[ZwoVulcanBridge] Vulcan 2.9.7.29 conectado: filtrando punições e flags.
[ZwoVulcanBridge] Modo: OBSERVE (observação: TODA punição do Vulcan é bloqueada e só registrada)
[ZwoPingState] Provedor PingStateProvider registrado para a ZwoVulcanBridge.
```

Se aparecer `O config do Vulcan tem N comando(s) que podem banir IP`, veja o item 4.

## 4. Ban só por perfil (nunca IP)

O `/mpban` e o `/mptempban` do ModPanel **também banem o último IP** do jogador quando o IP tem poucas contas.
Nos `punishment-commands` do Vulcan use o comando novo do ModPanel, que bane **só o perfil/UUID**:

```yaml
punishment-commands:
  - 'mpbanperfil %player% [Vulcan] Unfair Advantage'
```

Alternativa sem o painel: o ban nativo do Paper também é só de perfil (`minecraft:ban %player% <motivo>`).
Para kick, mantenha `kick %player% ...`.

Ao ligar e no `/zvb reload`, a ponte lê o config do Vulcan e **avisa** (console e `/zvb status`) se encontrar
`mpban`, `mptempban`, `mpipban`, `ban-ip`, `banip` ou similares. Ela não altera o config do Vulcan.

## 5. Conferência

```
/zvb status        -> Vulcan "conectado", provedor "ZwoPingState 1.0.0", contadores de punições
/pingstate stats   -> custo da coleta, idade das amostras
/pingstate <nick>  -> estado do jogador
/zvb jogador <nick>-> conexão, proteção e se uma punição do Vulcan passaria agora
```

## 6. Período de observação (obrigatório)

Deixe `mode: OBSERVE` (padrão) por alguns dias. Nesse modo a ponte:

- **bloqueia todas as punições do Vulcan** (ninguém é kickado nem banido pelo Vulcan);
- grava cada uma em `plugins/ZwoVulcanBridge/punicoes.log` como "BLOQUEADA (observação; teria passado)" ou
  "BLOQUEADA (conexão instável)", etc.;
- avisa a staff com a permissão `zwovulcanbridge.alerts`;
- não mexe nas flags nem no VL: alertas e setbacks do Vulcan continuam normais.

Revise o `punicoes.log`. As linhas "teria passado" são as punições que o Vulcan aplicaria em ENFORCE.

## 7. Ativar (somente quando decidido)

No `plugins/ZwoVulcanBridge/config.yml`: `mode: ENFORCE` e `/zvb reload`. Bedrock continua excluído.

> `filter.cancel-flags` fica `false` de propósito: com `false`, a flag segue inteira no Vulcan (alerta para a
> staff e o tratamento normal do Vulcan, como o setback) e só a **punição** é filtrada. Cancelar a flag
> provavelmente também cancela o setback, e quem forja lag andaria livre. Isso **não foi confirmado** no teste
> local: os bots trapaceiros foram kickados por "flying" pelo vanilla nos dois casos.

## Atualização a partir da 1.0.0

A ponte 1.0.0 tinha uma escada própria (kick, kick, ban). Na 2.0.0 isso saiu: quem pune é o Vulcan.

1. Pare o servidor e troque o JAR.
2. **Apague ou renomeie** o `plugins/ZwoVulcanBridge/config.yml` antigo, para gerar o novo (as chaves mudaram).
3. Os arquivos `strikes.yml` e `ocorrencias.log` antigos não são mais usados e podem ser arquivados.
4. Ajuste os `punishment-commands` do Vulcan (item 4).
