# Spec — Assinatura digital NF-e (#17) + Contrato de erro estruturado

- **Data:** 2026-06-11
- **Autor:** Murillo Weiss (Supersonic Team) + Claude Code
- **Issue:** [#17 — Assinatura digital mock/sandbox ICP-Brasil (RF014)](https://github.com/SupersonicTeam/backend/issues/17)
- **Sprint:** 3 (21/05 → 03/06/2026)
- **Status:** Aprovado para implementação

---

## 1. Objetivo e escopo

Implementar a **assinatura digital** da NF-e modelo 55 em ambiente sandbox/mock (ICP-Brasil),
inserindo a assinatura no pipeline de geração de XML já existente, e **reverter o hardening**
do `XsdValidator` que hoje tolera a ausência de `ds:Signature`.

Em conjunto (pré-requisito da #17), padronizar o **contrato de erro da API** num formato JSON
estruturado e acionável pelo frontend, válido para **todos** os erros da aplicação.

O trabalho foi decomposto em três camadas, das quais **A e B entram agora**:

| Camada | Descrição | Entrega |
|---|---|---|
| **A** | Contrato de erro estruturado (transversal) | PR base, vem primeiro |
| **B** | Assinatura digital #17 (usa A) | PR da #17 |
| **C** | Persistência de inconsistências + status no banco | Issue nova (fora deste spec) |

### Fora de escopo (não fazer aqui)
- Transmissão à SEFAZ (issue #15) e consulta de status (issue #16).
- Certificado ICP-Brasil **real** e aceitação efetiva pela SEFAZ — migra para a #15.
- Camada **C**: entidade `NotaFiscalValidacao`, novos status da nota
  (`COM_INCONSISTENCIAS`/`AGUARDANDO_CORRECAO`/`VALIDADA`), endpoint de histórico de validações.

---

## 2. Decisões técnicas

| Decisão | Escolha | Justificativa |
|---|---|---|
| Stack de assinatura | **Apache Santuario (`org.apache.santuario:xmlsec`)** | Referência de XML Security em Java; evita pegadinhas de canonicalização/prefixo do XMLDSig nativo com NF-e. Versão estável (linha 3.0.x, compatível com Java 21) declarada **explícita** no `pom` — não é gerenciada pelo BOM do Spring Boot. |
| Certificado | **Autoassinado A1 `.p12` de teste** | Desbloqueia a #17 sem custo nem dado sensível. Passa na validação criptográfica local. A aceitação real pela SEFAZ migra para a #15. |
| Algoritmos | **Cravados pela SEFAZ (MOC NF-e)** | Enveloped no `infNFe`; transforms `[enveloped-signature, C14N]`; C14N inclusiva (`xml-c14n-20010315`); `SignatureMethod` RSA-SHA1; `DigestMethod` SHA-1; `KeyInfo`→`X509Data`→`X509Certificate`. Não são escolhas. |
| Contrato de erro | **Envelope `errors[]` com itens ricos** | Frontend precisa de erros acionáveis por campo, não "nota inválida". |

---

## 3. Camada A — Contrato de erro estruturado

### 3.1 Resposta JSON padronizada (todos os erros)

```json
{
  "timestamp": "2026-06-11T18:40:00-03:00",
  "status": 422,
  "error": "Unprocessable Entity",
  "message": "A nota fiscal possui inconsistências fiscais",
  "path": "/api/notas/42/gerar-xml",
  "errors": [
    {
      "code": "CFOP_INVALIDO",
      "field": "itens[0].cfop",
      "message": "CFOP incompatível com a UF de destino",
      "suggestion": "Verifique se a operação é estadual ou interestadual",
      "rejectedValue": "5102",
      "expectedValue": "6102"
    }
  ]
}
```

`rejectedValue`, `expectedValue`, `suggestion` e `field` são opcionais e **omitidos** do JSON
quando ausentes (`@JsonInclude(NON_NULL)`).

### 3.2 Componentes

- **`ApiError` (record, `common/`)** — evolui: o campo `fields` passa a se chamar **`errors`**,
  do tipo `List<ErrorDetail>`. Mantém `timestamp`, `status`, `error`, `message`, `path`.
- **`ErrorDetail` (record, `common/`)** — `code`, `field`, `message`, `suggestion`,
  `rejectedValue`, `expectedValue`. Anotado com `@JsonInclude(JsonInclude.Include.NON_NULL)`.
- **`ErrorCode` (enum, `common/` ou `exception/`)** — catálogo central de códigos.
  Cada constante expõe o código string. Inicial:
  - Genéricos: `VALIDATION_ERROR`, `RESOURCE_NOT_FOUND`, `ILLEGAL_ARGUMENT`, `INTERNAL_ERROR`.
  - Nota fiscal: `NOTA_NAO_EDITAVEL`, `NOTA_INVALIDA_EMISSAO`, `XML_GENERATION_ERROR`.
  - Assinatura: `CERTIFICADO_AUSENTE`, `CERTIFICADO_EXPIRADO`, `CERTIFICADO_INVALIDO`,
    `ASSINATURA_FALHOU`, `ASSINATURA_INVALIDA`.
- **`DomainException` (abstract, `exception/`)** — estende `RuntimeException`; carrega
  `HttpStatus status`, mensagem de envelope e `List<ErrorDetail> errors`. Base de todas as
  exceções de domínio.

### 3.3 Refatoração do tratamento de erro

- **`GlobalExceptionHandler`**:
  - **Um** `@ExceptionHandler(DomainException.class)` converte qualquer exceção de domínio
    em `ApiError` (status, message e `errors` vindos da própria exceção).
  - Mantém handlers específicos para `MethodArgumentNotValidException` (Bean Validation →
    `ErrorDetail` com `code=VALIDATION_ERROR`, `field`, `message`, **`rejectedValue`**
    extraído do `FieldError`), `IllegalArgumentException` (400) e o fallback `Exception` (500).
- **Exceções existentes refatoradas** para estender `DomainException`:
  - `ResourceNotFoundException` → 404, `RESOURCE_NOT_FOUND`.
  - `NotaFiscalNaoEditavelException` → 409, `NOTA_NAO_EDITAVEL`.
  - `NotaFiscalInvalidaParaEmissaoException` → 422, `NOTA_INVALIDA_EMISSAO`; o
    `FieldViolation` passa a mapear-se para `ErrorDetail` (com `code` por violação quando
    aplicável).
  - `XmlGenerationException` → 422, `XML_GENERATION_ERROR`. **Regra de ouro mantida:**
    detalhe técnico (que pode conter fragmento de XML / SAXParseException) **só no log**;
    cliente recebe mensagem genérica + código.

### 3.4 Compatibilidade

A renomeação `fields` → `errors` é uma quebra de contrato, **aceitável** porque a API ainda
está em desenvolvimento (sem auth, sem consumidores externos em produção — Sprint 2/3).

---

## 4. Camada B — Assinatura digital (#17)

### 4.1 Arquitetura (mantém o padrão fiscal em camadas)

| Componente | Pacote | Responsabilidade | Depende de |
|---|---|---|---|
| `CertificadoDigitalProperties` | `config/` | `@ConfigurationProperties("app.nfe.certificado")`: `path`, `password`, `alias`. Valores via env var. | — |
| `KeyStoreCertificadoProvider` | `nfe/` | Carrega o `.p12` (PKCS#12); expõe `PrivateKey` + `X509Certificate` pelo alias; cache lazy; valida expiração no load. | Properties |
| `NfeAssinador` | `nfe/` | Recebe `xml` + `Id` do `infNFe`, produz XML **assinado** via Santuario. | Provider |
| `AssinaturaValidator` | `nfe/` | Valida a assinatura criptograficamente (core validation: SignatureValue + DigestValue); detecta adulteração. | — |
| `AssinaturaDigitalException` | `exception/` | `DomainException` com os códigos de assinatura. | DomainException (A) |

### 4.2 Parâmetros de assinatura (cravados pela SEFAZ — MOC NF-e)

- Assinatura **enveloped**, inserida como irmã de `infNFe` dentro de `NFe` (ordem do `TNFe`:
  `infNFe`, `infNFeSupl?`, `Signature`).
- `Reference URI = "#" + infNFe.getId()`.
- Transforms: `enveloped-signature` + C14N (`http://www.w3.org/TR/2001/REC-xml-c14n-20010315`).
- `CanonicalizationMethod`: C14N inclusiva (`xml-c14n-20010315`).
- `SignatureMethod`: **RSA-SHA1**. `DigestMethod`: **SHA-1**.
- `KeyInfo` → `X509Data` → `X509Certificate` (certificado do emitente).
- Uma assinatura por NF-e.

### 4.3 Segurança (XXE)

O parser DOM usado para carregar o XML antes de assinar deve aplicar o **mesmo hardening XXE**
já presente no `XsdValidator`: `FEATURE_SECURE_PROCESSING`, `disallow-doctype-decl`, sem
entidades externas, sem resolução de DTD/schema externos.

### 4.4 Integração no pipeline

`GeracaoNotaFiscalService.gerarXml()` passa a:

```java
fiscalRulesValidator.validar(nota);
NFe nfe = notaFiscalNfeMapper.toNfe(nota);
String xml          = xmlGenerator.marshal(nfe);
String xmlAssinado  = nfeAssinador.assinar(xml, nfe.getInfNFe().getId());
assinaturaValidator.validar(xmlAssinado);   // a própria assinatura confere?
xsdValidator.validar(xmlAssinado);          // agora COM ds:Signature
return xmlAssinado;
```

### 4.5 Reversão do hardening do `XsdValidator`

Remover `isUnsignedDocumentOmission()` e o filtro de tolerância
(`XsdValidator.java`, hoje linhas ~106-144). Todo erro do XSD volta a ser **fatal**, já que o
XML chega assinado. O `xmldsig-core-schema_v1.01.xsd` já está no resolver de classpath, então o
XSD valida o `ds:Signature` nativamente.

### 4.6 Mapeamento de erros (camada A aplicada)

| Situação | `ErrorCode` | HTTP | Mensagem ao cliente |
|---|---|---|---|
| `.p12` ausente / path inválido | `CERTIFICADO_AUSENTE` | 500 | genérica + código (config do servidor) |
| Certificado expirado | `CERTIFICADO_EXPIRADO` | 500 | genérica + código |
| Senha/alias inválidos, cert corrompido | `CERTIFICADO_INVALIDO` | 500 | genérica + código |
| Falha ao gerar a assinatura | `ASSINATURA_FALHOU` | 500 | genérica + código |
| Assinatura gerada não confere (bug interno) | `ASSINATURA_INVALIDA` | 500 | genérica + código |

Detalhe técnico sempre **só no log**.

### 4.7 Certificado de teste

`.p12` autoassinado gerado por `keytool`, versionado em
`src/test/resources/certs/nfe-teste.p12` (senha conhecida, documentada — cert de teste sem
valor real). O profile `dev` aponta para ele via `application-dev`; produção usa env var com
cert real. **Nenhum `.p12` real entra no repositório.**

### 4.8 Documentação do certificado real

Doc em `docs/` explicando como configurar um certificado ICP-Brasil A1 real em produção
(env vars `NFE_CERT_PATH`, `NFE_CERT_PASSWORD`, `NFE_CERT_ALIAS`) e registrando que a
aceitação efetiva pela SEFAZ é validada na #15.

---

## 5. Configuração (`application.yml`)

```yaml
app:
  nfe:
    certificado:
      path: ${NFE_CERT_PATH:}
      password: ${NFE_CERT_PASSWORD:}
      alias: ${NFE_CERT_ALIAS:}
```

Profile `dev`/`test` aponta para o `.p12` de teste. `.env.example` atualizado com as novas vars.

---

## 6. Testes

### Camada A
- `GlobalExceptionHandlerTest` (ou de integração via MockMvc): cada tipo de exceção produz o
  JSON `errors[]` esperado, com `code`/`field`/`rejectedValue` corretos; `XmlGenerationException`
  **não** vaza detalhe técnico ao cliente.
- Atualizar quaisquer testes que assumam o campo antigo `fields`.

### Camada B
- `NfeAssinadorTest`: assina um XML fixture de NF-e; valida estrutura — `ds:Signature` presente,
  `Reference URI` = `#NFe<chave>`, algoritmos corretos (C14N, RSA-SHA1, SHA-1), `X509Certificate`
  no `KeyInfo`.
- `AssinaturaValidatorTest`: assinatura válida passa; **XML adulterado após assinar falha**
  (detecção de tamper).
- `KeyStoreCertificadoProviderTest`: carrega cert de teste; erros em path inválido, senha errada,
  alias inexistente (códigos corretos).
- `XsdValidatorTest` (regressão do hardening revertido): XML **assinado** valida; XML **sem
  assinatura** agora é **rejeitado** pelo XSD.
- `GeracaoNotaFiscalServiceTest`: atualizar — o XML retornado agora vem assinado; a validação da
  assinatura é chamada antes do XSD.

Mockito strict stubs: sem stub de objeto usado só após early return.

---

## 7. Entrega (programação incremental)

- **PR A** — `feat/contrato-erro-estruturado` (ou `chore/`): camada A isolada, deployável.
- **PR B** — `feat/nfe-assinatura-digital`: camada B (#17), depende de A já mergeada. A
  assinatura e a reversão do hardening do `XsdValidator` são **indivisíveis** (reverter sem
  assinar quebraria a geração), então vão juntas no mesmo PR.
- **Issue C** (nova) — persistência de inconsistências + status + histórico. Criada no GitHub,
  Sprint 3/4.

Cada PR: Conventional Commits, worktree isolado, code review + security review (OWASP) +
testes verdes antes do merge.

---

## 8. Riscos e dependências

- **Canonicalização**: divergência de C14N/prefixo é a causa nº 1 de rejeição de assinatura
  NF-e. Mitigado pela escolha do Santuario e por teste de tamper + validação local.
- **Versão do `xmlsec`**: confirmar a versão estável compatível com Java 21 no momento de
  adicionar ao `pom` (não é gerenciada pelo BOM do Spring Boot).
- **DoD (b) "SEFAZ aceita"**: não verificável na #17 (precisa de #15 + cert real) — registrado
  como comentário na issue e migrado para a #15.
- **Atualizar `CLAUDE.md`**: a assinatura sai do débito da Sprint 7 (já implementada na 3);
  o hardening do `XsdValidator` deixa de existir.
