# Certificado digital da NF-e

A assinatura digital da NF-e usa um certificado A1 (PKCS#12, `.p12`), parametrizado em
`app.nfe.certificado` (`path` / `password` / `alias`).

## Algoritmos (cravados pela SEFAZ — MOC NF-e)

- Assinatura **enveloped** no elemento `infNFe` (irmã de `infNFe` dentro de `NFe`).
- `Reference URI = "#" + infNFe.Id`; transforms `enveloped-signature` + C14N.
- Canonicalização **C14N inclusiva** (`xml-c14n-20010315`), `SignatureMethod` **RSA-SHA1**,
  `DigestMethod` **SHA-1**, `KeyInfo` → `X509Data` → `X509Certificate`.

Implementação com **Apache Santuario** (`org.apache.santuario:xmlsec`): `NfeAssinador` assina e
`AssinaturaValidator` confere a assinatura antes da validação XSD.

## Desenvolvimento / teste

O profile `test` usa `src/test/resources/certs/nfe-teste.p12` — certificado **autoassinado**,
sem valor jurídico (ver `src/test/resources/certs/README.md`). Senha/alias: `changeit` /
`nfe-teste`.

## Produção (ICP-Brasil real)

Configurar via variáveis de ambiente — **nunca** versionar o `.p12`:

```
NFE_CERT_PATH=/caminho/seguro/certificado.p12
NFE_CERT_PASSWORD=********
NFE_CERT_ALIAS=<alias do certificado>
```

O certificado deve ser **ICP-Brasil válido**. A SEFAZ valida a cadeia ICP-Brasil mesmo em
homologação, então um certificado autoassinado **não** é aceito pela SEFAZ — ele serve apenas
para a validação criptográfica local. A aceitação efetiva pela SEFAZ é verificada no fluxo de
**envio (issue #15)**.

## Erros

Falhas de certificado/assinatura viram `AssinaturaDigitalException` (HTTP 500) com código
estruturado (`CERTIFICADO_AUSENTE`, `CERTIFICADO_EXPIRADO`, `CERTIFICADO_INVALIDO`,
`ASSINATURA_FALHOU`, `ASSINATURA_INVALIDA`). O cliente recebe mensagem genérica; o detalhe
técnico fica só no log.
