# PR B — Assinatura Digital NF-e (#17) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> **Pré-requisito:** PR A (contrato de erro estruturado) já mergeado — `DomainException`, `ErrorCode`, `ErrorDetail` disponíveis.

**Goal:** Assinar digitalmente a NF-e modelo 55 (enveloped, padrão SEFAZ) com Apache Santuario e certificado sandbox autoassinado, validar a assinatura localmente, e exigir `ds:Signature` no XSD.

**Architecture:** Nova etapa isolada no pipeline de geração: `KeyStoreCertificadoProvider` carrega o `.p12`; `NfeAssinador` produz o XML assinado; `AssinaturaValidator` confere a assinatura. Integra no `GeracaoNotaFiscalService` entre `marshal` e `XsdValidator`, e reverte o hardening que tolerava assinatura ausente.

**Tech Stack:** Java 21, Spring Boot 3.5.14, Apache Santuario (`org.apache.santuario:xmlsec`), JAXP/DOM, JUnit 5, Mockito.

**Branch:** `feat/nfe-assinatura-digital` (worktree isolado em `.claude/.worktrees/`).

**Algoritmos (cravados SEFAZ — MOC NF-e):** enveloped no `infNFe`; C14N inclusiva `http://www.w3.org/TR/2001/REC-xml-c14n-20010315`; `SignatureMethod` RSA-SHA1; `DigestMethod` SHA-1; `KeyInfo`→`X509Data`→`X509Certificate`.

---

### Task 1: Adicionar dependência `xmlsec` (Apache Santuario)

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Confirmar a versão estável**

Verificar no Maven Central a última versão estável da linha **3.0.x** de `org.apache.santuario:xmlsec` compatível com Java 21 (ex.: `3.0.5`). **Não** é gerenciada pelo BOM do Spring Boot — versão explícita obrigatória.

- [ ] **Step 2: Adicionar a dependência**

No bloco `<dependencies>` do `pom.xml`:

```xml
<dependency>
  <groupId>org.apache.santuario</groupId>
  <artifactId>xmlsec</artifactId>
  <version>3.0.5</version>
</dependency>
```

- [ ] **Step 3: Resolver e compilar**

Run: `./mvnw -q dependency:resolve compile`
Expected: baixa o `xmlsec` e compila sem conflito.

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -F - <<'MSG'
chore(deps): adiciona Apache Santuario (xmlsec) para assinatura XML da NF-e
MSG
```

---

### Task 2: `CertificadoDigitalProperties` + configuração

**Files:**
- Create: `src/main/java/br/com/agrocontabil/config/CertificadoDigitalProperties.java`
- Modify: `src/main/resources/application.yml`
- Modify: `.env.example`
- Test: `src/test/java/br/com/agrocontabil/config/CertificadoDigitalPropertiesTest.java`

- [ ] **Step 1: Write the failing test**

```java
package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CertificadoDigitalPropertiesTest {

  @Test
  void shouldHoldPathPasswordAndAlias() {
    CertificadoDigitalProperties props =
        new CertificadoDigitalProperties("/certs/nfe.p12", "secret", "nfe");
    assertThat(props.path()).isEqualTo("/certs/nfe.p12");
    assertThat(props.password()).isEqualTo("secret");
    assertThat(props.alias()).isEqualTo("nfe");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=CertificadoDigitalPropertiesTest`
Expected: FAIL — classe não existe.

- [ ] **Step 3: Write minimal implementation**

```java
package br.com.agrocontabil.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parâmetros do certificado digital usado para assinar a NF-e.
 * Valores vêm de env var — nenhum segredo no repositório.
 *
 * @param path     caminho do arquivo PKCS#12 (.p12)
 * @param password senha do KeyStore
 * @param alias    alias da entrada do certificado (opcional; se vazio, usa o primeiro)
 */
@ConfigurationProperties(prefix = "app.nfe.certificado")
public record CertificadoDigitalProperties(String path, String password, String alias) {}
```

- [ ] **Step 4: Registrar as properties**

Garantir que a classe principal (`AgroContabilApplication`) tenha `@ConfigurationPropertiesScan`, ou adicionar `@EnableConfigurationProperties(CertificadoDigitalProperties.class)` numa `@Configuration`. Verificar o padrão já usado por `TributosProperties` e seguir igual.

- [ ] **Step 5: Configurar `application.yml`**

```yaml
app:
  nfe:
    certificado:
      path: ${NFE_CERT_PATH:}
      password: ${NFE_CERT_PASSWORD:}
      alias: ${NFE_CERT_ALIAS:}
```

E no profile `test`/`dev` (`application-test.yml`), apontar para o cert de teste (criado na Task 3):

```yaml
app:
  nfe:
    certificado:
      path: classpath:certs/nfe-teste.p12
      password: changeit
      alias: nfe-teste
```

- [ ] **Step 6: Atualizar `.env.example`**

```dotenv
# Certificado digital A1 (.p12) para assinatura da NF-e
NFE_CERT_PATH=
NFE_CERT_PASSWORD=
NFE_CERT_ALIAS=
```

- [ ] **Step 7: Run test + commit**

Run: `./mvnw test -Dtest=CertificadoDigitalPropertiesTest`
Expected: PASS.

```bash
git add src/main/java/br/com/agrocontabil/config/CertificadoDigitalProperties.java \
        src/main/resources/application.yml src/test/resources/application-test.yml .env.example \
        src/test/java/br/com/agrocontabil/config/CertificadoDigitalPropertiesTest.java
git commit -F - <<'MSG'
feat(config): adiciona CertificadoDigitalProperties (path/password/alias via env)
MSG
```

---

### Task 3: Gerar e versionar o certificado de teste (`.p12` autoassinado)

**Files:**
- Create: `src/test/resources/certs/nfe-teste.p12` (binário)
- Create: `src/test/resources/certs/README.md`

- [ ] **Step 1: Gerar o keystore com keytool**

Run (na raiz do repo):

```bash
keytool -genkeypair -alias nfe-teste -keyalg RSA -keysize 2048 \
  -sigalg SHA1withRSA -validity 3650 \
  -dname "CN=NFe Teste AgroContabil, OU=TCC, O=Supersonic Team, C=BR" \
  -keystore src/test/resources/certs/nfe-teste.p12 -storetype PKCS12 \
  -storepass changeit -keypass changeit
```

Expected: cria o arquivo `nfe-teste.p12`.

- [ ] **Step 2: Documentar a regeneração**

`src/test/resources/certs/README.md`:

```markdown
# Certificado de teste — assinatura NF-e

`nfe-teste.p12` é um certificado **autoassinado** A1, exclusivo para testes.
NÃO tem valor jurídico e NÃO é aceito pela SEFAZ. Senha/alias: `changeit` / `nfe-teste`.

Regenerar:

    keytool -genkeypair -alias nfe-teste -keyalg RSA -keysize 2048 \
      -sigalg SHA1withRSA -validity 3650 \
      -dname "CN=NFe Teste AgroContabil, OU=TCC, O=Supersonic Team, C=BR" \
      -keystore nfe-teste.p12 -storetype PKCS12 -storepass changeit -keypass changeit

Em produção, o certificado ICP-Brasil real vem via env (NFE_CERT_PATH/PASSWORD/ALIAS) e
nunca é versionado.
```

- [ ] **Step 3: Commit**

```bash
git add src/test/resources/certs/nfe-teste.p12 src/test/resources/certs/README.md
git commit -F - <<'MSG'
test(nfe): adiciona certificado autoassinado de teste para assinatura
MSG
```

---

### Task 4: Adicionar códigos de assinatura ao `ErrorCode` + `AssinaturaDigitalException`

**Files:**
- Modify: `src/main/java/br/com/agrocontabil/common/ErrorCode.java`
- Create: `src/main/java/br/com/agrocontabil/exception/AssinaturaDigitalException.java`
- Test: `src/test/java/br/com/agrocontabil/exception/AssinaturaDigitalExceptionTest.java`

- [ ] **Step 1: Write the failing test**

```java
package br.com.agrocontabil.exception;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AssinaturaDigitalExceptionTest {

  @Test
  void shouldMapToInternalServerErrorWithCode() {
    AssinaturaDigitalException ex =
        new AssinaturaDigitalException(ErrorCode.CERTIFICADO_EXPIRADO, "cert vencido em 2020");
    assertThat(ex.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(ex.getErrors()).hasSize(1);
    assertThat(ex.getErrors().get(0).code()).isEqualTo("CERTIFICADO_EXPIRADO");
    // detalhe tecnico fica em getMessage (log), nao no ErrorDetail exposto
    assertThat(ex.getMessage()).contains("cert vencido");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=AssinaturaDigitalExceptionTest`
Expected: FAIL — código/exceção não existem.

- [ ] **Step 3: Adicionar os códigos ao `ErrorCode`**

No enum `ErrorCode`, adicionar as constantes:

```java
  // Assinatura digital
  CERTIFICADO_AUSENTE,
  CERTIFICADO_EXPIRADO,
  CERTIFICADO_INVALIDO,
  ASSINATURA_FALHOU,
  ASSINATURA_INVALIDA,
```

- [ ] **Step 4: Criar a exceção**

```java
package br.com.agrocontabil.exception;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.common.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Falha na assinatura digital da NF-e (certificado ou processo de assinatura).
 *
 * <p>São erros de configuração do servidor ou bug interno — não corrigíveis pelo
 * usuário final — portanto HTTP 500. O detalhe técnico fica só no log; o cliente
 * recebe uma mensagem genérica e o {@code code} estruturado.
 */
public class AssinaturaDigitalException extends DomainException {

  private static final String CLIENT_MESSAGE =
      "Nao foi possivel assinar digitalmente a NF-e. Contate o suporte.";

  public AssinaturaDigitalException(ErrorCode code, String technicalMessage) {
    super(HttpStatus.INTERNAL_SERVER_ERROR, technicalMessage,
        List.of(ErrorDetail.of(code, CLIENT_MESSAGE)));
  }

  public AssinaturaDigitalException(ErrorCode code, String technicalMessage, Throwable cause) {
    this(code, technicalMessage);
    initCause(cause);
  }
}
```

> O handler genérico de `DomainException` (PR A) já loga 5xx com stack e devolve `getErrors()`. Como o `ErrorDetail` já carrega a mensagem-cliente genérica, e o envelope (`getMessage()`) só é logado, não há vazamento. **Verificar** que o `clientMessageFor()` do handler não devolve `getMessage()` ao cliente para 5xx — se devolver, ajustar o handler para usar mensagem genérica em 5xx. (Decisão: no PR A, `clientMessageFor` devolve `getMessage()` exceto para `XmlGenerationException`. Para evitar vazamento aqui, **incluir** `AssinaturaDigitalException` no mesmo tratamento — ver Step 5.)

- [ ] **Step 5: Garantir mensagem genérica ao cliente para 5xx**

No `GlobalExceptionHandler.clientMessageFor`, ajustar para não vazar detalhe técnico em 5xx:

```java
  private String clientMessageFor(DomainException ex) {
    if (ex instanceof XmlGenerationException xml) {
      return xml.clientMessage();
    }
    if (ex.getStatus().is5xxServerError()) {
      return "Erro interno ao processar a NF-e. Tente novamente mais tarde.";
    }
    return ex.getMessage();
  }
```

- [ ] **Step 6: Run test + commit**

Run: `./mvnw test -Dtest=AssinaturaDigitalExceptionTest`
Expected: PASS.

```bash
git add src/main/java/br/com/agrocontabil/common/ErrorCode.java \
        src/main/java/br/com/agrocontabil/exception/AssinaturaDigitalException.java \
        src/main/java/br/com/agrocontabil/exception/GlobalExceptionHandler.java \
        src/test/java/br/com/agrocontabil/exception/AssinaturaDigitalExceptionTest.java
git commit -F - <<'MSG'
feat(exception): adiciona AssinaturaDigitalException e codigos de certificado/assinatura
MSG
```

---

### Task 5: `KeyStoreCertificadoProvider`

**Files:**
- Create: `src/main/java/br/com/agrocontabil/nfe/KeyStoreCertificadoProvider.java`
- Test: `src/test/java/br/com/agrocontabil/nfe/KeyStoreCertificadoProviderTest.java`

- [ ] **Step 1: Write the failing test**

```java
package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.CertificadoDigitalProperties;
import br.com.agrocontabil.exception.AssinaturaDigitalException;
import org.junit.jupiter.api.Test;

class KeyStoreCertificadoProviderTest {

  private static final String TEST_P12 = "classpath:certs/nfe-teste.p12";

  @Test
  void shouldLoadPrivateKeyAndCertificate() {
    var props = new CertificadoDigitalProperties(TEST_P12, "changeit", "nfe-teste");
    var provider = new KeyStoreCertificadoProvider(props);

    assertThat(provider.getPrivateKey()).isNotNull();
    assertThat(provider.getCertificate()).isNotNull();
    assertThat(provider.getCertificate().getSubjectX500Principal().getName())
        .contains("NFe Teste");
  }

  @Test
  void shouldFailWithCertificadoAusenteWhenPathMissing() {
    var props = new CertificadoDigitalProperties("classpath:certs/nao-existe.p12",
        "changeit", "nfe-teste");
    var provider = new KeyStoreCertificadoProvider(props);

    assertThatThrownBy(provider::getCertificate)
        .isInstanceOf(AssinaturaDigitalException.class);
  }

  @Test
  void shouldFailWithCertificadoInvalidoWhenWrongPassword() {
    var props = new CertificadoDigitalProperties(TEST_P12, "senha-errada", "nfe-teste");
    var provider = new KeyStoreCertificadoProvider(props);

    assertThatThrownBy(provider::getPrivateKey)
        .isInstanceOf(AssinaturaDigitalException.class);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=KeyStoreCertificadoProviderTest`
Expected: FAIL — classe não existe.

- [ ] **Step 3: Write implementation**

```java
package br.com.agrocontabil.nfe;

import br.com.agrocontabil.config.CertificadoDigitalProperties;
import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.exception.AssinaturaDigitalException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Carrega o certificado digital (PKCS#12) e expõe a chave privada e o certificado X.509.
 * Cache lazy thread-safe; valida a validade temporal do certificado no carregamento.
 */
@Component
public class KeyStoreCertificadoProvider {

  private final CertificadoDigitalProperties props;
  private final ResourceLoader resourceLoader = new DefaultResourceLoader();

  private volatile PrivateKey privateKey;
  private volatile X509Certificate certificate;
  private final Object lock = new Object();

  public KeyStoreCertificadoProvider(CertificadoDigitalProperties props) {
    this.props = props;
  }

  /** Chave privada do certificado, carregada sob demanda. */
  public PrivateKey getPrivateKey() {
    ensureLoaded();
    return privateKey;
  }

  /** Certificado X.509 do emitente, carregado sob demanda. */
  public X509Certificate getCertificate() {
    ensureLoaded();
    return certificate;
  }

  private void ensureLoaded() {
    if (certificate != null) {
      return;
    }
    synchronized (lock) {
      if (certificate != null) {
        return;
      }
      load();
    }
  }

  private void load() {
    if (props.path() == null || props.path().isBlank()) {
      throw new AssinaturaDigitalException(ErrorCode.CERTIFICADO_AUSENTE,
          "Caminho do certificado (app.nfe.certificado.path) nao configurado");
    }
    Resource resource = resourceLoader.getResource(props.path());
    if (!resource.exists()) {
      throw new AssinaturaDigitalException(ErrorCode.CERTIFICADO_AUSENTE,
          "Arquivo de certificado nao encontrado: " + props.path());
    }
    try (InputStream in = resource.getInputStream()) {
      KeyStore ks = KeyStore.getInstance("PKCS12");
      char[] pwd = props.password() == null ? new char[0] : props.password().toCharArray();
      ks.load(in, pwd);

      String alias = resolveAlias(ks);
      PrivateKey key = (PrivateKey) ks.getKey(alias, pwd);
      X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
      if (key == null || cert == null) {
        throw new AssinaturaDigitalException(ErrorCode.CERTIFICADO_INVALIDO,
            "Alias '" + alias + "' nao contem chave privada e certificado");
      }
      cert.checkValidity(); // lanca CertificateExpiredException/NotYetValid

      this.privateKey = key;
      this.certificate = cert;
    } catch (java.security.cert.CertificateExpiredException
        | java.security.cert.CertificateNotYetValidException e) {
      throw new AssinaturaDigitalException(ErrorCode.CERTIFICADO_EXPIRADO,
          "Certificado fora do periodo de validade: " + e.getMessage(), e);
    } catch (AssinaturaDigitalException e) {
      throw e;
    } catch (Exception e) {
      throw new AssinaturaDigitalException(ErrorCode.CERTIFICADO_INVALIDO,
          "Falha ao carregar o certificado: " + e.getMessage(), e);
    }
  }

  private String resolveAlias(KeyStore ks) throws Exception {
    if (props.alias() != null && !props.alias().isBlank()) {
      return props.alias();
    }
    Enumeration<String> aliases = ks.aliases();
    if (aliases.hasMoreElements()) {
      return aliases.nextElement();
    }
    throw new AssinaturaDigitalException(ErrorCode.CERTIFICADO_INVALIDO,
        "KeyStore nao possui nenhum alias");
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=KeyStoreCertificadoProviderTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/br/com/agrocontabil/nfe/KeyStoreCertificadoProvider.java \
        src/test/java/br/com/agrocontabil/nfe/KeyStoreCertificadoProviderTest.java
git commit -F - <<'MSG'
feat(nfe): adiciona KeyStoreCertificadoProvider (carrega .p12 PKCS#12)
MSG
```

---

### Task 6: `NfeAssinador` (gera o `ds:Signature` enveloped)

**Files:**
- Create: `src/main/java/br/com/agrocontabil/nfe/NfeAssinador.java`
- Test: `src/test/java/br/com/agrocontabil/nfe/NfeAssinadorTest.java`

**Notas Santuario:** `org.apache.xml.security.Init.init()` uma vez; `XMLSignature` com `ALGO_ID_SIGNATURE_RSA_SHA1` e `Canonicalizer.ALGO_ID_C14N_OMIT_COMMENTS`; transforms `TRANSFORM_ENVELOPED_SIGNATURE` + `TRANSFORM_C14N_OMIT_COMMENTS`; digest `MessageDigestAlgorithm.ALGO_ID_DIGEST_SHA1`. Marcar o atributo `Id` do `infNFe` como ID (`setIdAttributeNS(null, "Id", true)`). Inserir o `Signature` como filho de `NFe` (irmão de `infNFe`).

- [ ] **Step 1: Write the failing test**

```java
package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.config.CertificadoDigitalProperties;
import org.junit.jupiter.api.Test;

class NfeAssinadorTest {

  private NfeAssinador novoAssinador() {
    var props = new CertificadoDigitalProperties("classpath:certs/nfe-teste.p12",
        "changeit", "nfe-teste");
    return new NfeAssinador(new KeyStoreCertificadoProvider(props));
  }

  /** XML mínimo com a estrutura NFe>infNFe@Id exigida pela assinatura. */
  private String xmlNfe(String id) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<NFe xmlns=\"http://www.portalfiscal.inf.br/nfe\">"
        + "<infNFe Id=\"" + id + "\" versao=\"4.00\"><ide><cUF>41</cUF></ide></infNFe>"
        + "</NFe>";
  }

  @Test
  void shouldProduceEnvelopedSignatureReferencingInfNFeId() {
    String id = "NFe41250600000000000000000000000000000000000001";
    String assinado = novoAssinador().assinar(xmlNfe(id), id);

    assertThat(assinado).contains("Signature");
    assertThat(assinado).contains("#" + id);                 // Reference URI
    assertThat(assinado).contains("rsa-sha1");               // SignatureMethod
    assertThat(assinado).contains("xmldsig#sha1");           // DigestMethod
    assertThat(assinado).contains("REC-xml-c14n-20010315");  // C14N inclusiva
    assertThat(assinado).contains("X509Certificate");        // KeyInfo
  }

  @Test
  void shouldInsertSignatureAsSiblingOfInfNFe() {
    String id = "NFe41250600000000000000000000000000000000000002";
    String assinado = novoAssinador().assinar(xmlNfe(id), id);
    // a tag de fechamento do infNFe vem antes da abertura do Signature
    int fimInf = assinado.indexOf("</infNFe>");
    int iniSig = assinado.indexOf("Signature");
    assertThat(fimInf).isGreaterThan(0);
    assertThat(iniSig).isGreaterThan(fimInf);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=NfeAssinadorTest`
Expected: FAIL — classe não existe.

- [ ] **Step 3: Write implementation**

```java
package br.com.agrocontabil.nfe;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.exception.AssinaturaDigitalException;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.xml.security.Init;
import org.apache.xml.security.c14n.Canonicalizer;
import org.apache.xml.security.signature.XMLSignature;
import org.apache.xml.security.transforms.Transforms;
import org.apache.xml.security.utils.Constants;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Assina digitalmente o XML da NF-e (assinatura enveloped no {@code infNFe}),
 * conforme o padrão SEFAZ (C14N inclusiva, RSA-SHA1, SHA-1, X509Data).
 */
@Component
public class NfeAssinador {

  private static final String NFE_NS = "http://www.portalfiscal.inf.br/nfe";

  static {
    if (!Init.isInitialized()) {
      Init.init();
    }
  }

  private final KeyStoreCertificadoProvider certificadoProvider;

  public NfeAssinador(KeyStoreCertificadoProvider certificadoProvider) {
    this.certificadoProvider = certificadoProvider;
  }

  /**
   * Assina o XML da NF-e.
   *
   * @param xml      XML não assinado (saída do {@code XmlGenerator})
   * @param infNfeId valor do atributo {@code Id} do elemento {@code infNFe}
   * @return XML assinado (com o nó {@code Signature} dentro de {@code NFe})
   */
  public String assinar(String xml, String infNfeId) {
    try {
      Document doc = parseSeguro(xml);

      Element infNFe = (Element) doc.getElementsByTagNameNS(NFE_NS, "infNFe").item(0);
      if (infNFe == null) {
        throw new AssinaturaDigitalException(ErrorCode.ASSINATURA_FALHOU,
            "Elemento infNFe nao encontrado no XML");
      }
      infNFe.setIdAttributeNS(null, "Id", true);

      Element nfe = (Element) infNFe.getParentNode();

      XMLSignature signature = new XMLSignature(
          doc, "",
          XMLSignature.ALGO_ID_SIGNATURE_RSA_SHA1,
          Canonicalizer.ALGO_ID_C14N_OMIT_COMMENTS);
      // Signature como irmão de infNFe (último filho de NFe)
      nfe.appendChild(signature.getElement());

      Transforms transforms = new Transforms(doc);
      transforms.addTransform(Transforms.TRANSFORM_ENVELOPED_SIGNATURE);
      transforms.addTransform(Transforms.TRANSFORM_C14N_OMIT_COMMENTS);
      signature.addDocument("#" + infNfeId, transforms,
          Constants.ALGO_ID_DIGEST_SHA1);

      signature.addKeyInfo(certificadoProvider.getCertificate());
      signature.sign(certificadoProvider.getPrivateKey());

      return serializar(doc);
    } catch (AssinaturaDigitalException e) {
      throw e;
    } catch (Exception e) {
      throw new AssinaturaDigitalException(ErrorCode.ASSINATURA_FALHOU,
          "Falha ao assinar o XML da NF-e: " + e.getMessage(), e);
    }
  }

  private Document parseSeguro(String xml) throws Exception {
    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    dbf.setNamespaceAware(true);
    // Hardening XXE (mesmo padrão do XsdValidator)
    dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
    dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    DocumentBuilder db = dbf.newDocumentBuilder();
    return db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

  private String serializar(Document doc) throws Exception {
    TransformerFactory tf = TransformerFactory.newInstance();
    tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
    Transformer transformer = tf.newTransformer();
    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
    StringWriter writer = new StringWriter();
    transformer.transform(new DOMSource(doc), new StreamResult(writer));
    return writer.toString();
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=NfeAssinadorTest`
Expected: PASS. (Se algum literal de algoritmo divergir na serialização — ex.: maiúsculas/minúsculas da URI — ajustar o `contains` do teste ao valor real produzido pelo Santuario.)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/br/com/agrocontabil/nfe/NfeAssinador.java \
        src/test/java/br/com/agrocontabil/nfe/NfeAssinadorTest.java
git commit -F - <<'MSG'
feat(nfe): adiciona NfeAssinador (assinatura enveloped RSA-SHA1/C14N via Santuario)
MSG
```

---

### Task 7: `AssinaturaValidator` (core validation + tamper detection)

**Files:**
- Create: `src/main/java/br/com/agrocontabil/nfe/AssinaturaValidator.java`
- Test: `src/test/java/br/com/agrocontabil/nfe/AssinaturaValidatorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package br.com.agrocontabil.nfe;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.CertificadoDigitalProperties;
import br.com.agrocontabil.exception.AssinaturaDigitalException;
import org.junit.jupiter.api.Test;

class AssinaturaValidatorTest {

  private final KeyStoreCertificadoProvider provider = new KeyStoreCertificadoProvider(
      new CertificadoDigitalProperties("classpath:certs/nfe-teste.p12", "changeit", "nfe-teste"));
  private final NfeAssinador assinador = new NfeAssinador(provider);
  private final AssinaturaValidator validator = new AssinaturaValidator();

  private String xmlNfe(String id) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<NFe xmlns=\"http://www.portalfiscal.inf.br/nfe\">"
        + "<infNFe Id=\"" + id + "\" versao=\"4.00\"><ide><cUF>41</cUF></ide></infNFe>"
        + "</NFe>";
  }

  @Test
  void validSignatureShouldPass() {
    String id = "NFe41250600000000000000000000000000000000000010";
    String assinado = assinador.assinar(xmlNfe(id), id);
    assertThatCode(() -> validator.validar(assinado)).doesNotThrowAnyException();
  }

  @Test
  void tamperedXmlShouldFail() {
    String id = "NFe41250600000000000000000000000000000000000011";
    String assinado = assinador.assinar(xmlNfe(id), id);
    // adultera o conteudo assinado (troca cUF) apos a assinatura
    String adulterado = assinado.replace("<cUF>41</cUF>", "<cUF>35</cUF>");
    assertThatThrownBy(() -> validator.validar(adulterado))
        .isInstanceOf(AssinaturaDigitalException.class);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=AssinaturaValidatorTest`
Expected: FAIL — classe não existe.

- [ ] **Step 3: Write implementation**

```java
package br.com.agrocontabil.nfe;

import br.com.agrocontabil.common.ErrorCode;
import br.com.agrocontabil.exception.AssinaturaDigitalException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.xml.security.Init;
import org.apache.xml.security.keys.KeyInfo;
import org.apache.xml.security.signature.XMLSignature;
import org.apache.xml.security.utils.Constants;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Valida criptograficamente a assinatura de um XML de NF-e (core validation:
 * confere SignatureValue e o DigestValue da Reference). Detecta adulteração.
 */
@Component
public class AssinaturaValidator {

  private static final String NFE_NS = "http://www.portalfiscal.inf.br/nfe";

  static {
    if (!Init.isInitialized()) {
      Init.init();
    }
  }

  /**
   * Valida a assinatura do XML.
   *
   * @param xmlAssinado XML com o nó {@code Signature}
   * @throws AssinaturaDigitalException se a assinatura estiver ausente ou inválida
   */
  public void validar(String xmlAssinado) {
    try {
      Document doc = parseSeguro(xmlAssinado);

      Element infNFe = (Element) doc.getElementsByTagNameNS(NFE_NS, "infNFe").item(0);
      if (infNFe != null) {
        infNFe.setIdAttributeNS(null, "Id", true);
      }

      Element sigElement = (Element) doc
          .getElementsByTagNameNS(Constants.SignatureSpecNS, "Signature").item(0);
      if (sigElement == null) {
        throw new AssinaturaDigitalException(ErrorCode.ASSINATURA_INVALIDA,
            "XML sem elemento Signature");
      }

      XMLSignature signature = new XMLSignature(sigElement, "");
      KeyInfo keyInfo = signature.getKeyInfo();
      X509Certificate cert = keyInfo == null ? null : keyInfo.getX509Certificate();
      boolean valido = cert != null
          ? signature.checkSignatureValue(cert)
          : signature.checkSignatureValue(keyInfo == null ? null : keyInfo.getPublicKey());

      if (!valido) {
        throw new AssinaturaDigitalException(ErrorCode.ASSINATURA_INVALIDA,
            "Assinatura digital invalida (SignatureValue/DigestValue nao conferem)");
      }
    } catch (AssinaturaDigitalException e) {
      throw e;
    } catch (Exception e) {
      throw new AssinaturaDigitalException(ErrorCode.ASSINATURA_INVALIDA,
          "Falha ao validar a assinatura: " + e.getMessage(), e);
    }
  }

  private Document parseSeguro(String xml) throws Exception {
    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    dbf.setNamespaceAware(true);
    dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
    dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    DocumentBuilder db = dbf.newDocumentBuilder();
    return db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=AssinaturaValidatorTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/br/com/agrocontabil/nfe/AssinaturaValidator.java \
        src/test/java/br/com/agrocontabil/nfe/AssinaturaValidatorTest.java
git commit -F - <<'MSG'
feat(nfe): adiciona AssinaturaValidator (core validation + deteccao de adulteracao)
MSG
```

---

### Task 8: Reverter o hardening do `XsdValidator`

**Files:**
- Modify: `src/main/java/br/com/agrocontabil/nfe/XsdValidator.java`
- Test: `src/test/java/br/com/agrocontabil/nfe/XsdValidatorTest.java`

- [ ] **Step 1: Atualizar o teste (regressão do hardening revertido)**

No `XsdValidatorTest`, garantir dois casos:
- XML **sem** `Signature` agora **lança** `XmlGenerationException` (antes era tolerado).
- (Se houver fixture de XML assinado válido contra o XSD, ele passa.)

```java
  @Test
  @DisplayName("XML sem ds:Signature agora e rejeitado pelo XSD (hardening revertido)")
  void xmlSemAssinaturaDeveSerRejeitado() {
    String xmlSemAssinatura = /* fixture/gerado sem Signature */ ...;
    assertThatThrownBy(() -> xsdValidator.validar(xmlSemAssinatura))
        .isInstanceOf(XmlGenerationException.class)
        .hasMessageContaining("Signature");
  }
```

> Se já existia um teste afirmando que a ausência de Signature era **tolerada**, ele deve ser **removido/invertido** aqui.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=XsdValidatorTest`
Expected: FAIL — hoje a ausência de Signature é tolerada (não lança).

- [ ] **Step 3: Remover a tolerância no `XsdValidator`**

Em `validar(String xml)`, substituir o bloco de filtragem (linhas ~106-126) por tratamento direto — todo erro é fatal:

```java
    try {
      Source source = new StreamSource(new StringReader(xml));
      validator.validate(source);
    } catch (SAXException | IOException e) {
      if (errors.isEmpty()) {
        errors.add("Erro ao processar o XML: " + e.getMessage());
      }
    }

    if (!errors.isEmpty()) {
      String detail = String.join("; ", errors);
      log.warn("XML da NF-e invalido contra o XSD: {}", detail);
      throw new XmlGenerationException("XML da NF-e invalido: " + detail);
    }
```

E **remover** o método `isUnsignedDocumentOmission(String)` e atualizar o Javadoc da classe (tirar a menção a "toleramos a ausência do Signature").

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=XsdValidatorTest`
Expected: PASS — XML sem assinatura agora é rejeitado.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/br/com/agrocontabil/nfe/XsdValidator.java \
        src/test/java/br/com/agrocontabil/nfe/XsdValidatorTest.java
git commit -F - <<'MSG'
refactor(nfe): XsdValidator passa a exigir ds:Signature (reverte tolerancia da Sprint 2)
MSG
```

---

### Task 9: Integrar a assinatura no `GeracaoNotaFiscalService`

**Files:**
- Modify: `src/main/java/br/com/agrocontabil/service/GeracaoNotaFiscalService.java`
- Modify: `src/test/java/br/com/agrocontabil/service/GeracaoNotaFiscalServiceTest.java`

- [ ] **Step 1: Atualizar o teste**

No `GeracaoNotaFiscalServiceTest`, adicionar os mocks `NfeAssinador` e `AssinaturaValidator` ao construtor e verificar a ordem do pipeline (assina → valida assinatura → XSD). Exemplo de verificação:

```java
  @Test
  @DisplayName("gerarXml assina o XML e valida a assinatura antes do XSD")
  void gerarXmlAssinaEValidaAntesDoXsd() {
    // given
    when(fiscalRulesValidator....);
    when(notaFiscalNfeMapper.toNfe(nota)).thenReturn(nfe);
    when(xmlGenerator.marshal(nfe)).thenReturn("<xml-sem-assinatura/>");
    when(nfeAssinador.assinar("<xml-sem-assinatura/>", nfe.getInfNFe().getId()))
        .thenReturn("<xml-assinado/>");

    // when
    String resultado = service.gerarXml(nota);

    // then
    assertThat(resultado).isEqualTo("<xml-assinado/>");
    InOrder ordem = inOrder(nfeAssinador, assinaturaValidator, xsdValidator);
    ordem.verify(nfeAssinador).assinar("<xml-sem-assinatura/>", nfe.getInfNFe().getId());
    ordem.verify(assinaturaValidator).validar("<xml-assinado/>");
    ordem.verify(xsdValidator).validar("<xml-assinado/>");
  }
```

> Ajustar stubs existentes que esperavam o XSD validando o XML **não** assinado.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=GeracaoNotaFiscalServiceTest`
Expected: FAIL — o serviço ainda não chama assinador/validador.

- [ ] **Step 3: Atualizar o serviço**

Injetar as duas dependências e inserir as chamadas no `gerarXml`:

```java
  private final FiscalRulesValidator fiscalRulesValidator;
  private final NotaFiscalNfeMapper notaFiscalNfeMapper;
  private final XmlGenerator xmlGenerator;
  private final NfeAssinador nfeAssinador;
  private final AssinaturaValidator assinaturaValidator;
  private final XsdValidator xsdValidator;

  public GeracaoNotaFiscalService(
      FiscalRulesValidator fiscalRulesValidator,
      NotaFiscalNfeMapper notaFiscalNfeMapper,
      XmlGenerator xmlGenerator,
      NfeAssinador nfeAssinador,
      AssinaturaValidator assinaturaValidator,
      XsdValidator xsdValidator) {
    this.fiscalRulesValidator = fiscalRulesValidator;
    this.notaFiscalNfeMapper = notaFiscalNfeMapper;
    this.xmlGenerator = xmlGenerator;
    this.nfeAssinador = nfeAssinador;
    this.assinaturaValidator = assinaturaValidator;
    this.xsdValidator = xsdValidator;
  }

  public String gerarXml(NotaFiscal nota) {
    log.info("Iniciando geracao do XML da NF-e: notaId={} numero={} serie={}",
        nota.getId(), nota.getNumero(), nota.getSerie());

    fiscalRulesValidator.validar(nota);

    NFe nfe = notaFiscalNfeMapper.toNfe(nota);
    String xml = xmlGenerator.marshal(nfe);
    String xmlAssinado = nfeAssinador.assinar(xml, nfe.getInfNFe().getId());
    assinaturaValidator.validar(xmlAssinado);
    xsdValidator.validar(xmlAssinado);

    log.info("XML da NF-e gerado, assinado e validado: notaId={} chave={}",
        nota.getId(), nfe.getInfNFe().getId());
    return xmlAssinado;
  }
```

Atualizar o Javadoc da classe (remover "não assina digitalmente — Sprint 7"; descrever a nova etapa).

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=GeracaoNotaFiscalServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/br/com/agrocontabil/service/GeracaoNotaFiscalService.java \
        src/test/java/br/com/agrocontabil/service/GeracaoNotaFiscalServiceTest.java
git commit -F - <<'MSG'
feat(nfe): integra assinatura digital no pipeline de geracao do XML

gerarXml passa a assinar o XML e validar a assinatura antes do XSD.
MSG
```

---

### Task 10: Teste de integração ponta-a-ponta + documentação + CLAUDE.md

**Files:**
- Create: `src/test/java/br/com/agrocontabil/service/GeracaoNotaFiscalAssinaturaIT.java`
- Create: `docs/nfe-certificado-digital.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Teste de integração (fluxo real, sem mocks de assinatura)**

```java
package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class GeracaoNotaFiscalAssinaturaIT {

  @Autowired
  private GeracaoNotaFiscalService service;

  @Test
  @DisplayName("Fluxo real: nota valida gera XML assinado e validado contra o XSD")
  void notaValidaGeraXmlAssinado() {
    var nota = /* montar NotaFiscal valida (reusar fixture/builder dos testes existentes) */ null;
    String xml = service.gerarXml(nota);
    assertThat(xml).contains("Signature");
    assertThat(xml).contains("X509Certificate");
  }
}
```

> Reusar o builder de `NotaFiscal` válida já existente nos testes da Sprint 2. Se não houver builder reutilizável, extrair um helper de teste.

- [ ] **Step 2: Rodar a suíte completa**

Run: `./mvnw test`
Expected: PASS — toda a suíte verde, incluindo o fluxo assinado.

- [ ] **Step 3: Documentar o certificado real**

`docs/nfe-certificado-digital.md`:

```markdown
# Certificado digital da NF-e

A assinatura usa um certificado A1 (PKCS#12, `.p12`).

## Desenvolvimento / teste
Usa `src/test/resources/certs/nfe-teste.p12` (autoassinado, sem valor jurídico).

## Produção (ICP-Brasil real)
Configurar via variáveis de ambiente — nunca versionar o `.p12`:

    NFE_CERT_PATH=/caminho/seguro/certificado.p12
    NFE_CERT_PASSWORD=********
    NFE_CERT_ALIAS=<alias do certificado>

O certificado deve ser ICP-Brasil válido. A aceitação efetiva pela SEFAZ é
verificada no fluxo de envio (issue #15).
```

- [ ] **Step 4: Atualizar `CLAUDE.md`**

- Mover a assinatura digital de "débito Sprint 7" para implementada na Sprint 3.
- Remover a menção a "XsdValidator tolera ausência de ds:Signature".
- Acrescentar `NfeAssinador`, `AssinaturaValidator`, `KeyStoreCertificadoProvider` à lista de componentes `nfe/`/`service/`, e o contrato de erro estruturado (camada A) à descrição de `common/`/`exception/`.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/br/com/agrocontabil/service/GeracaoNotaFiscalAssinaturaIT.java \
        docs/nfe-certificado-digital.md CLAUDE.md
git commit -F - <<'MSG'
test(nfe): IT do fluxo de geracao assinada + doc de certificado e CLAUDE.md
MSG
```

---

## Self-Review (executado)

- **Cobertura do spec (camada B):** dependência (T1), properties+config (T2), cert de teste (T3), códigos+exceção (T4), provider (T5), assinador (T6), validador (T7), reversão do XsdValidator (T8), integração no pipeline (T9), IT+doc+CLAUDE.md (T10). ✔
- **Placeholders:** os pontos com `/* ... */` (montagem de `NotaFiscal` válida no IT, fixtures do `XsdValidatorTest`) referenciam **fixtures existentes** da Sprint 2 — o worker deve reusá-los; sinalizado explicitamente, não é código a inventar. ✔
- **Consistência de tipos:** `KeyStoreCertificadoProvider.getCertificate()/getPrivateKey()` usados em T6/T7; `assinar(xml, id)` e `validar(xml)` consistentes entre assinador, validador e serviço; `ErrorCode` de assinatura definido em T4 e usado em T5-T7. ✔
- **Riscos sinalizados:** literais de algoritmo na serialização do Santuario podem variar (ajustar `contains` ao valor real — T6 Step 4); confirmar versão do `xmlsec` (T1); `clientMessageFor` ajustado para não vazar 5xx (T4 Step 5).
- **Dependência de PR A:** `DomainException`/`ErrorCode`/`ErrorDetail` devem estar mergeados antes de iniciar.
