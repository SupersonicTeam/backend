# Certificado de teste — assinatura NF-e

`nfe-teste.p12` é um certificado **autoassinado** A1 (RSA 2048), exclusivo para testes.
NÃO tem valor jurídico e NÃO é aceito pela SEFAZ. Senha/alias: `changeit` / `nfe-teste`.

Regenerar:

    keytool -genkeypair -alias nfe-teste -keyalg RSA -keysize 2048 \
      -sigalg SHA256withRSA -validity 3650 \
      -dname "CN=NFe Teste AgroContabil, OU=TCC, O=Supersonic Team, C=BR" \
      -keystore nfe-teste.p12 -storetype PKCS12 -storepass changeit -keypass changeit

> Observação: o `-sigalg` acima é a assinatura do próprio certificado autoassinado e é
> independente do algoritmo da assinatura do XML da NF-e (RSA-SHA1, cravado pela SEFAZ).
> O que importa para assinar a NF-e é o par de chaves RSA.

Em produção, o certificado ICP-Brasil real vem via env (`NFE_CERT_PATH`/`NFE_CERT_PASSWORD`/
`NFE_CERT_ALIAS`) e nunca é versionado.
