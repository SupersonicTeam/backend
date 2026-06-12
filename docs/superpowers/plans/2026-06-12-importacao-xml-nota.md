# Importação de XML de notas fiscais de terceiros — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permitir importar XML de NF-e de terceiros via `POST /api/notas/importar` (upload múltiplo), com parser JAXB XXE-safe, validação XSD e extração para `NotaFiscal` marcada como importada.

**Architecture:** Endpoint multipart → `ImportacaoNotaFiscalService` itera os arquivos; por arquivo `NfeXmlParser` (DOM seguro localiza `<NFe>` em `nfeProc`/`NFe`, valida via `XsdValidator`, unmarshal JAXB) → `NfeImportMapper` extrai para `NotaFiscal` (`origem=IMPORTADA`, `status=AUTORIZADA`, emitente terceiro em campos novos) → dedup por `chaveAcesso` → save. Falhas são capturadas por arquivo e devolvidas num relatório (HTTP 200).

**Tech Stack:** Java 21, Spring Boot 3.5.14, Spring Web (multipart), JAXB, JAXP DOM, Liquibase, JUnit 5 + Mockito + AssertJ.

**Spec:** `docs/superpowers/specs/2026-06-12-importacao-xml-nota-design.md`

**Branch:** `sprint-2--notas-fiscais`.

---

## Notas gerais para o executor

- **Comando de teste:** `.\mvnw.cmd test` (PowerShell) ou `./mvnw test` (bash). Um só: `-Dtest=NomeDaClasse`.
- **Infra de teste:** profile `test` desabilita Liquibase e usa `ddl-auto: create-drop`. `@DataJpaTest` (ex.: `NotaFiscalRepositoryTest`) usa schema H2 manual (`schema-nota-fiscal-test.sql`) com `ddl-auto=none` — qualquer coluna nova na entidade `NotaFiscal` PRECISA ser adicionada nesse script.
- **`@WebMvcTest`** (`NotaFiscalControllerTest`) não sobe JPA; já mocka `ProdutorRepository`, `NcmAgropecuarioRepository`, `CfopProdutorRuralRepository`. O novo serviço de importação será `@MockBean` lá.
- **Reuso:** classes JAXB em `nfe/model/` (NFe tem `@XmlRootElement`), `XsdValidator` (valida contra `nfe_v4.00.xsd`; tolera ausência de `ds:Signature`). Para gerar um XML real de teste, instancie o pipeline como em `GeracaoNotaFiscalServiceTest`.
- **Estilo:** `@DisplayName` em português, AssertJ, Lombok. Conventional Commits. Nunca editar changesets 0001-0006.

---

## Task 1: Config multipart + discriminador `Origem` + campos do emitente + migration 0007

**Files:**
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/br/com/agrocontabil/model/Origem.java`
- Modify: `src/main/java/br/com/agrocontabil/model/NotaFiscal.java`
- Create: `src/main/resources/db/changelog/changes/0007-nota-fiscal-importacao.xml`
- Modify: `src/main/resources/db/changelog/db.changelog-master.yaml`
- Modify: `src/test/resources/schema-nota-fiscal-test.sql`

- [ ] **Step 1: Config multipart**

Em `src/main/resources/application.yml`, dentro do bloco `spring:` (após `application:`/`profiles:`, no mesmo nível de `jpa:`), adicione:

```yaml
  servlet:
    multipart:
      max-file-size: 5MB
      max-request-size: 50MB
```

- [ ] **Step 2: Enum `Origem`**

Create `src/main/java/br/com/agrocontabil/model/Origem.java`:

```java
package br.com.agrocontabil.model;

/**
 * Origem da nota fiscal no sistema.
 *
 * <ul>
 *   <li>{@link #PROPRIA} — emitida pelo produtor autenticado (fluxo padrão).</li>
 *   <li>{@link #IMPORTADA} — XML de terceiro importado (nota de entrada); o produtor
 *       autenticado é o dono/destinatário e o emitente é um terceiro.</li>
 * </ul>
 */
public enum Origem {
  PROPRIA,
  IMPORTADA
}
```

- [ ] **Step 3: Campos novos na entidade `NotaFiscal`**

Em `src/main/java/br/com/agrocontabil/model/NotaFiscal.java`, logo após o campo `tipoDocumento` (que termina com `private TipoDocumento tipoDocumento = TipoDocumento.NFE;`), adicione:

```java
  /**
   * Origem da nota: {@link Origem#PROPRIA} (emitida pelo produtor) ou
   * {@link Origem#IMPORTADA} (XML de terceiro importado).
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "origem", length = 10, nullable = false, columnDefinition = "VARCHAR(10)")
  @Builder.Default
  private Origem origem = Origem.PROPRIA;

  /**
   * Nome/razão social do emitente terceiro. Preenchido apenas em notas
   * {@link Origem#IMPORTADA}; nulo nas próprias (o emitente é o {@link #produtor}).
   */
  @Column(name = "emitente_nome", length = 255)
  private String emitenteNome;

  /** CPF/CNPJ do emitente terceiro (somente dígitos). Apenas em notas importadas. */
  @Column(name = "emitente_cpf_cnpj", length = 14)
  private String emitenteCpfCnpj;
```

(`@Enumerated`, `EnumType`, `@Column`, `@Builder.Default` já estão importados.)

- [ ] **Step 4: Migration 0007**

Create `src/main/resources/db/changelog/changes/0007-nota-fiscal-importacao.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
            http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.27.xsd">

    <!-- ================================================================
         0007-nota-fiscal-importacao.xml
         Sprint 2 — issue #19 (UC002): importacao de XML de terceiros.

         Adiciona em nota_fiscal:
           - origem            VARCHAR(10) NOT NULL DEFAULT 'PROPRIA'
           - emitente_nome     VARCHAR(255) (nullable)
           - emitente_cpf_cnpj VARCHAR(14)  (nullable)

         Default 'PROPRIA' garante compatibilidade retroativa.
         NAO editar changesets anteriores (0001-0006).
         ================================================================ -->

    <changeSet id="0007-nota-fiscal-add-origem-emitente" author="supersonic-team">
        <comment>
            Adiciona origem (PROPRIA/IMPORTADA) e dados do emitente terceiro
            para suportar notas importadas de XML.
        </comment>

        <addColumn tableName="nota_fiscal">
            <column name="origem" type="VARCHAR(10)" defaultValue="PROPRIA">
                <constraints nullable="false"/>
            </column>
        </addColumn>

        <addColumn tableName="nota_fiscal">
            <column name="emitente_nome" type="VARCHAR(255)">
                <constraints nullable="true"/>
            </column>
        </addColumn>

        <addColumn tableName="nota_fiscal">
            <column name="emitente_cpf_cnpj" type="VARCHAR(14)">
                <constraints nullable="true"/>
            </column>
        </addColumn>

        <rollback>
            <dropColumn tableName="nota_fiscal" columnName="origem"/>
            <dropColumn tableName="nota_fiscal" columnName="emitente_nome"/>
            <dropColumn tableName="nota_fiscal" columnName="emitente_cpf_cnpj"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 5: Incluir no master changelog**

Em `src/main/resources/db/changelog/db.changelog-master.yaml`, após o include do `0006-ncm-cfop-referencia.xml`, adicione:

```yaml
  - include:
      file: db/changelog/changes/0007-nota-fiscal-importacao.xml
```

- [ ] **Step 6: Atualizar o schema H2 de teste**

Em `src/test/resources/schema-nota-fiscal-test.sql`, na tabela `nota_fiscal`, adicione as 3 colunas (ex.: logo após a coluna `data_geracao_xml` e antes de `created_at`):

```sql
    origem                      VARCHAR(10)    NOT NULL DEFAULT 'PROPRIA',
    emitente_nome               VARCHAR(255),
    emitente_cpf_cnpj           VARCHAR(14),
```

- [ ] **Step 7: Verificar contexto e o repo test existente**

Run: `.\mvnw.cmd test -Dtest=AgroContabilApplicationTests,NotaFiscalRepositoryTest`
Expected: PASS (contexto sobe; o repo test continua verde com as colunas novas no schema H2).

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/application.yml src/main/java/br/com/agrocontabil/model/Origem.java src/main/java/br/com/agrocontabil/model/NotaFiscal.java src/main/resources/db/changelog/changes/0007-nota-fiscal-importacao.xml src/main/resources/db/changelog/db.changelog-master.yaml src/test/resources/schema-nota-fiscal-test.sql
git commit -m "feat(import-xml): config multipart, discriminador Origem e campos do emitente (migration 0007)"
```

---

## Task 2: `existsByChaveAcesso` no repositório

**Files:**
- Modify: `src/main/java/br/com/agrocontabil/repository/NotaFiscalRepository.java`
- Test: `src/test/java/br/com/agrocontabil/repository/NotaFiscalRepositoryTest.java`

- [ ] **Step 1: READ `NotaFiscalRepository.java`** para ver os métodos existentes e o estilo.

- [ ] **Step 2: Escrever o teste que falha**

Em `src/test/java/br/com/agrocontabil/repository/NotaFiscalRepositoryTest.java`, adicione um teste. READ o arquivo para reutilizar o `buildNotaFiscal()`/`produtor` já existentes. O teste persiste uma nota com chave e verifica `existsByChaveAcesso`:

```java
  @Test
  @DisplayName("existsByChaveAcesso retorna true para chave existente e false para inexistente")
  void existsByChaveAcesso() {
    NotaFiscal nota = buildNotaFiscal();
    nota.setChaveAcesso("41260612345678000195550010000000011000000017");
    notaFiscalRepository.saveAndFlush(nota);

    assertThat(notaFiscalRepository.existsByChaveAcesso(
        "41260612345678000195550010000000011000000017")).isTrue();
    assertThat(notaFiscalRepository.existsByChaveAcesso("00000000000000000000000000000000000000000000"))
        .isFalse();
  }
```

(Se `buildNotaFiscal()` já setar uma chave, use uma chave distinta de 44 dígitos para o caso "existente".)

- [ ] **Step 3: Rodar, confirmar falha**

Run: `.\mvnw.cmd test -Dtest=NotaFiscalRepositoryTest`
Expected: FAIL (método `existsByChaveAcesso` não existe → erro de compilação).

- [ ] **Step 4: Adicionar o método derivado**

Em `src/main/java/br/com/agrocontabil/repository/NotaFiscalRepository.java`, adicione:

```java
  /**
   * Indica se já existe nota com a chave de acesso informada (dedup de importação).
   *
   * @param chaveAcesso chave de 44 dígitos
   * @return {@code true} se já houver uma nota com essa chave
   */
  boolean existsByChaveAcesso(String chaveAcesso);
```

- [ ] **Step 5: Rodar, confirmar que passa**

Run: `.\mvnw.cmd test -Dtest=NotaFiscalRepositoryTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/br/com/agrocontabil/repository/NotaFiscalRepository.java src/test/java/br/com/agrocontabil/repository/NotaFiscalRepositoryTest.java
git commit -m "feat(import-xml): NotaFiscalRepository.existsByChaveAcesso para dedup"
```

---

## Task 3: `XmlImportacaoException` + `NfeXmlParser` (parse XXE-safe + XSD + JAXB)

**Files:**
- Create: `src/main/java/br/com/agrocontabil/exception/XmlImportacaoException.java`
- Create: `src/main/java/br/com/agrocontabil/nfe/NfeXmlParser.java`
- Test: `src/test/java/br/com/agrocontabil/nfe/NfeXmlParserTest.java`

- [ ] **Step 1: Criar a exceção**

Create `src/main/java/br/com/agrocontabil/exception/XmlImportacaoException.java`:

```java
package br.com.agrocontabil.exception;

/**
 * Lançada quando um XML de nota fiscal não pode ser importado: malformado, sem
 * elemento {@code <NFe>}, inválido contra o XSD, ou com campos obrigatórios ausentes.
 *
 * <p>No fluxo de importação em lote, é capturada por arquivo e registrada como erro
 * no relatório — não chega ao {@code GlobalExceptionHandler}.
 */
public class XmlImportacaoException extends RuntimeException {

  public XmlImportacaoException(String message) {
    super(message);
  }

  public XmlImportacaoException(String message, Throwable cause) {
    super(message, cause);
  }
}
```

- [ ] **Step 2: Escrever o teste que falha**

Create `src/test/java/br/com/agrocontabil/nfe/NfeXmlParserTest.java`. Ele gera um XML de NF-e real via o pipeline (como `GeracaoNotaFiscalServiceTest`), testa parse do `<NFe>` puro, do `<NFe>` dentro de `<nfeProc>`, malformado e XXE:

```java
package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.TributosProperties;
import br.com.agrocontabil.exception.XmlImportacaoException;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.nfe.model.NFe;
import br.com.agrocontabil.service.CalculoIbsCbsService;
import br.com.agrocontabil.service.ChaveAcessoGenerator;
import br.com.agrocontabil.service.GeracaoNotaFiscalService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NfeXmlParser — parse XXE-safe + XSD + JAXB")
class NfeXmlParserTest {

  private NfeXmlParser parser;
  private String nfeXml; // XML real de uma NF-e (bare <NFe>)

  @BeforeEach
  void setUp() {
    parser = new NfeXmlParser(new XsdValidator());

    // Gera um XML de NF-e real via o pipeline (mesma montagem do GeracaoNotaFiscalServiceTest)
    TributosProperties props = new TributosProperties();
    TributosProperties.Ibs ibs = new TributosProperties.Ibs();
    ibs.setAliquota(new BigDecimal("0.0010"));
    TributosProperties.Cbs cbs = new TributosProperties.Cbs();
    cbs.setAliquota(new BigDecimal("0.0090"));
    props.setIbs(ibs);
    props.setCbs(cbs);
    CalculoIbsCbsService calculo = new CalculoIbsCbsService(props);

    GeracaoNotaFiscalService pipeline = new GeracaoNotaFiscalService(
        new FiscalRulesValidator(),
        new NotaFiscalNfeMapper(new ChaveAcessoGenerator()),
        new XmlGenerator(),
        new XsdValidator());

    NotaFiscal nota = buildNota();
    calculo.calcular(nota);
    nfeXml = pipeline.gerarXml(nota);
  }

  @Test
  @DisplayName("parseia XML de NFe puro e retorna o grafo JAXB")
  void parseNfePuro() {
    NFe nfe = parser.parse(nfeXml.getBytes(StandardCharsets.UTF_8));
    assertThat(nfe.getInfNFe()).isNotNull();
    assertThat(nfe.getInfNFe().getId()).startsWith("NFe");
    assertThat(nfe.getInfNFe().getEmit()).isNotNull();
  }

  @Test
  @DisplayName("parseia XML de NFe dentro de nfeProc")
  void parseNfeDentroDeNfeProc() {
    // Remove o prolog <?xml?> e embute o <NFe> num <nfeProc>
    String semProlog = nfeXml.replaceFirst("<\\?xml[^>]*\\?>", "");
    String nfeProc =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<nfeProc versao=\"4.00\" xmlns=\"http://www.portalfiscal.inf.br/nfe\">"
            + semProlog
            + "</nfeProc>";

    NFe nfe = parser.parse(nfeProc.getBytes(StandardCharsets.UTF_8));
    assertThat(nfe.getInfNFe()).isNotNull();
    assertThat(nfe.getInfNFe().getId()).startsWith("NFe");
  }

  @Test
  @DisplayName("XML malformado lanca XmlImportacaoException")
  void xmlMalformado() {
    assertThatThrownBy(() -> parser.parse("<isto nao e xml valido".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(XmlImportacaoException.class);
  }

  @Test
  @DisplayName("XML sem elemento NFe lanca XmlImportacaoException")
  void xmlSemNfe() {
    String semNfe = "<?xml version=\"1.0\"?><outraCoisa></outraCoisa>";
    assertThatThrownBy(() -> parser.parse(semNfe.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(XmlImportacaoException.class);
  }

  @Test
  @DisplayName("XML com DOCTYPE/entidade externa (XXE) e rejeitado com seguranca")
  void xxeRejeitado() {
    String xxe =
        "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
            + "<NFe xmlns=\"http://www.portalfiscal.inf.br/nfe\"><infNFe>&xxe;</infNFe></NFe>";
    // disallow-doctype-decl faz o parser lancar -> XmlImportacaoException (sem expandir a entidade)
    assertThatThrownBy(() -> parser.parse(xxe.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(XmlImportacaoException.class);
  }

  private NotaFiscal buildNota() {
    Produtor produtor = Produtor.builder()
        .id(UUID.randomUUID())
        .nomeCompleto("EMITENTE TERCEIRO LTDA")
        .cpfCnpj("12345678000195")
        .tipoProdutor(TipoProdutor.PESSOA_JURIDICA)
        .regimeTributario(RegimeTributario.SIMPLES)
        .estado("PR")
        .build();

    NotaFiscalItem item = NotaFiscalItem.builder()
        .id(UUID.randomUUID())
        .numeroItem(1)
        .codigoProduto("SOJA001")
        .descricao("Soja em grao")
        .ncm("12010090")
        .cfop("5101")
        .unidade("KG")
        .quantidade(new BigDecimal("1000.0000"))
        .valorUnitario(new BigDecimal("1.0000"))
        .valorTotal(new BigDecimal("1000.00"))
        .cstIbsCbs("101")
        .cClassTrib("010100")
        .build();

    EnderecoDestinatario endDest = EnderecoDestinatario.builder()
        .logradouro("Rua das Flores").numero("100").municipio("Curitiba")
        .codigoMunicipioIbge("4106902").uf("PR").cep("80010000").build();

    NotaFiscal nota = NotaFiscal.builder()
        .id(UUID.randomUUID())
        .numero(1L).serie(1).modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.of(2026, 12, 1, 10, 0, 0, 0, ZoneOffset.of("-03:00")))
        .produtor(produtor)
        .destinatarioNome("COMPRADORA SA")
        .destinatarioCpfCnpj("98765432000199")
        .destinatarioEndereco(endDest)
        .valorTotalProdutos(new BigDecimal("1000.00"))
        .valorTotalNota(new BigDecimal("1000.00"))
        .build();
    nota.addItem(item);
    return nota;
  }
}
```

- [ ] **Step 3: Rodar, confirmar falha**

Run: `.\mvnw.cmd test -Dtest=NfeXmlParserTest`
Expected: FAIL (compilação: `NfeXmlParser` não existe).

- [ ] **Step 4: Implementar `NfeXmlParser`**

Create `src/main/java/br/com/agrocontabil/nfe/NfeXmlParser.java`:

```java
package br.com.agrocontabil.nfe;

import br.com.agrocontabil.exception.XmlImportacaoException;
import br.com.agrocontabil.nfe.model.NFe;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Unmarshaller;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Faz o parse de um XML de NF-e importado para o grafo JAXB {@link NFe}.
 *
 * <p>Pipeline: parse DOM com hardening XXE → localiza o elemento {@code <NFe>}
 * (raiz {@code <nfeProc>} de arquivo autorizado, ou {@code <NFe>} puro) →
 * serializa o nó e valida contra o XSD via {@link XsdValidator} → unmarshal JAXB.
 *
 * <p>Qualquer falha (malformado, sem {@code <NFe>}, XSD inválido) lança
 * {@link XmlImportacaoException}.
 */
@Component
public class NfeXmlParser {

  private static final Logger log = LoggerFactory.getLogger(NfeXmlParser.class);
  private static final String NFE_NS = "http://www.portalfiscal.inf.br/nfe";

  private final XsdValidator xsdValidator;

  public NfeXmlParser(XsdValidator xsdValidator) {
    this.xsdValidator = xsdValidator;
  }

  /**
   * Parseia o XML importado e retorna o grafo JAXB da NF-e.
   *
   * @param xmlBytes conteúdo bruto do arquivo XML
   * @return grafo {@link NFe} extraído e validado contra o XSD
   * @throws XmlImportacaoException se o XML for inválido ou não contiver {@code <NFe>}
   */
  public NFe parse(byte[] xmlBytes) {
    Document doc = parseSeguro(xmlBytes);

    NodeList nfeNodes = doc.getElementsByTagNameNS(NFE_NS, "NFe");
    if (nfeNodes.getLength() == 0) {
      throw new XmlImportacaoException(
          "XML nao contem o elemento <NFe> no namespace da NF-e.");
    }
    Node nfeNode = nfeNodes.item(0);

    String nfeXml = serializar(nfeNode);

    // Valida o <NFe> contra o XSD oficial (XsdValidator tolera ausencia de assinatura).
    xsdValidator.validar(nfeXml);

    return unmarshal(nfeNode);
  }

  private Document parseSeguro(byte[] xmlBytes) {
    try {
      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      dbf.setNamespaceAware(true);
      // Hardening XXE
      dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
      dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      dbf.setXIncludeAware(false);
      dbf.setExpandEntityReferences(false);
      DocumentBuilder builder = dbf.newDocumentBuilder();
      return builder.parse(new ByteArrayInputStream(xmlBytes));
    } catch (Exception e) {
      log.warn("Falha ao parsear XML importado: {}", e.getMessage());
      throw new XmlImportacaoException("XML invalido ou malformado: " + e.getMessage(), e);
    }
  }

  private String serializar(Node node) {
    try {
      TransformerFactory tf = TransformerFactory.newInstance();
      tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
      Transformer transformer = tf.newTransformer();
      transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
      StringWriter writer = new StringWriter();
      transformer.transform(new DOMSource(node), new StreamResult(writer));
      return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + writer;
    } catch (Exception e) {
      throw new XmlImportacaoException("Falha ao serializar o elemento NFe: " + e.getMessage(), e);
    }
  }

  private NFe unmarshal(Node nfeNode) {
    try {
      JAXBContext context = JAXBContext.newInstance(NFe.class);
      Unmarshaller unmarshaller = context.createUnmarshaller();
      return (NFe) unmarshaller.unmarshal(nfeNode);
    } catch (Exception e) {
      throw new XmlImportacaoException("Falha ao desserializar a NF-e (JAXB): " + e.getMessage(), e);
    }
  }
}
```

- [ ] **Step 5: Rodar, confirmar que passa**

Run: `.\mvnw.cmd test -Dtest=NfeXmlParserTest`
Expected: PASS (5 cenários). Caso o teste `parseNfeDentroDeNfeProc` falhe na validação XSD por algum detalhe do wrapper, lembre-se: validamos apenas o nó `<NFe>` serializado, não o `nfeProc` — confirme que `serializar(nfeNode)` está serializando o nó NFe (e não o documento todo).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/br/com/agrocontabil/exception/XmlImportacaoException.java src/main/java/br/com/agrocontabil/nfe/NfeXmlParser.java src/test/java/br/com/agrocontabil/nfe/NfeXmlParserTest.java
git commit -m "feat(import-xml): NfeXmlParser parseia NFe (nfeProc/NFe) com XXE-safe + XSD + JAXB"
```

---

## Task 4: `NfeImportMapper` (JAXB NFe → NotaFiscal)

**Files:**
- Create: `src/main/java/br/com/agrocontabil/nfe/NfeImportMapper.java`
- Test: `src/test/java/br/com/agrocontabil/nfe/NfeImportMapperTest.java`

- [ ] **Step 1: Escrever o teste que falha**

Create `src/test/java/br/com/agrocontabil/nfe/NfeImportMapperTest.java`. Reutiliza o pipeline para gerar um XML real, parseia com `NfeXmlParser`, mapeia e assere os campos:

```java
package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.config.TributosProperties;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Origem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoProdutor;
import br.com.agrocontabil.nfe.model.NFe;
import br.com.agrocontabil.service.CalculoIbsCbsService;
import br.com.agrocontabil.service.ChaveAcessoGenerator;
import br.com.agrocontabil.service.GeracaoNotaFiscalService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NfeImportMapper — JAXB NFe -> NotaFiscal")
class NfeImportMapperTest {

  private NfeImportMapper mapper;
  private NfeXmlParser parser;
  private String nfeXml;

  @BeforeEach
  void setUp() {
    mapper = new NfeImportMapper();
    parser = new NfeXmlParser(new XsdValidator());

    TributosProperties props = new TributosProperties();
    TributosProperties.Ibs ibs = new TributosProperties.Ibs();
    ibs.setAliquota(new BigDecimal("0.0010"));
    TributosProperties.Cbs cbs = new TributosProperties.Cbs();
    cbs.setAliquota(new BigDecimal("0.0090"));
    props.setIbs(ibs);
    props.setCbs(cbs);
    CalculoIbsCbsService calculo = new CalculoIbsCbsService(props);
    GeracaoNotaFiscalService pipeline = new GeracaoNotaFiscalService(
        new FiscalRulesValidator(),
        new NotaFiscalNfeMapper(new ChaveAcessoGenerator()),
        new XmlGenerator(),
        new XsdValidator());

    NotaFiscal origem = buildNota();
    calculo.calcular(origem);
    nfeXml = pipeline.gerarXml(origem);
  }

  @Test
  @DisplayName("extrai campos principais e marca como IMPORTADA/AUTORIZADA")
  void extraiCampos() {
    NFe nfe = parser.parse(nfeXml.getBytes(StandardCharsets.UTF_8));
    Produtor dono = Produtor.builder().id(UUID.randomUUID()).nomeCompleto("Dono").build();

    NotaFiscal nota = mapper.toEntity(nfe, nfeXml, dono);

    assertThat(nota.getOrigem()).isEqualTo(Origem.IMPORTADA);
    assertThat(nota.getStatus()).isEqualTo(StatusNotaFiscal.AUTORIZADA);
    assertThat(nota.getProdutor()).isSameAs(dono);
    assertThat(nota.getChaveAcesso()).hasSize(44);
    assertThat(nota.getModelo()).isEqualTo("55");
    assertThat(nota.getNumero()).isEqualTo(1L);
    // emitente terceiro = o emit do XML gerado (CNPJ 12345678000195)
    assertThat(nota.getEmitenteCpfCnpj()).isEqualTo("12345678000195");
    assertThat(nota.getEmitenteNome()).isNotBlank();
    // destinatario = o dest do XML
    assertThat(nota.getDestinatarioCpfCnpj()).isEqualTo("98765432000199");
    assertThat(nota.getItens()).hasSize(1);
    assertThat(nota.getItens().get(0).getNcm()).isEqualTo("12010090");
    assertThat(nota.getItens().get(0).getCfop()).isEqualTo("5101");
    assertThat(nota.getValorTotalProdutos()).isEqualByComparingTo("1000.00");
    assertThat(nota.getXmlGerado()).isEqualTo(nfeXml);
    assertThat(nota.getDataGeracaoXml()).isNotNull();
  }

  private NotaFiscal buildNota() {
    Produtor produtor = Produtor.builder()
        .id(UUID.randomUUID()).nomeCompleto("EMITENTE TERCEIRO LTDA")
        .cpfCnpj("12345678000195").tipoProdutor(TipoProdutor.PESSOA_JURIDICA)
        .regimeTributario(RegimeTributario.SIMPLES).estado("PR").build();
    NotaFiscalItem item = NotaFiscalItem.builder()
        .id(UUID.randomUUID()).numeroItem(1).codigoProduto("SOJA001").descricao("Soja em grao")
        .ncm("12010090").cfop("5101").unidade("KG")
        .quantidade(new BigDecimal("1000.0000")).valorUnitario(new BigDecimal("1.0000"))
        .valorTotal(new BigDecimal("1000.00")).cstIbsCbs("101").cClassTrib("010100").build();
    EnderecoDestinatario endDest = EnderecoDestinatario.builder()
        .logradouro("Rua das Flores").numero("100").municipio("Curitiba")
        .codigoMunicipioIbge("4106902").uf("PR").cep("80010000").build();
    NotaFiscal nota = NotaFiscal.builder()
        .id(UUID.randomUUID()).numero(1L).serie(1).modelo("55")
        .naturezaOperacao("Venda de producao rural")
        .dataEmissao(OffsetDateTime.of(2026, 12, 1, 10, 0, 0, 0, ZoneOffset.of("-03:00")))
        .produtor(produtor).destinatarioNome("COMPRADORA SA")
        .destinatarioCpfCnpj("98765432000199").destinatarioEndereco(endDest)
        .valorTotalProdutos(new BigDecimal("1000.00")).valorTotalNota(new BigDecimal("1000.00"))
        .build();
    nota.addItem(item);
    return nota;
  }
}
```

- [ ] **Step 2: Rodar, confirmar falha**

Run: `.\mvnw.cmd test -Dtest=NfeImportMapperTest`
Expected: FAIL (compilação: `NfeImportMapper` não existe).

- [ ] **Step 3: Implementar `NfeImportMapper`**

Create `src/main/java/br/com/agrocontabil/nfe/NfeImportMapper.java`:

```java
package br.com.agrocontabil.nfe;

import br.com.agrocontabil.exception.XmlImportacaoException;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Origem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoDocumento;
import br.com.agrocontabil.nfe.model.Dest;
import br.com.agrocontabil.nfe.model.Det;
import br.com.agrocontabil.nfe.model.Emit;
import br.com.agrocontabil.nfe.model.EnderDest;
import br.com.agrocontabil.nfe.model.Ide;
import br.com.agrocontabil.nfe.model.InfNFe;
import br.com.agrocontabil.nfe.model.NFe;
import br.com.agrocontabil.nfe.model.Prod;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;

/**
 * Mapeia o grafo JAXB {@link NFe} (XML importado de terceiro) para a entidade
 * {@link NotaFiscal}, marcando-a como {@link Origem#IMPORTADA} e
 * {@link StatusNotaFiscal#AUTORIZADA}.
 *
 * <p>O emitente do XML (terceiro) vai para {@code emitenteNome}/{@code emitenteCpfCnpj};
 * o {@code <dest>} do XML preenche o bloco destinatário; o {@code produtor} é o dono
 * autenticado (passado por parâmetro). Campos IBS/CBS por item ficam fora deste mapper
 * (o XML completo é preservado em {@code xmlGerado}).
 */
@Component
public class NfeImportMapper {

  /**
   * Constrói uma {@link NotaFiscal} importada a partir do grafo JAXB.
   *
   * @param nfe         grafo da NF-e importada
   * @param xmlOriginal XML original (armazenado em {@code xmlGerado})
   * @param dono        produtor autenticado, dono da nota importada
   * @return entidade pronta para persistência
   * @throws XmlImportacaoException se campos obrigatórios estiverem ausentes/inválidos
   */
  public NotaFiscal toEntity(NFe nfe, String xmlOriginal, Produtor dono) {
    InfNFe inf = nfe.getInfNFe();
    if (inf == null || inf.getIde() == null || inf.getEmit() == null) {
      throw new XmlImportacaoException("XML da NF-e sem grupos obrigatorios (ide/emit).");
    }
    Ide ide = inf.getIde();
    Emit emit = inf.getEmit();

    NotaFiscal nota = NotaFiscal.builder()
        .chaveAcesso(extrairChave(inf.getId()))
        .numero(parseLong(ide.getNNF(), "ide.nNF"))
        .serie(parseInt(ide.getSerie(), "ide.serie"))
        .modelo(ide.getMod() != null ? ide.getMod() : "55")
        .tipoDocumento(TipoDocumento.NFE)
        .origem(Origem.IMPORTADA)
        .status(StatusNotaFiscal.AUTORIZADA)
        .naturezaOperacao(ide.getNatOp() != null ? ide.getNatOp() : "IMPORTADA")
        .dataEmissao(parseData(ide.getDhEmi()))
        .produtor(dono)
        .emitenteNome(emit.getXNome())
        .emitenteCpfCnpj(emit.getCnpj() != null ? emit.getCnpj() : emit.getCpf())
        .versaoLayoutNfe(inf.getVersao())
        .xmlGerado(xmlOriginal)
        .dataGeracaoXml(OffsetDateTime.now())
        .valorTotalProdutos(extrairVProd(inf))
        .valorTotalNota(extrairVnf(inf))
        .build();

    aplicarDestinatario(nota, inf.getDest());
    aplicarItens(nota, inf);
    return nota;
  }

  private void aplicarDestinatario(NotaFiscal nota, Dest dest) {
    if (dest == null) {
      nota.setDestinatarioNome("DESTINATARIO NAO INFORMADO");
      nota.setDestinatarioCpfCnpj("00000000000");
      return;
    }
    nota.setDestinatarioNome(dest.getXNome() != null ? dest.getXNome() : "DESTINATARIO");
    nota.setDestinatarioCpfCnpj(dest.getCnpj() != null ? dest.getCnpj() : dest.getCpf());
    nota.setDestinatarioIe(dest.getIe());
    EnderDest end = dest.getEnderDest();
    if (end != null) {
      nota.setDestinatarioEndereco(EnderecoDestinatario.builder()
          .logradouro(end.getXLgr())
          .numero(end.getNro())
          .municipio(end.getXMun())
          .codigoMunicipioIbge(end.getCMun())
          .uf(end.getUf())
          .cep(end.getCep())
          .build());
    }
  }

  private void aplicarItens(NotaFiscal nota, InfNFe inf) {
    if (inf.getDet() == null) {
      return;
    }
    for (Det det : inf.getDet()) {
      Prod prod = det.getProd();
      if (prod == null) {
        continue;
      }
      NotaFiscalItem item = NotaFiscalItem.builder()
          .numeroItem(parseInt(det.getNItem(), "det.nItem"))
          .codigoProduto(prod.getCProd())
          .descricao(prod.getXProd())
          .ncm(prod.getNcm())
          .cfop(prod.getCfop())
          .unidade(prod.getUCom())
          .quantidade(parseDecimal(prod.getQCom(), "prod.qCom"))
          .valorUnitario(parseDecimal(prod.getVUnCom(), "prod.vUnCom"))
          .valorTotal(parseDecimal(prod.getVProd(), "prod.vProd"))
          .build();
      nota.addItem(item);
    }
  }

  private String extrairChave(String id) {
    if (id == null || !id.startsWith("NFe") || id.length() != 47) {
      throw new XmlImportacaoException("Atributo Id da infNFe ausente ou invalido: " + id);
    }
    return id.substring(3);
  }

  private BigDecimal extrairVProd(InfNFe inf) {
    if (inf.getTotal() != null && inf.getTotal().getIcmsTot() != null
        && inf.getTotal().getIcmsTot().getVProd() != null) {
      return new BigDecimal(inf.getTotal().getIcmsTot().getVProd());
    }
    return BigDecimal.ZERO;
  }

  private BigDecimal extrairVnf(InfNFe inf) {
    if (inf.getTotal() != null && inf.getTotal().getIcmsTot() != null
        && inf.getTotal().getIcmsTot().getVNF() != null) {
      return new BigDecimal(inf.getTotal().getIcmsTot().getVNF());
    }
    return BigDecimal.ZERO;
  }

  private long parseLong(String value, String campo) {
    try {
      return Long.parseLong(value.trim());
    } catch (Exception e) {
      throw new XmlImportacaoException("Campo numerico invalido (" + campo + "): " + value);
    }
  }

  private int parseInt(String value, String campo) {
    try {
      return Integer.parseInt(value.trim());
    } catch (Exception e) {
      throw new XmlImportacaoException("Campo inteiro invalido (" + campo + "): " + value);
    }
  }

  private BigDecimal parseDecimal(String value, String campo) {
    try {
      return new BigDecimal(value.trim());
    } catch (Exception e) {
      throw new XmlImportacaoException("Campo decimal invalido (" + campo + "): " + value);
    }
  }

  private OffsetDateTime parseData(String dhEmi) {
    try {
      return OffsetDateTime.parse(dhEmi.trim());
    } catch (Exception e) {
      throw new XmlImportacaoException("Data de emissao (dhEmi) invalida: " + dhEmi);
    }
  }
}
```

> Confirme que `IcmsTot` expõe `getVProd()` e `getVNF()` (o `NotaFiscalNfeMapper` os preenche via `setVProd`/`setVNF`, então os getters existem). Confirme que `InfNFe` expõe `getTotal()`/`getVersao()`/`getId()` (já existem).

- [ ] **Step 4: Rodar, confirmar que passa**

Run: `.\mvnw.cmd test -Dtest=NfeImportMapperTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/br/com/agrocontabil/nfe/NfeImportMapper.java src/test/java/br/com/agrocontabil/nfe/NfeImportMapperTest.java
git commit -m "feat(import-xml): NfeImportMapper extrai NotaFiscal importada do grafo JAXB"
```

---

## Task 5: DTOs do relatório + `ImportacaoNotaFiscalService`

**Files:**
- Create: `src/main/java/br/com/agrocontabil/dto/ImportacaoItemResultado.java`
- Create: `src/main/java/br/com/agrocontabil/dto/ImportacaoLoteResponse.java`
- Create: `src/main/java/br/com/agrocontabil/service/ImportacaoNotaFiscalService.java`
- Test: `src/test/java/br/com/agrocontabil/service/ImportacaoNotaFiscalServiceTest.java`

- [ ] **Step 1: Criar os DTOs**

Create `src/main/java/br/com/agrocontabil/dto/ImportacaoItemResultado.java`:

```java
package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Resultado da importação de um único arquivo XML.
 */
@Schema(description = "Resultado da importacao de um arquivo XML")
public record ImportacaoItemResultado(

    @Schema(description = "Nome do arquivo enviado", example = "nota1.xml")
    String arquivo,

    @Schema(description = "Indica se o arquivo foi importado com sucesso", example = "true")
    boolean sucesso,

    @Schema(description = "Id da nota criada (quando sucesso)")
    UUID notaId,

    @Schema(description = "Chave de acesso da nota (quando sucesso)")
    String chaveAcesso,

    @Schema(description = "Motivo do erro (quando falha)")
    String erro
) {

  /** Cria um resultado de sucesso. */
  public static ImportacaoItemResultado sucesso(String arquivo, UUID notaId, String chave) {
    return new ImportacaoItemResultado(arquivo, true, notaId, chave, null);
  }

  /** Cria um resultado de erro. */
  public static ImportacaoItemResultado erro(String arquivo, String motivo) {
    return new ImportacaoItemResultado(arquivo, false, null, null, motivo);
  }
}
```

Create `src/main/java/br/com/agrocontabil/dto/ImportacaoLoteResponse.java`:

```java
package br.com.agrocontabil.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Relatório consolidado da importação de um lote de arquivos XML.
 */
@Schema(description = "Relatorio de importacao de um lote de XMLs")
public record ImportacaoLoteResponse(

    @Schema(description = "Total de arquivos recebidos", example = "3")
    int total,

    @Schema(description = "Quantidade importada com sucesso", example = "2")
    int importados,

    @Schema(description = "Quantidade que falhou", example = "1")
    int falhas,

    @Schema(description = "Resultado por arquivo")
    List<ImportacaoItemResultado> resultados
) {

  /** Monta o relatório a partir da lista de resultados por arquivo. */
  public static ImportacaoLoteResponse de(List<ImportacaoItemResultado> resultados) {
    int ok = (int) resultados.stream().filter(ImportacaoItemResultado::sucesso).count();
    return new ImportacaoLoteResponse(resultados.size(), ok, resultados.size() - ok, resultados);
  }
}
```

- [ ] **Step 2: Escrever o teste que falha**

Create `src/test/java/br/com/agrocontabil/service/ImportacaoNotaFiscalServiceTest.java`:

```java
package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.dto.ImportacaoLoteResponse;
import br.com.agrocontabil.exception.XmlImportacaoException;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.nfe.NfeImportMapper;
import br.com.agrocontabil.nfe.NfeXmlParser;
import br.com.agrocontabil.nfe.model.NFe;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
@DisplayName("ImportacaoNotaFiscalService — lote com relatorio por arquivo")
class ImportacaoNotaFiscalServiceTest {

  @Mock private NfeXmlParser parser;
  @Mock private NfeImportMapper mapper;
  @Mock private NotaFiscalRepository repository;
  @Mock private AuthenticatedProdutorProvider produtorProvider;

  @InjectMocks private ImportacaoNotaFiscalService service;

  private Produtor produtor;

  @BeforeEach
  void setUp() {
    produtor = Produtor.builder().id(UUID.randomUUID()).nomeCompleto("Dono").build();
  }

  private MultipartFile arquivo(String nome) {
    return new MockMultipartFile(nome, nome, "application/xml", "<NFe/>".getBytes());
  }

  @Test
  @DisplayName("lote com um sucesso e um erro produz relatorio correto")
  void loteSucessoEErro() {
    when(produtorProvider.get()).thenReturn(produtor);

    NFe nfeOk = new NFe();
    when(parser.parse(any())).thenReturn(nfeOk).thenThrow(
        new XmlImportacaoException("XML invalido"));

    NotaFiscal notaMapeada = NotaFiscal.builder()
        .id(UUID.randomUUID()).chaveAcesso("41260612345678000195550010000000011000000017").build();
    when(mapper.toEntity(any(), any(), any())).thenReturn(notaMapeada);
    when(repository.existsByChaveAcesso(any())).thenReturn(false);
    when(repository.save(any(NotaFiscal.class))).thenAnswer(inv -> inv.getArgument(0));

    ImportacaoLoteResponse resp = service.importar(List.of(arquivo("ok.xml"), arquivo("ruim.xml")));

    assertThat(resp.total()).isEqualTo(2);
    assertThat(resp.importados()).isEqualTo(1);
    assertThat(resp.falhas()).isEqualTo(1);
    assertThat(resp.resultados()).anyMatch(r -> r.sucesso() && "ok.xml".equals(r.arquivo()));
    assertThat(resp.resultados()).anyMatch(r -> !r.sucesso() && "ruim.xml".equals(r.arquivo()));
  }

  @Test
  @DisplayName("chave ja importada vira erro no relatorio, sem salvar")
  void chaveDuplicada() {
    when(produtorProvider.get()).thenReturn(produtor);
    when(parser.parse(any())).thenReturn(new NFe());
    NotaFiscal notaMapeada = NotaFiscal.builder()
        .id(UUID.randomUUID()).chaveAcesso("41260612345678000195550010000000011000000017").build();
    when(mapper.toEntity(any(), any(), any())).thenReturn(notaMapeada);
    when(repository.existsByChaveAcesso(any())).thenReturn(true);

    ImportacaoLoteResponse resp = service.importar(List.of(arquivo("dup.xml")));

    assertThat(resp.importados()).isZero();
    assertThat(resp.falhas()).isEqualTo(1);
    assertThat(resp.resultados().get(0).erro()).containsIgnoringCase("importada");
  }
}
```

- [ ] **Step 3: Rodar, confirmar falha**

Run: `.\mvnw.cmd test -Dtest=ImportacaoNotaFiscalServiceTest`
Expected: FAIL (compilação: `ImportacaoNotaFiscalService` não existe).

- [ ] **Step 4: Implementar o serviço**

Create `src/main/java/br/com/agrocontabil/service/ImportacaoNotaFiscalService.java`:

```java
package br.com.agrocontabil.service;

import br.com.agrocontabil.dto.ImportacaoItemResultado;
import br.com.agrocontabil.dto.ImportacaoLoteResponse;
import br.com.agrocontabil.exception.XmlImportacaoException;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.nfe.NfeImportMapper;
import br.com.agrocontabil.nfe.NfeXmlParser;
import br.com.agrocontabil.nfe.model.NFe;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Importa XML de notas fiscais de terceiros em lote.
 *
 * <p>Para cada arquivo: parse ({@link NfeXmlParser}) → mapeamento
 * ({@link NfeImportMapper}) → dedup por chave → persistência, vinculando ao produtor
 * autenticado. Falhas são capturadas por arquivo; arquivos válidos são importados
 * mesmo que outros falhem. Retorna um relatório consolidado.
 */
@Service
public class ImportacaoNotaFiscalService {

  private static final Logger log = LoggerFactory.getLogger(ImportacaoNotaFiscalService.class);

  private final NfeXmlParser parser;
  private final NfeImportMapper mapper;
  private final NotaFiscalRepository repository;
  private final AuthenticatedProdutorProvider produtorProvider;

  public ImportacaoNotaFiscalService(
      NfeXmlParser parser,
      NfeImportMapper mapper,
      NotaFiscalRepository repository,
      AuthenticatedProdutorProvider produtorProvider) {
    this.parser = parser;
    this.mapper = mapper;
    this.repository = repository;
    this.produtorProvider = produtorProvider;
  }

  /**
   * Importa cada arquivo do lote, devolvendo o relatório por arquivo.
   *
   * @param arquivos arquivos XML enviados
   * @return relatório consolidado (total/importados/falhas/resultados)
   */
  @Transactional
  public ImportacaoLoteResponse importar(List<MultipartFile> arquivos) {
    Produtor dono = produtorProvider.get();
    List<ImportacaoItemResultado> resultados = new ArrayList<>();

    for (MultipartFile arquivo : arquivos) {
      String nome = arquivo.getOriginalFilename() != null
          ? arquivo.getOriginalFilename() : "arquivo.xml";
      resultados.add(importarUm(arquivo, nome, dono));
    }
    return ImportacaoLoteResponse.de(resultados);
  }

  private ImportacaoItemResultado importarUm(MultipartFile arquivo, String nome, Produtor dono) {
    try {
      NFe nfe = parser.parse(arquivo.getBytes());
      NotaFiscal nota = mapper.toEntity(nfe, new String(arquivo.getBytes(),
          java.nio.charset.StandardCharsets.UTF_8), dono);

      if (repository.existsByChaveAcesso(nota.getChaveAcesso())) {
        return ImportacaoItemResultado.erro(nome, "Nota ja importada (chave " + nota.getChaveAcesso() + ").");
      }

      NotaFiscal salva = repository.save(nota);
      log.info("XML importado: arquivo={} notaId={} chave={} produtor={}",
          nome, salva.getId(), salva.getChaveAcesso(), dono.getId());
      return ImportacaoItemResultado.sucesso(nome, salva.getId(), salva.getChaveAcesso());

    } catch (XmlImportacaoException e) {
      log.warn("Falha ao importar {}: {}", nome, e.getMessage());
      return ImportacaoItemResultado.erro(nome, e.getMessage());
    } catch (DataIntegrityViolationException e) {
      log.warn("Chave duplicada ao importar {}", nome);
      return ImportacaoItemResultado.erro(nome, "Nota ja importada (chave duplicada).");
    } catch (IOException e) {
      log.warn("Erro de leitura ao importar {}: {}", nome, e.getMessage());
      return ImportacaoItemResultado.erro(nome, "Falha ao ler o arquivo: " + e.getMessage());
    }
  }
}
```

- [ ] **Step 5: Rodar, confirmar que passa**

Run: `.\mvnw.cmd test -Dtest=ImportacaoNotaFiscalServiceTest`
Expected: PASS (2 cenários).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/br/com/agrocontabil/dto/ImportacaoItemResultado.java src/main/java/br/com/agrocontabil/dto/ImportacaoLoteResponse.java src/main/java/br/com/agrocontabil/service/ImportacaoNotaFiscalService.java src/test/java/br/com/agrocontabil/service/ImportacaoNotaFiscalServiceTest.java
git commit -m "feat(import-xml): ImportacaoNotaFiscalService importa lote com relatorio por arquivo"
```

---

## Task 6: Endpoint `POST /api/notas/importar` + handler de tamanho + verificação final

**Files:**
- Modify: `src/main/java/br/com/agrocontabil/controller/NotaFiscalController.java`
- Modify: `src/main/java/br/com/agrocontabil/exception/GlobalExceptionHandler.java`
- Test: `src/test/java/br/com/agrocontabil/controller/NotaFiscalControllerTest.java`

- [ ] **Step 1: READ** `NotaFiscalController.java` e `NotaFiscalControllerTest.java` para integrar com o estilo e os `@MockBean` existentes.

- [ ] **Step 2: Escrever os testes que falham (controller)**

Em `src/test/java/br/com/agrocontabil/controller/NotaFiscalControllerTest.java`:

(a) Imports:
```java
import br.com.agrocontabil.dto.ImportacaoItemResultado;
import br.com.agrocontabil.dto.ImportacaoLoteResponse;
import br.com.agrocontabil.service.ImportacaoNotaFiscalService;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
```

(b) Adicione um `@MockBean` para o serviço de importação (junto aos outros):
```java
  @MockBean
  private ImportacaoNotaFiscalService importacaoNotaFiscalService;
```

(c) Adicione dois testes (após o teste de DELETE, por exemplo):
```java
  @Test
  @DisplayName("POST /api/notas/importar com 2 arquivos retorna 200 com relatorio")
  void importarMultiplosArquivosRetorna200() throws Exception {
    UUID notaId = UUID.randomUUID();
    ImportacaoLoteResponse relatorio = ImportacaoLoteResponse.de(java.util.List.of(
        ImportacaoItemResultado.sucesso("nota1.xml", notaId, "41260612345678000195550010000000011000000017"),
        ImportacaoItemResultado.erro("nota2.xml", "XML invalido")));
    when(importacaoNotaFiscalService.importar(org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(relatorio);

    org.springframework.mock.web.MockMultipartFile a1 =
        new org.springframework.mock.web.MockMultipartFile(
            "arquivos", "nota1.xml", "application/xml", "<NFe/>".getBytes());
    org.springframework.mock.web.MockMultipartFile a2 =
        new org.springframework.mock.web.MockMultipartFile(
            "arquivos", "nota2.xml", "application/xml", "<x/>".getBytes());

    mockMvc.perform(multipart("/api/notas/importar").file(a1).file(a2))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.importados").value(1))
        .andExpect(jsonPath("$.falhas").value(1))
        .andExpect(jsonPath("$.resultados").isArray());
  }

  @Test
  @DisplayName("POST /api/notas/importar sem arquivos retorna 400")
  void importarSemArquivosRetorna400() throws Exception {
    when(importacaoNotaFiscalService.importar(org.mockito.ArgumentMatchers.anyList()))
        .thenThrow(new IllegalArgumentException("Nenhum arquivo enviado"));

    // multipart sem nenhum .file() — o @RequestParam exigido ausente gera 400
    mockMvc.perform(multipart("/api/notas/importar"))
        .andExpect(status().isBadRequest());
  }
```

> Nota: quando o `@RequestParam("arquivos")` é exigido e nenhum arquivo é enviado, o Spring retorna 400 (`MissingServletRequestPartException`/`MissingServletRequestParameterException`), já tratado como 400 pela infra padrão. O segundo teste valida esse caminho.

- [ ] **Step 3: Rodar, confirmar falha**

Run: `.\mvnw.cmd test -Dtest=NotaFiscalControllerTest`
Expected: FAIL (endpoint `/importar` e o `@MockBean` do serviço não existem → 404/erro de contexto).

- [ ] **Step 4: Adicionar o endpoint no controller**

Em `src/main/java/br/com/agrocontabil/controller/NotaFiscalController.java`:

(a) Imports:
```java
import br.com.agrocontabil.dto.ImportacaoLoteResponse;
import br.com.agrocontabil.service.ImportacaoNotaFiscalService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
```

(b) Injete o serviço: adicione um campo `private final ImportacaoNotaFiscalService importacaoNotaFiscalService;` e inclua-o no construtor (parâmetro + atribuição), ao lado do `notaFiscalService` existente.

(c) Adicione o endpoint:
```java
  /**
   * Importa XML de notas fiscais de terceiros (upload múltiplo).
   *
   * <p>Cada arquivo é processado independentemente; o relatório retorna sucesso/erro
   * por arquivo. Arquivos válidos são importados mesmo que outros falhem.
   */
  @PostMapping(value = "/importar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Operation(
      summary = "Importa XML de notas fiscais de terceiros",
      description = "Recebe um ou mais arquivos XML (multipart) e importa cada um. "
          + "Retorna um relatorio por arquivo (sucesso/erro). Notas importadas ficam com "
          + "origem IMPORTADA e status AUTORIZADA.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Relatorio de importacao gerado"),
      @ApiResponse(responseCode = "400", description = "Nenhum arquivo enviado ou requisicao invalida",
          content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<ImportacaoLoteResponse> importar(
      @RequestParam("arquivos") List<MultipartFile> arquivos) {
    if (arquivos == null || arquivos.isEmpty()) {
      throw new IllegalArgumentException("Nenhum arquivo enviado para importacao.");
    }
    return ResponseEntity.ok(importacaoNotaFiscalService.importar(arquivos));
  }
```

- [ ] **Step 5: Mapear `MaxUploadSizeExceededException` para 400 no handler**

Em `src/main/java/br/com/agrocontabil/exception/GlobalExceptionHandler.java`, adicione (antes do `handleIllegalArgument`):

```java
    /** Upload acima do limite configurado (spring.servlet.multipart.max-file-size). */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUpload(
            org.springframework.web.multipart.MaxUploadSizeExceededException ex,
            HttpServletRequest request) {
        log.warn("Upload acima do limite: uri={}", request.getRequestURI());
        ApiError body = ApiError.of(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Arquivo acima do tamanho maximo permitido para importacao.",
                request.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }
```

- [ ] **Step 6: Rodar o teste do controller**

Run: `.\mvnw.cmd test -Dtest=NotaFiscalControllerTest`
Expected: PASS (todos, incluindo os 2 novos de importação).

> Se o teste "sem arquivos" não der exatamente 400 (dependendo de como o Spring trata o part ausente), ajuste para enviar um part vazio ou verifique o status real e alinhe a asserção ao comportamento correto (o objetivo é: ausência de arquivos não cria nota e não dá 5xx).

- [ ] **Step 7: Suíte completa (verificação final)**

Run: `.\mvnw.cmd test`
Expected: BUILD SUCCESS — todos os testes verdes.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/br/com/agrocontabil/controller/NotaFiscalController.java src/main/java/br/com/agrocontabil/exception/GlobalExceptionHandler.java src/test/java/br/com/agrocontabil/controller/NotaFiscalControllerTest.java
git commit -m "feat(import-xml): endpoint POST /api/notas/importar (upload multiplo) + limite de tamanho"
```

---

## Self-Review (preenchido pelo autor do plano)

**Cobertura do spec:**
- Endpoint `POST /api/notas/importar` com `MultipartFile` (múltiplo) → Task 6 ✓
- Parser JAXB (nfeProc/NFe, XXE-safe) → Task 3 ✓
- Validação XSD do XML importado → Task 3 (reusa `XsdValidator`) ✓
- Extração para `NotaFiscal` → Task 4 ✓
- Upload múltiplo (`List<MultipartFile>`) → Tasks 5, 6 ✓
- `spring.servlet.multipart.max-file-size` → Task 1 ✓
- Discriminador `Origem` + emitente + migration → Task 1 ✓
- Dedup por chave → Tasks 2, 5 ✓
- DoD "NFe real importado / dados via JAXB / múltiplos arquivos" → Tasks 4 (extração de XML gerado real), 6 (lote) ✓

**Consistência de tipos/assinaturas:**
- `NfeXmlParser.parse(byte[])→NFe` (Task 3) usado em 4, 5 ✓
- `NfeImportMapper.toEntity(NFe, String, Produtor)→NotaFiscal` (Task 4) usado em 5 ✓
- `NotaFiscalRepository.existsByChaveAcesso(String)` (Task 2) usado em 5 ✓
- `ImportacaoLoteResponse.de(List)` / `ImportacaoItemResultado.sucesso/erro` (Task 5) usados em 5, 6 ✓
- `XmlImportacaoException` (Task 3) lançada em 3, 4; capturada em 5 ✓
- `Origem {PROPRIA, IMPORTADA}` e campos `emitenteNome/emitenteCpfCnpj` consistentes (Tasks 1, 4) ✓

**Riscos sinalizados:**
- `@WebMvcTest` precisa do `@MockBean ImportacaoNotaFiscalService` (Task 6); os `@MockBean` de repositórios dos validators já existem.
- Adição de colunas à entidade exige atualizar `schema-nota-fiscal-test.sql` (Task 1, Step 6) — senão `NotaFiscalRepositoryTest` quebra.
- Extração de IBS/CBS por item fica fora de escopo (XML completo preservado em `xmlGerado`); `valorTotalIbs/Cbs` da nota importada ficam nulos (nullable) — consistente com o modelo.
