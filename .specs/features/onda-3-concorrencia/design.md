# Design — Onda 3: Concorrência e escopo transacional

Complemento do [spec.md](spec.md). Traz os fluxos antes/depois, os changeSets, o contrato de erro e
os pontos de toque por arquivo.

## 1. Estado atual — onde cada defeito vive

```
NotaFiscalService  @Transactional NA CLASSE          <- arrasta tudo para dentro da tx
├── gerarXml(id)                        linha ~269
│   ├── findByIdAndProdutorId           [tx aberta, conexão presa]
│   ├── checa STATUS_EDITAVEIS          <- janela de corrida: nenhum lock, nenhum @Version
│   ├── gerarXmlRegistrandoInconsistencias
│   │   ├── FiscalRulesValidator        [tx presa]
│   │   ├── JAXB marshal                [tx presa]
│   │   ├── NfeAssinador  (RSA-SHA1)    [tx presa]  <- lento
│   │   ├── AssinaturaValidator         [tx presa]
│   │   ├── XsdValidator                [tx presa]  <- lento
│   │   └── catch -> validacaoService.registrarInconsistencias
│   │                 @Transactional(REQUIRES_NEW)   <- 2ª CONEXÃO com a 1ª presa  (#97)
│   ├── extrairChaveAcessoDoXml
│   └── save                            <- last write wins  (#95)
└── gerarPdf(id)                        linha ~340  @Transactional(readOnly)
    ├── findByIdAndProdutorId
    └── DanfePdfService.gerarPdf(nota)  [tx presa]  <- Jasper inteiro + LAZY getItens/getProdutor

SefazService  @Transactional NA CLASSE
├── enviar(notaId)                      linha ~106
│   ├── findByIdAndProdutorId           <- sem lock
│   ├── checa status == XML_GERADO      <- janela de corrida  (#96)
│   ├── sefazClient.enviar(...)         [tx presa]  <- I/O de rede dentro da tx
│   ├── save
│   └── publishEvent(NotaEmitidaEvent)  <- dois requests = dois eventos = débito em dobro
└── cancelar(notaId, motivo)            linha ~208  (mesmo padrão)
```

## 2. Fluxo alvo — `gerarXml`

```
tx1 (readOnly, curta) ─────────────────────────┐
  findByIdAndProdutorId  -> 404 se não é dono   │
  checa STATUS_EDITAVEIS -> 409 se não editável │
  materializa NotaFiscalSnapshot                │
commit ────────────────────────────────────────┘  conexão devolvida ao pool

  ── SEM TRANSAÇÃO, SEM CONEXÃO ────────────────
  FiscalRulesValidator(snapshot)
  JAXB marshal -> NfeAssinador (RSA) -> AssinaturaValidator -> XsdValidator
  catch (validação inválida):
      registrarInconsistencias(...)   tx própria, ÚNICA, sem aninhamento   (#97)
      relança -> 422
  ──────────────────────────────────────────────

tx2 (escrita, curta) ──────────────────────────┐
  findByIdAndProdutorIdForUpdate  [lock]        │
  RECHECA STATUS_EDITAVEIS -> 409               │  <- fecha a janela de corrida
  seta xmlGerado / versaoLayoutNfe /            │
       dataGeracaoXml / chaveAcesso /           │
       status = XML_GERADO                      │
  save  (@Version incrementa)                   │
commit ────────────────────────────────────────┘
```

Duas gerações concorrentes: a segunda thread, ao chegar na tx2, ou espera o lock e vê
`XML_GERADO` (409 de status), ou — se as tx2 se intercalarem de outra forma — falha no `@Version`
(409 de conflito). **Nenhum caminho persiste duas chaves.**

Custo aceito: numa corrida, a thread perdedora executou assinatura e XSD "em vão". É a troca
deliberada por não manter conexão presa durante a criptografia — CPU desperdiçada em caso raro vale
menos que uma conexão presa em todo caso.

## 3. Fluxo alvo — `gerarPdf`

```
tx1 (readOnly, curta) ─────────────────────────┐
  findByIdAndProdutorId  -> 404                 │
  xmlGerado == null -> DanfeIndisponivelException (409)
  materializa NotaFiscalSnapshot (produtor, itens, endereço, totais)
commit ────────────────────────────────────────┘

  ── SEM TRANSAÇÃO ─────────────────────────────
  DanfePdfService.gerarPdf(snapshot)   <- JasperReports inteiro
  ──────────────────────────────────────────────
```

Sem escrita, sem tx2. É o fluxo mais simples da onda e o que mais alivia o pool sob download
concorrente.

## 4. Fluxo alvo — `enviar` (e `cancelar`, simétrico)

```
tx1 (escrita, curta) ──────────────────────────┐
  findByIdAndProdutorIdForUpdate  [lock]        │
  status != XML_GERADO -> 409                   │
  status = ENVIANDO   (@PreUpdate toca updatedAt)
  save                                          │
commit ────────────────────────────────────────┘  lock e conexão liberados

  ── SEM TRANSAÇÃO ─────────────────────────────
  sefazClient.enviar(xml, chave, ambiente)      <- retry do SefazClient aqui
  ──────────────────────────────────────────────

tx2 (escrita, curta) ──────────────────────────┐
  findByIdAndProdutorIdForUpdate  [lock]        │
  aplicarRetorno(...)  -> AUTORIZADA /          │
                          REJEITADA /           │
                          PENDENTE_SEFAZ        │
  save                                          │
  publishEvent(NotaEmitidaEvent)  se AUTORIZADA │  <- exatamente 1 evento
commit ────────────────────────────────────────┘  AFTER_COMMIT dispara a apuração

catch SefazComunicacaoException:
  txC (compensação, curta): ENVIANDO -> PENDENTE_SEFAZ   ;  propaga 502
  (cancelar: CANCELANDO -> AUTORIZADA)
```

Segundo request concorrente: bloqueia no lock da tx1; ao obtê-lo, o status já é `ENVIANDO` → 409.
**O transporte é chamado uma única vez.**

Este é o desenho que a **#83** (Onda 6) precisa — a I/O já está fora da transação, então a troca do
`MockSefazTransport` pelo SOAP real não mexe no escopo transacional.

### Matriz de estados transitórios

| Estado de origem | Transitório | Sucesso | Falha de comunicação | Preso (timeout do job) |
|---|---|---|---|---|
| `XML_GERADO` | `ENVIANDO` | `AUTORIZADA` / `REJEITADA` / `PENDENTE_SEFAZ` | → `PENDENTE_SEFAZ` + 502 | → `PENDENTE_SEFAZ` |
| `AUTORIZADA` | `CANCELANDO` | `CANCELADA` | → `AUTORIZADA` + 502 | → `AUTORIZADA` |

Em `ENVIANDO`/`CANCELANDO`, **toda** operação responde 409 (RF-96.10). Eles não entram em
`STATUS_EDITAVEIS` e `sincronizarStatus` continua exigindo `PENDENTE_SEFAZ` (guarda da #79 intacta).

## 5. Fluxo alvo — `registrarInconsistencias` (#97)

```
ANTES:  [tx externa aberta] ──> REQUIRES_NEW ──> 2ª conexão     10 requests = pool travado
DEPOIS: [sem tx]            ──> REQUIRED     ──> 1ª e única conexão
```

A garantia da #22 muda de mecanismo, não de resultado: antes o histórico sobrevivia ao rollback
porque estava em outra transação; agora **não existe rollback a sobreviver**, porque a exceção de
validação é lançada fora de qualquer transação de escrita. O 422 continua chegando ao cliente com o
histórico já persistido e a nota em `COM_INCONSISTENCIAS`.

**Invariante nova, a documentar no Javadoc:** `registrarInconsistencias` só pode ser chamado fora de
transação. Se um chamador futuro o invocar de dentro de uma tx, o `REQUIRED` vai juntar-se a ela e o
histórico volta a morrer no rollback — regressão silenciosa da #22.

## 6. Componentes

| Arquivo | Mudança | Issue |
|---|---|---|
| `model/NotaFiscal.java` | `@Version private Long version` | #95 |
| `model/StatusNotaFiscal.java` | `+ ENVIANDO`, `+ CANCELANDO` + Javadoc | #96 |
| `repository/NotaFiscalRepository.java` | `+ findByIdAndProdutorIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)`); `+ findByStatusInAndUpdatedAtBefore` (reconciliação) | #96 |
| `common/ErrorCode.java` | `+ CONFLITO_CONCORRENCIA` | #95 |
| `exception/GlobalExceptionHandler.java` | `+ @ExceptionHandler(OptimisticLockingFailureException.class)` → 409 | #95 |
| `service/NotaFiscalService.java` | `@Transactional` da classe → por método; `gerarXml`/`gerarPdf` em 2 fases; 409 nos novos estados | #99 |
| `service/NotaFiscalSnapshot.java` *(novo)* | `record` de materialização do grafo | #99 |
| `service/NotaFiscalValidacaoService.java` | `REQUIRES_NEW` → `REQUIRED` + Javadoc da invariante | #97 |
| `service/SefazService.java` | `enviar`/`cancelar` em 3 tx + compensação; `+ reconciliarTransmissoesPresas` | #96 |
| `service/GeracaoNotaFiscalService.java`, `GeracaoNfaService.java` | recebem snapshot | #99 |
| `nfe/FiscalRulesValidator.java`, `nfe/NotaFiscalNfeMapper.java` | recebem snapshot | #99 |
| `danfe/DanfePdfService.java` | recebe snapshot | #99 |
| `sefaz/SefazStatusSyncJob.java` | chama a reconciliação antes do sync normal | #96 |
| `config/SefazProperties.java` | `+ transmissao.timeout-ms` (default 600000) | #96 |
| `db/changelog/changes/0022-nota-fiscal-version.xml` *(novo)* | coluna `version` | #95 |
| `src/test/resources/schema-nota-fiscal-test.sql` | `+ version BIGINT NOT NULL DEFAULT 0` | #95 |
| `src/main/resources/application.yml` | `app.nfe.sefaz.transmissao.timeout-ms` | #96 |

## 7. Contrato de erro do conflito otimista

```json
{
  "errors": [
    {
      "code": "CONFLITO_CONCORRENCIA",
      "field": null,
      "message": "A nota foi modificada em outra operação; recarregue e tente de novo.",
      "suggestion": "Recarregue a nota (GET /api/notas/{id}) e repita a operação com os dados atualizados.",
      "rejectedValue": null,
      "expectedValue": null
    }
  ]
}
```

HTTP **409**. Nada de nome de exceção, stack trace ou nome de tabela — detalhe técnico só no log
(`log.warn` com `notaId` e a exceção).

## 8. ChangeSet 0022 — esboço

```xml
<changeSet id="0022-nota-fiscal-version" author="agrocontabil">
  <addColumn tableName="nota_fiscal">
    <column name="version" type="BIGINT" defaultValueNumeric="0">
      <constraints nullable="false"/>
    </column>
  </addColumn>
  <rollback>
    <dropColumn tableName="nota_fiscal" columnName="version"/>
  </rollback>
</changeSet>
```

`defaultValueNumeric="0"` preenche as linhas existentes na própria adição, então `nullable="false"`
não quebra em base com dados. Nenhum changeSet existente é editado.

## 9. Pontos de atenção na implementação

1. **`@Transactional` por método com autoinvocação — resolvido: usar `TransactionTemplate`.**
   Ver §10.
2. **`@Version` + `save()` em entidade detached.** Na tx2 a nota é recarregada com lock, então é
   managed — correto. Não reaproveitar a instância carregada na tx1 para persistir na tx2: seria
   detached com `version` potencialmente velha.
3. **Ordem de checagem no `@Lock`.** O `findByIdAndProdutorIdForUpdate` já filtra por produtor, então
   o 404 anti-IDOR continua vindo antes de qualquer 409 — CA-11.
4. **`PESSIMISTIC_WRITE` e `readOnly`.** Nunca combinar lock de escrita com `readOnly = true`.
5. **Teste de pool=1 precisa de timeout curto.** Sem `spring.datasource.hikari.connection-timeout`
   baixo, um deadlock **pendura** a suíte em vez de falhar. Configurar timeout de aquisição de poucos
   segundos no `@TestPropertySource` desse teste.
6. **Eventos e `AFTER_COMMIT`.** O `publishEvent` fica dentro da tx2 (o listener é `AFTER_COMMIT`).
   Publicar fora da transação faria o listener nunca disparar.
7. **Schema dos novos status.** Já verificado: `0002-nota-fiscal.xml` define
   `status VARCHAR(20) NOT NULL DEFAULT 'RASCUNHO'` sem CHECK constraint. O PR de #96 **não tem
   changeSet** — o único changeSet da onda é o `0022` do PR 1.

## 10. Decisão D-8 — `TransactionTemplate`, não colaborador com `@Transactional`

**Problema.** Quebrar `gerarXml` em fases transacionais dentro de `NotaFiscalService` esbarra na
autoinvocação: uma chamada direta de método para método na mesma classe **não passa pelo proxy do
Spring**, e a `@Transactional` da fase é ignorada **em silêncio**. O redesenho pareceria pronto,
os testes de comportamento passariam e tudo continuaria rodando numa transação só — o defeito
exato que a #99 quer remover, agora invisível.

**Opções avaliadas.**

| Opção | Como funciona | Por que não |
|---|---|---|
| Autoinjeção (`@Lazy NotaFiscalService self`) | `self.fase1(...)` força a passagem pelo proxy | Ciclo de dependência deliberado, difícil de ler e de testar. Convida a erro: basta alguém trocar `self.fase1()` por `fase1()` num refactor para o escopo transacional sumir sem nenhum sintoma. |
| Colaborador `NotaFiscalTxSupport` com `@Transactional` | Bean separado, cada fase é um método anotado | Funciona e é testável, mas cria uma classe cuja única razão de existir é contornar o proxy. O escopo da transação fica em **outro arquivo**, longe do fluxo que ele delimita — mais difícil de auditar na revisão, que é justamente o que a #99 precisa tornar evidente. |
| **`TransactionTemplate`** ✅ | Método de orquestração sem `@Transactional`; cada fase é um `transactionTemplate.execute(status -> {...})` no próprio fluxo | **É o precedente do projeto.** Escolhida. |

**Por que `TransactionTemplate` vence aqui.**

1. **Já é o padrão da casa, pelo mesmo motivo.** `ImportacaoNotaFiscalService` (issue #19 + #64) usa
   exatamente este desenho: o método de lote **não** é `@Transactional`, cada arquivo roda em
   `transactionTemplate.execute(...)`, e o Javadoc documenta a razão. A convenção do projeto é
   "escreva como o código ao redor" — não há por que inventar um mecanismo diferente para o mesmo
   problema, a três arquivos de distância.
2. **Imune à armadilha da autoinvocação.** Não existe proxy a contornar: o limite da transação é o
   próprio bloco do lambda. Nenhum refactor futuro consegue apagá-lo acidentalmente.
3. **O escopo fica legível no ponto de uso.** Dá para ver, lendo `gerarXml` de cima a baixo, onde a
   transação abre e fecha, e que assinatura RSA e XSD estão **fora** dos blocos. Numa revisão de PR
   sobre escopo transacional, isso é a diferença entre auditável e "confie na anotação".
4. **Resolve o requisito de evento de #96 de graça.** `ImportacaoNotaFiscalService` já documenta que
   `publishEvent` precisa rodar **dentro** da transação, porque o listener é
   `@TransactionalEventListener(AFTER_COMMIT)` e um evento publicado sem transação ativa é
   **descartado em silêncio**. Com `TransactionTemplate`, a publicação na tx2 de `enviar` fica
   naturalmente dentro do lambda — e o precedente prova que o desenho funciona.
5. **Timeout por bloco.** `TransactionTemplate` aceita timeout próprio, alinhado ao que a #113 já faz
   com `@Transactional(timeoutString=...)` nos jobs. As tx curtas desta onda ficam muito abaixo do
   `spring.transaction.default-timeout: 30s`.

**Consequências para a implementação.**

- `NotaFiscalService` recebe `TransactionTemplate` no construtor, como `ImportacaoNotaFiscalService`.
- `gerarXml` e `gerarPdf` deixam de ter `@Transactional`; as fases viram blocos `execute(...)`.
- A fase de leitura usa um `TransactionTemplate` **readOnly** — injetar um segundo bean qualificado
  ou configurar `TransactionDefinition` por chamada. Nunca combinar readOnly com lock de escrita.
- Os métodos CRUD (`create`, `update`, `delete`, `findAll`, `findById`, `obterXml`) **continuam com
  `@Transactional` declarativo** — são de fase única, sem I/O pesado, e mexer neles seria escopo
  desnecessário.
- `SefazService` (PR 2) segue o mesmo desenho: `enviar`/`cancelar` deixam de ser `@Transactional` e
  passam a três blocos `execute(...)` (tx1, tx2 e a de compensação).
