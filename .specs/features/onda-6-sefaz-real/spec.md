# Spec — Onda 6: Sprint 7, SEFAZ real

- **Data:** 2026-09-25
- **Issues:** #84 (cancelamento rejeitado) → #83 (endurecer `SefazService` para o transporte SOAP real)
- **Pressupõe:** Onda 3 mergeada (#96: lock pessimista + estados `ENVIANDO`/`CANCELANDO` + I/O fora
  da transação; #99: padrão "tx curta → trabalho fora → tx curta") e Onda 2 #88 (emitente real).
- **Baseline:** 570 testes (5 skipped), todos verdes em `791afaf`.
- **Autonomia:** Murillo autorizou em 2026-09-25 a execução autônoma da onda (spec → PR → review
  avançado → correções → merge, fechando as issues).

## 1. O que a Onda 3 já entregou e o que sobra

A #96 resolveu parte do que a #83 pedia; esta onda parte dela.

| Item da #83/#84 | Situação em `791afaf` |
|---|---|
| Rede dentro da transação (`enviar`/`cancelar`/`sincronizarStatus`) | **Resolvido** (#96): 3 transações curtas + `TransactionTemplate`. |
| `sincronizarPendentes` numa transação única | **Resolvido** (#96): transação curta por nota. |
| `sincronizarPendentes` sem paginação | **Aberto**: `findByStatus(PENDENTE_SEFAZ)` ainda carrega todas as entidades. |
| Sync sem freio quando a SEFAZ cai | **Aberto**: com a SEFAZ fora, cada nota gasta `max-attempts × read-timeout`; 100 notas prendem o job por horas, muito além do `lockAtMostFor` (14 min) do ShedLock. |
| Retry não idempotente em `enviar`/`cancelar` | **Aberto**: `@Retryable(SefazComunicacaoException)` reenvia depois de um read-timeout. |
| Reconciliação pela chave quando a resposta se perde | **Aberto**: qualquer falha vira `PENDENTE_SEFAZ` e espera o job (até 15 min). |
| Duplicidade (cStat 204 no envio, 573 no evento) | **Aberto**: 204 vira `REJEITADA` com a nota autorizada no fisco. |
| Timeout de socket configurável | **Aberto**. |
| Cancelamento rejeitado com exceção própria (#84) | **Parcial**: `CancelamentoRejeitadoException` existe, mas responde **409** e prefixa o `xMotivo`. A issue pede **422**, `message = xMotivo`, `rejectedValue = cStat` e teste ponta a ponta com transporte que rejeita. |

## 2. Decisões de escopo

| Débito da Sprint 7 | Decisão | Motivo |
|---|---|---|
| Transporte SOAP + mTLS real (ICP-Brasil, WSDL por UF) | **Fora desta onda, issue nova.** | Exige certificado ICP-Brasil do emitente, acesso à homologação de cada UF e a escolha da biblioteca SOAP. Esta onda deixa o `SefazService` pronto para ele (contrato de exceções, timeouts, reconciliação), que é o pré-requisito. |
| `cNF` via `SecureRandom` | **Dentro, PR D, issue nova.** | Mudança contida em `ChaveAcessoGenerator`. O `cNF` existe para impedir que terceiros deduzam a chave de acesso; o valor derivado de número/série anula isso. |

## 3. Hierarquia de exceções de comunicação e política de retry

```
DomainException
└── SefazComunicacaoException                (502; instância direta = indeterminada)
    ├── SefazConexaoException                a requisição comprovadamente NÃO chegou ao fisco
    └── SefazRespostaIndeterminadaException  a requisição PODE ter sido processada
```

| Classe | Quando o transporte lança | Exemplos |
|---|---|---|
| `SefazConexaoException` | Falha **antes** de o corpo da requisição sair | conexão recusada, DNS, connect-timeout, handshake TLS |
| `SefazRespostaIndeterminadaException` | Falha **depois** de enviar | read-timeout, conexão resetada, resposta ilegível, HTTP 5xx |
| `SefazComunicacaoException` (direta) | Transporte não sabe classificar | tratada como **indeterminada** (conservador) |

As três continuam respondendo **502** com `SEFAZ_COMUNICACAO_FALHOU`: o contrato público não muda.

| Operação (`SefazClient`) | Idempotente? | Retry em |
|---|---|---|
| `enviar` | Não (reenvio → cStat 204) | só `SefazConexaoException` |
| `cancelar` | Não (reenvio → cStat 573) | só `SefazConexaoException` |
| `consultarStatus` | Sim (leitura) | qualquer `SefazComunicacaoException` |

## 4. Situações de consulta novas (`SituacaoSefaz`)

| Valor | cStat típico | Origem | Efeito na nota em `PENDENTE_SEFAZ` (sincronização) |
|---|---|---|---|
| `NAO_ENCONTRADA` (novo) | 217 | só `consultarStatus` | volta para `XML_GERADO`: o fisco não tem a NF-e, reenviar é seguro, e um reenvio que colida com processamento tardio cai no tratamento de 204 |
| `CANCELADA` (novo) | 101 / 151 | só `consultarStatus` | `CANCELADA`, sem evento (nota pendente nunca entrou no débito) |

O `MockSefazTransport` passa a responder `NAO_ENCONTRADA` (217) para chave nunca transmitida (hoje
`REJEITADA`) e `CANCELADA` (101) para chave cancelada, ficando mais fiel à SEFAZ real.

## 5. Máquina de estados durante o envio

```
XML_GERADO --tx1--> ENVIANDO --(rede, fora de tx)-->
  ├─ retorno normal (100 / rejeição / denegação / pendente) ..... tx2 → AUTORIZADA | REJEITADA | PENDENTE_SEFAZ (200)
  ├─ cStat 204 (duplicidade) ........ reconcilia pela chave → AUTORIZADA | REJEITADA | PENDENTE_SEFAZ (200)
  ├─ SefazConexaoException (retries esgotados) ....... compensa → XML_GERADO + 502 (nunca chegou)
  ├─ indeterminada (ou base) ........ reconcilia pela chave:
  │     ├─ AUTORIZADA / REJEITADA / DENEGADA ........................ tx2 aplica + 200 (evento se autorizou)
  │     └─ NAO_ENCONTRADA / PENDENTE / outra / consulta falhou ...... compensa → PENDENTE_SEFAZ + 502
  └─ outra RuntimeException ......................................... compensa → PENDENTE_SEFAZ + relança
```

Na reconciliação **imediata**, `NAO_ENCONTRADA` **não** devolve para `XML_GERADO`: logo depois de um
read-timeout a SEFAZ pode ainda estar processando. Quem conclui "não chegou" é a sincronização
posterior (job ou endpoint), e mesmo um engano ali é absorvido pelo tratamento de 204.

## 6. Máquina de estados durante o cancelamento

```
AUTORIZADA --tx1--> CANCELANDO --(rede)-->
  ├─ cStat 135/155 ................................................ tx2 → CANCELADA + evento (200)
  ├─ cStat 573 (duplicidade de evento) → consulta: CANCELADA → tx2 → CANCELADA + evento (200)
  │                                              senão → compensa AUTORIZADA + 422 CANCELAMENTO_REJEITADO
  ├─ outro cStat ............................ compensa AUTORIZADA + 422 CANCELAMENTO_REJEITADO (#84)
  ├─ SefazConexaoException ......................................... compensa AUTORIZADA + 502
  └─ indeterminada → consulta: CANCELADA → tx2 → CANCELADA + evento (200)
                              senão → compensa AUTORIZADA + 502
```

A compensação continua indo para `AUTORIZADA`, e não para `PENDENTE_SEFAZ`: `PENDENTE_SEFAZ`
tiraria do débito da apuração uma nota que segue autorizada no fisco.

## 7. Desenho transacional

- `enviar`/`cancelar`: tx1 (lock + estado transitório) → rede fora de tx (envio **e** consulta de
  reconciliação) → tx2 (lock + desfecho + evento dentro da tx, por causa do `AFTER_COMMIT`).
- `sincronizarPendentes` (PR C):
  - lê **só IDs** em lotes por keyset (`id > último ORDER BY id`, `app.nfe.sefaz.sync.lote-tamanho`,
    padrão 100), numa transação curta por lote;
  - por nota: tx curta de leitura → consulta fora de tx → tx curta de aplicação com recheck de
    `PENDENTE_SEFAZ`;
  - qualquer `RuntimeException` de uma nota é logada e não afeta as demais;
  - **disjuntor**: `app.nfe.sefaz.sync.max-falhas-consecutivas` (padrão 5) falhas de comunicação
    seguidas encerram a execução (SEFAZ fora do ar); as notas restantes ficam para o próximo ciclo;
  - guarda da #79 preservada: só `PENDENTE_SEFAZ` é sobrescrita; evento publicado quando autoriza.

## 8. Propriedades novas em `SefazProperties`

| Propriedade | Padrão | Uso |
|---|---|---|
| `app.nfe.sefaz.timeout.connect-ms` | 10000 | connect-timeout do transporte real |
| `app.nfe.sefaz.timeout.read-ms` | 30000 | read-timeout do transporte real |
| `app.nfe.sefaz.sync.lote-tamanho` | 100 | IDs por página do keyset |
| `app.nfe.sefaz.sync.max-falhas-consecutivas` | 5 | disjuntor do sync |

**Invariante validado no startup** (a aplicação não sobe se violado): o pior caso de uma transmissão
(envio com retry + consulta de reconciliação com retry) precisa caber em `transmissao.timeout-ms`.
Por operação: `max-attempts × (connect-ms + read-ms) + (max-attempts − 1) × backoff-ms`; a
transmissão soma duas operações. Sem isso, a reconciliação de transmissões presas (#96) poderia
atropelar uma transmissão ainda viva. Com os padrões: 2 × (3 × 40 s + 2 s) = 244 s < 600 s.

As chaves órfãs `transaction-timeout` de `sefaz.sync` e `sefaz.transmissao` no `application.yml`
(nenhuma classe as lê desde a #96) saem no PR C.

## 9. Testes (critérios verificáveis)

| # | Teste | Tipo |
|---|---|---|
| T1 | Read-timeout depois de a SEFAZ autorizar: transporte chamado **1 vez**, nota `AUTORIZADA` via consulta, `NotaEmitidaEvent` **1 vez**, 200 | integração (`@SpyBean` no transporte) |
| T2 | `SefazConexaoException` é re-tentada e a nota volta a `XML_GERADO` + 502 | integração |
| T3 | Indeterminada **não** é re-tentada em `enviar`/`cancelar`; é em `consultarStatus` | integração do proxy de retry |
| T4 | Envio com cStat 204 reconcilia e fica `AUTORIZADA` (não `REJEITADA`) | unitário |
| T5 | Durante a chamada ao transporte **não há transação ativa** e o pool Hikari tem **0 conexões ativas** | integração |
| T6 | Sync: 5 pendentes, lote de 2, erro não-comunicação na 2ª não impede as outras 4 | integração |
| T7 | Sync: disjuntor encerra após N falhas de comunicação seguidas | unitário |
| T8 | Sync `NAO_ENCONTRADA` → `XML_GERADO`; `CANCELADA` → `CANCELADA`; terminal nunca ressuscita (#79) | unitário |
| T9 | Cancelamento recusado (cStat 420) → **422** `CANCELAMENTO_REJEITADO`, `message = xMotivo`, `rejectedValue = cStat`, nota segue `AUTORIZADA` | integração HTTP (MockMvc) |
| T10 | Cancelamento com 573 ou indeterminado + consulta `CANCELADA` → `CANCELADA` + evento 1 vez | unitário |
| T11 | Startup falha quando o invariante de timeouts é violado | unitário |
| T12 | `cNF` aleatório: 8 dígitos, ≠ `nNF`, DV correto, gerações diferem | unitário |

## 10. Critérios de aceite

1. Nenhum caminho reenvia à SEFAZ um XML ou evento cuja entrega é incerta sem antes consultar pela chave.
2. Nenhuma chamada ao transporte acontece com transação aberta (T5).
3. `sincronizarPendentes` nunca carrega todas as entidades, progride nota a nota e para cedo com a SEFAZ fora.
4. Cancelamento recusado responde 422 com cStat/xMotivo do fisco.
5. Regras preservadas: anti-IDOR 404, guarda contra ressuscitar nota terminal (#79), evento de
   apuração exatamente uma vez por autorização/cancelamento (#64/#96), lock pessimista (#96).
6. Suíte inteira verde.

## 11. Achado durante a execução: #125

O T5 falhou na primeira execução: sem transação ativa, havia 1 conexão Hikari presa e um
`EntityManagerHolder` preso à thread. A causa não estava no `SefazService`, e sim no
`application.yml`: o PR #110 inseriu `server:` no meio de `spring:`, e `spring.jpa.open-in-view`,
`ddl-auto: validate`, `default_batch_fetch_size` (#81), `jdbc.time_zone` e os limites de multipart
viraram `server.*`, ignorados em silêncio. Corrigido no PR #126 (issue #125), mergeado antes do
PR B. Sem ele, o critério de aceite 2 era inatingível com qualquer código de serviço.

## 12. Sequência de PRs

| PR | Branch | Escopo | Fecha |
|---|---|---|---|
| A | `onda6/84-cancelamento-rejeitado` | 422 + contrato do `ErrorDetail` + OpenAPI + T9 | #84 |
| — | `fix/config-yaml-spring-jpa` | #125: bloco `server:` fora do lugar (achado do T5), PR #126 | #125 |
| B | `onda6/83-retry-idempotente` | exceções, retry por tipo, reconciliação pela chave, 204/573, `NAO_ENCONTRADA`/`CANCELADA`, mock fiel; T1–T5, T8, T10 | — |
| C | `onda6/83-sync-paginado` | keyset + disjuntor + timeouts + invariante + limpeza do yml; T6, T7, T11 | #83 |
| D | `onda6/cnf-secure-random` | `cNF` via `SecureRandom`; T12 | issue nova |

Issue nova registrada para o transporte SOAP real (Sprint 7, fora desta onda).
