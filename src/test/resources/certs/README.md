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

# Servidor SEFAZ simulado — transporte SOAP + mTLS (issue #128)

`sefaz-fake-servidor.p12` é o certificado **autoassinado** do servidor SOAP falso dos testes do
`SoapSefazTransport` (SAN `localhost`/`127.0.0.1`, para a verificação de hostname do cliente).
`sefaz-fake-truststore.p12` contém só esse certificado e faz o papel da cadeia ICP-Brasil dos
servidores da SEFAZ. O cliente se autentica com o `nfe-teste.p12` acima (mTLS). Senha: `changeit`.

Regenerar:

    keytool -genkeypair -alias sefaz-fake -keyalg RSA -keysize 2048 -sigalg SHA256withRSA \
      -validity 3650 -dname "CN=localhost, OU=TCC Teste, O=Supersonic Team, C=BR" \
      -ext "SAN=dns:localhost,ip:127.0.0.1" \
      -keystore sefaz-fake-servidor.p12 -storetype PKCS12 -storepass changeit -keypass changeit
    keytool -exportcert -alias sefaz-fake -keystore sefaz-fake-servidor.p12 -storepass changeit \
      -rfc -file sefaz-fake.pem
    keytool -importcert -noprompt -alias sefaz-fake -file sefaz-fake.pem \
      -keystore sefaz-fake-truststore.p12 -storetype PKCS12 -storepass changeit
