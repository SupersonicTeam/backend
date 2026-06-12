# Design — Upload e importação de XML de notas fiscais de terceiros

- **Data:** 2026-06-12
- **Sprint:** 2 (issue #19 — SupersonicTeam/backend, UC002)
- **Autor:** Supersonic Team
- **Status:** aprovado para planejamento

## Contexto

Hoje o sistema só gera notas próprias (`NotaFiscal` com `produtor` = emitente +
bloco `destinatario` embutido). Esta feature permite **importar XML de notas de
terceiros** (tipicamente notas de entrada/compra, onde o produtor autenticado é o
destinatário e o emitente é um terceiro).

O pipeline de NF-e já fornece peças reaproveitáveis: as classes JAXB em
`nfe/model/` (`NFe` tem `@XmlRootElement` — serve para unmarshalling) e o
`XsdValidator` (valida contra `nfe_v4.00.xsd`; tolera ausência de `ds:Signature`,
então tanto um XML assinado real quanto um gerado localmente passam).

## Objetivos (Definition of Done)

- XML de NF-e real importado com sucesso.
- Dados extraídos corretamente via JAXB.
- Upload de múltiplos arquivos funciona.

## Decisões de design

1. **Modelagem:** discriminador `Origem {PROPRIA, IMPORTADA}` + campos do emitente
   terceiro (`emitenteNome`, `emitenteCpfCnpj`) na `NotaFiscal`. Importada:
   `produtor` = dono autenticado (ownership/anti-IDOR), `origem=IMPORTADA`,
   `status=AUTORIZADA` (já existe no enum), emitente terceiro nos campos novos,
   `destinatario*` recebe o `<dest>` do XML.
2. **Falha parcial no lote:** relatório por arquivo (HTTP 200), arquivos válidos
   importam mesmo que outros falhem.
3. **Duplicata:** `chave_acesso` é `unique`; chave já importada vira erro do
   arquivo no relatório (idempotente, sem duplicar).
4. **Parser:** JAXB reusando `nfe/model`, com parse seguro (XXE hardened) que
   localiza o elemento `<NFe>` tanto em XML raiz `<nfeProc>` (arquivo autorizado
   real) quanto `<NFe>` puro.
5. **Escopo modelo:** layout 4.00 modelo 55 (`tipoDocumento=NFE`). NFA usa o mesmo
   layout 55; distinção NFA no import fica fora de escopo.
6. **Limites multipart:** 5MB por arquivo, 50MB por lote.
7. **Import não passa por `@ValidNcm`/`@ValidCfop`/`CfopOperacaoValidator`** (são do
   request de criação de nota própria). Nota de terceiro é documento já
   autorizado; não a rejeitamos por NCM fora do catálogo agro.

## Componentes

### 1. Configuração multipart
Em `src/main/resources/application.yml`, sob `spring`:
```yaml
  servlet:
    multipart:
      max-file-size: 5MB
      max-request-size: 50MB
```

### 2. Modelo & Banco — migration `0007-nota-fiscal-importacao.xml`
- **`model/Origem`** — `enum { PROPRIA, IMPORTADA }`.
- **`NotaFiscal`** novos campos:
  - `origem` — `@Enumerated(STRING)`, coluna `origem VARCHAR(10) NOT NULL DEFAULT 'PROPRIA'`, `@Builder.Default = Origem.PROPRIA`.
  - `emitenteNome` — `emitente_nome VARCHAR(255)` nullable.
  - `emitenteCpfCnpj` — `emitente_cpf_cnpj VARCHAR(14)` nullable.
- **Migration `0007`** (segue padrão 0005/0006): `addColumn` das 3 colunas
  (`origem` com `defaultValue='PROPRIA'` + `nullable=false`; as outras nullable);
  rollback com `dropColumn`. Incluir no `db.changelog-master.yaml`.
- **`schema-nota-fiscal-test.sql`** (test H2): adicionar as 3 colunas, senão o
  `NotaFiscalRepositoryTest` (insere `NotaFiscal`) quebra.

### 3. Parser — `nfe/NfeXmlParser` (`@Component`)
- `NFe parse(byte[] xml)`:
  - `DocumentBuilderFactory` com XXE hardening (`disallow-doctype-decl`,
    `external-general-entities=false`, `external-parameter-entities=false`,
    `XMLConstants.FEATURE_SECURE_PROCESSING=true`, `setExpandEntityReferences(false)`).
  - Localiza o primeiro elemento `NFe` no namespace
    `http://www.portalfiscal.inf.br/nfe` (raiz ou sob `nfeProc`) via
    `getElementsByTagNameNS(NS, "NFe")`.
  - Serializa o nó `NFe` para string (Transformer) → valida via
    `XsdValidator.validar(nfeXml)`.
  - Unmarshal JAXB do nó para `NFe` (Unmarshaller com `setProperty` seguro).
  - Erros (sem `<NFe>`, malformado, XSD inválido) → `XmlImportacaoException(msg)`.
- **`exception/XmlImportacaoException extends RuntimeException`** — mensagem
  descritiva; capturada por arquivo no serviço (não chega ao handler global no
  fluxo de lote).

### 4. Mapper — `nfe/NfeImportMapper` (`@Component` ou estático)
`NotaFiscal toEntity(NFe nfe, String xmlOriginal, Produtor dono)`:
- `chaveAcesso` = `infNFe.Id` sem o prefixo `"NFe"` (44 dígitos).
- `numero`/`serie`/`naturezaOperacao`/`dataEmissao` de `<ide>` (parse de
  `dhEmi` para `OffsetDateTime`).
- `emitenteNome` = `emit.xNome`; `emitenteCpfCnpj` = `emit.CNPJ` ou `emit.CPF`.
- `destinatarioNome`/`destinatarioCpfCnpj`/`destinatarioIe`/`destinatarioEndereco`
  de `<dest>`/`<enderDest>`.
- itens de `<det>`: `numeroItem`, `descricao`, `ncm`, `cfop`, `unidade`,
  `quantidade`, `valorUnitario`, `valorTotal` (de `<prod>`); IBS/CBS de
  `<imposto><IBSCBS>` quando presentes.
- `valorTotalProdutos`/`valorTotalNota` de `<total><ICMSTot>` (`vProd`/`vNF`).
- `origem=IMPORTADA`, `status=AUTORIZADA`, `tipoDocumento=NFE`, `modelo="55"`,
  `versaoLayoutNfe` = `infNFe.versao`, `xmlGerado` = `xmlOriginal`,
  `dataGeracaoXml` = momento da importação, `produtor` = dono autenticado.
- Erros de parsing de campos obrigatórios → `XmlImportacaoException`.

### 5. Serviço — `service/ImportacaoNotaFiscalService` (`@Service`)
- `ImportacaoLoteResponse importar(List<MultipartFile> arquivos)`:
  - `produtor` = `produtorProvider.get()`.
  - Para cada arquivo: lê bytes; `parser.parse` → `mapper.toEntity` →
    se `repository.existsByChaveAcesso(chave)` → resultado ERRO "nota ja importada";
    senão `repository.save` → resultado SUCESSO (notaId, chave).
  - Captura `XmlImportacaoException` e `IOException` por arquivo → resultado ERRO.
  - Captura `DataIntegrityViolationException` (corrida na unique) → ERRO "ja importada".
  - Monta `ImportacaoLoteResponse` (total, importados, falhas, resultados).
- **`NotaFiscalRepository`**: novo `boolean existsByChaveAcesso(String chave)`.

### 6. DTOs + Controller
- **`dto/ImportacaoItemResultado`** (record): `arquivo` (nome), `sucesso` (bool),
  `notaId` (UUID, nullable), `chaveAcesso` (String, nullable), `erro` (String, nullable).
- **`dto/ImportacaoLoteResponse`** (record): `total`, `importados`, `falhas`,
  `List<ImportacaoItemResultado> resultados`.
- **`NotaFiscalController`**: `POST /api/notas/importar`,
  `@RequestParam("arquivos") List<MultipartFile> arquivos` → 200 com
  `ImportacaoLoteResponse`. Lista nula/vazia → 400 (`IllegalArgumentException`,
  já mapeada). `consumes = multipart/form-data`. OpenAPI documentado.

## Fluxo de dados

```
POST /api/notas/importar (multipart, arquivos[])
  → ImportacaoNotaFiscalService.importar(produtor autenticado)
      para cada arquivo:
        NfeXmlParser.parse(bytes)            // XXE-safe, localiza <NFe>, valida XSD, JAXB
        NfeImportMapper.toEntity(nfe, xml, produtor)  // origem=IMPORTADA, status=AUTORIZADA
        existsByChaveAcesso? -> ERRO "ja importada"
        save -> SUCESSO (notaId, chave)
      -> ImportacaoLoteResponse (relatorio por arquivo)
```

## Tratamento de erros

- Erro por arquivo (XML inválido, XSD inválido, sem `<NFe>`, chave duplicada) →
  registrado como item ERRO no relatório; **não** aborta o lote; HTTP 200.
- Lista de arquivos vazia/nula → 400.
- Arquivo acima de `max-file-size` → Spring lança `MaxUploadSizeExceededException`
  (tratada como 400 pelo handler — adicionar mapeamento no `GlobalExceptionHandler`).
- Ownership: `produtor` da nota importada = autenticado (anti-IDOR preservado).

## Testes

- **`NfeXmlParserTest`**: NFe puro e dentro de `nfeProc` → parse OK; XML
  malformado → `XmlImportacaoException`; payload XXE (entidade externa) → não
  expande / falha segura.
- **`NfeImportMapperTest`**: JAXB `NFe` → `NotaFiscal` com campos corretos
  (emitente, dest, itens, chave, `origem=IMPORTADA`, `status=AUTORIZADA`).
- **`ImportacaoNotaFiscalServiceTest`** (Mockito): lote sucesso+erro → relatório
  correto; chave duplicada (`existsByChaveAcesso=true`) → item ERRO; produtor do provider.
- **Controller** (`@WebMvcTest`, `MockMultipartFile`): 2 arquivos → 200 com
  relatório; lista vazia → 400.
- **DoD "NFe real importado":** gerar um XML via o pipeline real
  (`NotaFiscal` → `GeracaoNotaFiscalService.gerarXml`), importá-lo via
  `NfeXmlParser` + `NfeImportMapper` e assertar a extração — fecha o ciclo
  geração↔importação com validação XSD real.

## Fora de escopo

- Validação da assinatura digital ICP-Brasil do XML importado (Sprint 7).
- Importação de NFA distinta de NF-e (mesmo layout 55).
- Importação de CT-e/NFC-e ou outros modelos.
- Conciliação/dedup por conteúdo além da chave de acesso.
- Frontend.
