# Transporte SOAP + mTLS da SEFAZ (issue #128)

O `SoapSefazTransport` é o `SefazTransport` real: SOAP 1.2 com mTLS nos webservices NF-e 4.00. Ele
cumpre o contrato que a Onda 6 (#83) deixou no `SefazService` (classificação de falhas, mapeamento
de cStat, timeouts) e entra no lugar do `MockSefazTransport` com `app.nfe.sefaz.mock.enabled=false`.

**Situação:** envio, consulta e cancelamento prontos e testados contra um servidor SOAP simulado
com mTLS. A homologação com a SEFAZ depende do certificado A1 ICP-Brasil de um emitente de teste e
do credenciamento na UF.

## Serviços

| Operação | Webservice | Mensagem |
|---|---|---|
| `enviar` | NFeAutorizacao4 | `enviNFe` síncrono (`indSinc=1`), uma NF-e por lote |
| `consultarStatus` | NFeConsultaProtocolo4 | `consSitNFe` pela chave |
| `cancelar` | NFeRecepcaoEvento4 | `envEvento` com o evento 110111 e o `infEvento` assinado |

Sem JAX-WS: o envelope da NF-e 4.00 é fixo e o mesmo em todas as UFs; muda só o endereço. O
envelope é montado como texto (`MensagensSefaz`), o envio usa o `HttpClient` do JDK e a resposta é
lida com o parser endurecido contra XXE do projeto (`SecureXmlFactories`). Nenhuma dependência nova.

## Configuração

```yaml
app:
  nfe:
    sefaz:
      ambiente: HOMOLOGACAO
      mock:
        enabled: false
      webservices:
        homologacao:
          PADRAO:            # UF sem entrada própria (ex.: as atendidas pela SVRS)
            autorizacao: https://...
            consulta: https://...
            evento: https://...
          PR:                # UF com autorizadora própria
            autorizacao: https://...
            consulta: https://...
            evento: https://...
      truststore:
        path: file:/caminho/icp-brasil.p12   # cadeia dos servidores da SEFAZ
        password: ${NFE_SEFAZ_TRUSTSTORE_PASSWORD}
```

- **Endereços:** pela UF do cUF da chave de acesso (2 primeiros dígitos), ou `PADRAO`. Confira os
  endereços na relação de serviços web do Portal Nacional da NF-e, por ambiente. Por variável de
  ambiente: `APP_NFE_SEFAZ_WEBSERVICES_HOMOLOGACAO_PADRAO_AUTORIZACAO` etc. (a chave do mapa vale
  sem diferenciar maiúsculas, porque o Spring a entrega em minúsculas).
- **A aplicação não sobe** com o mock desligado se o ambiente não tiver endereço, ou se algum não
  for `https://`.
- **mTLS:** o certificado do cliente é o mesmo A1 que assina a NF-e (`NFE_CERT_*`).
- **Truststore:** o padrão da JVM não traz a AC raiz ICP-Brasil; em homologação real é preciso
  configurá-lo. Só TLS 1.2 e 1.3; redirecionamento desligado.
- **Timeouts:** `app.nfe.sefaz.timeout.connect-ms` e `read-ms`. O tempo total de uma chamada é
  limitado a `connect-ms + read-ms`, o valor que a validação de subida usa no pior caso da
  transmissão.

## Classificação de falhas

| O que aconteceu | Exceção | Por quê |
|---|---|---|
| DNS, conexão recusada ou estourada (inclui o handshake TLS, que o JDK conta no `connectTimeout`) | `SefazConexaoException` | a requisição não saiu: reenviar é seguro |
| HTTP 4xx | `SefazConexaoException` | o servidor recusou antes de processar |
| cStat 108/109 (serviço paralisado), 656 (consumo indevido) | `SefazConexaoException` | o fisco disse que não processou |
| tempo de resposta estourado, conexão caída, HTTP 5xx, SOAP Fault | `SefazRespostaIndeterminadaException` | o fisco pode ter processado: o serviço consulta pela chave |
| resposta ilegível, sem `cStat`, com DOCTYPE ou acima de 1 MB | `SefazRespostaIndeterminadaException` | idem |
| qualquer outra falha | `SefazRespostaIndeterminadaException` | na dúvida, nunca reenviar às cegas (cStat 204) |
| rejeição de dados | `SefazRetorno` com `REJEITADA` | não é exceção: é resultado |

## Mapeamento de cStat

| Envio (`retEnviNFe`) | Situação |
|---|---|
| lote 104, `infProt` 100 | AUTORIZADA (protocolo e `dhRecbto`) |
| lote 104, `infProt` 110/301/302/303 | DENEGADA |
| lote 104, outro (inclui 204) | REJEITADA com o cStat (o 204 dispara a reconciliação) |
| lote 103 (recebido, assíncrono) | PENDENTE |
| lote com outro código (rejeição do lote) | REJEITADA |

| Consulta (`retConsSitNFe`) | Situação |
|---|---|
| 100 | AUTORIZADA |
| 101, 151 | CANCELADA, com protocolo e data do **evento** de cancelamento |
| 110, 301, 302, 303 | DENEGADA |
| 217 | NAO_ENCONTRADA |
| qualquer outro | PENDENTE (nunca rejeitada pela consulta) |

## Cancelamento (evento 110111)

- **`infEvento`** com `Id` = `ID110111` + chave + `01`, `cOrgao` = cUF da chave, `dhEvento` em
  Brasília, `nProt` da autorização e `xJust` **escapada** (o texto do usuário nunca vira XML; quebras
  de linha e espaços repetidos saem).
- **Assinatura** enveloped do `infEvento` com o mesmo certificado da NF-e e do mTLS
  (`NfeAssinador.assinarElemento`), verificada nos testes pelo `AssinaturaValidator`.
- **Autor do evento** (`DocumentoEmitente`): o CNPJ ou CPF do **certificado** (no ICP-Brasil, o CN
  termina em `:` + documento), porque a SEFAZ recusa autor diferente do titular. Sem esse padrão,
  vem da chave; como há CNPJ válido começando com `000`, CPF × CNPJ se decide pelos dígitos
  verificadores.
- **Retorno** (`retEnvEvento`): lote 128 + evento 135/155 → registrado, com protocolo e data do
  evento; 573 (evento já registrado) e outras recusas → rejeição com o cStat (o `SefazService`
  reconcilia o 573 pela consulta); 108/109 → conexão.
- Falha ao montar ou assinar o evento é antes de qualquer envio: vira conexão, e a nota segue
  autorizada.

## Testes

`SoapSefazTransportTest` sobe um `HttpsServer` do JDK que **exige certificado de cliente** e só
confia no certificado de teste do emitente (`certs/nfe-teste.p12`): o envio só passa se o cliente
fizer mTLS. O servidor usa `certs/sefaz-fake-servidor.p12` (SAN `localhost`), e o cliente confia nele
por `certs/sefaz-fake-truststore.p12`, no papel da cadeia ICP-Brasil. Cobre: autorização, 204, 301,
103, 108, consulta 217/101/100/105, conexão recusada, tempo de leitura, servidor não confiável,
HTTP 500/404, resposta malformada, DOCTYPE, resposta grande demais, corpo travado no meio, endereço
por UF e o cancelamento (135 com assinatura válida e justificativa escapada, 573, lote recusado,
109). `DocumentoEmitenteTest` cobre o autor do evento.

O cliente nunca loga o XML nem a chave de acesso (a chave carrega o CPF/CNPJ do emitente).
