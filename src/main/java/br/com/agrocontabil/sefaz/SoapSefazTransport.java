package br.com.agrocontabil.sefaz;

import br.com.agrocontabil.common.UfIbge;
import br.com.agrocontabil.config.SefazProperties;
import br.com.agrocontabil.config.SefazProperties.Endpoints;
import br.com.agrocontabil.config.SefazProperties.Webservices;
import br.com.agrocontabil.exception.SefazConexaoException;
import br.com.agrocontabil.nfe.KeyStoreCertificadoProvider;
import br.com.agrocontabil.nfe.NfeAssinador;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Transporte real da SEFAZ: SOAP 1.2 + mTLS nos webservices NF-e 4.00 (issue #128). Ativo com
 * {@code app.nfe.sefaz.mock.enabled=false}; com o mock ligado (padrão), este bean não existe.
 *
 * <ul>
 *   <li><strong>Endereço</strong> pela UF autorizadora, tirada do cUF da chave de acesso, ou o
 *       {@code PADRAO} do ambiente ({@code app.nfe.sefaz.webservices.*}).</li>
 *   <li><strong>mTLS</strong> com o certificado A1 do emitente, o mesmo que assina a NF-e
 *       ({@link KeyStoreCertificadoProvider}), e a cadeia ICP-Brasil dos servidores em
 *       {@code app.nfe.sefaz.truststore.*}.</li>
 *   <li><strong>Falhas</strong> classificadas pelo {@link ClienteSoapSefaz} e pelo
 *       {@link RetornoSefazParser} no contrato do {@link SefazTransport}.</li>
 * </ul>
 *
 * <p>Cancelamento: evento 110111 com o {@code infEvento} assinado ({@link NfeAssinador}) e o
 * autor tirado do certificado ({@link DocumentoEmitente}).
 *
 * <p>O cliente HTTP é montado na primeira transmissão, não na subida: o certificado é carregado
 * sob demanda. Sem certificado, a transmissão falha como conexão (nada saiu, a nota volta para
 * reenvio) em vez de derrubar a aplicação.
 *
 * <p>Nunca loga o XML nem a chave de acesso: a chave carrega o CPF/CNPJ do emitente.
 */
@Component
@ConditionalOnProperty(name = "app.nfe.sefaz.mock.enabled", havingValue = "false")
public class SoapSefazTransport implements SefazTransport {

  private static final Logger log = LoggerFactory.getLogger(SoapSefazTransport.class);
  /** "Hoje" do projeto: o {@code dhEvento} vai com o fuso de Brasília. */
  private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");

  private final SefazProperties propriedades;
  private final KeyStoreCertificadoProvider certificado;
  private final NfeAssinador assinador;
  private final Clock clock;
  private final Function<SSLContext, ClienteSoapSefaz> fabricaCliente;
  private final AtomicLong idLote = new AtomicLong(new SecureRandom().nextLong(1, 1_000_000_000L));
  private volatile ClienteSoapSefaz cliente;

  /** Transporte sobre o certificado do emitente e a configuração {@code app.nfe.sefaz}. */
  public SoapSefazTransport(SefazProperties propriedades,
      KeyStoreCertificadoProvider certificado, NfeAssinador assinador, Clock clock) {
    this.propriedades = propriedades;
    this.certificado = certificado;
    this.assinador = assinador;
    this.clock = clock;
    this.fabricaCliente = ssl -> new ClienteSoapSefaz(ssl,
        Duration.ofMillis(propriedades.getTimeout().getConnectMs()),
        Duration.ofMillis(propriedades.getTimeout().getReadMs()));
  }

  @Override
  public SefazRetorno enviar(String xmlAssinado, String chaveAcesso, SefazAmbiente ambiente) {
    String url = endereco(chaveAcesso, ambiente).getAutorizacao();
    SefazRetorno retorno = RetornoSefazParser.lerAutorizacao(cliente().enviar(
        url, ServicoSefaz.AUTORIZACAO, MensagensSefaz.autorizacao(xmlAssinado, proximoLote())));
    log.info("SEFAZ autorizacao: cStat={} situacao={}", retorno.cStat(), retorno.situacao());
    return retorno;
  }

  @Override
  public SefazRetorno consultarStatus(String chaveAcesso, SefazAmbiente ambiente) {
    String url = endereco(chaveAcesso, ambiente).getConsulta();
    SefazRetorno retorno = RetornoSefazParser.lerConsulta(cliente().enviar(
        url, ServicoSefaz.CONSULTA, MensagensSefaz.consulta(chaveAcesso, ambiente)));
    log.info("SEFAZ consulta: cStat={} situacao={}", retorno.cStat(), retorno.situacao());
    return retorno;
  }

  @Override
  public SefazRetorno cancelar(String chaveAcesso, String justificativa,
      String protocoloAutorizacao, SefazAmbiente ambiente) {
    String url = endereco(chaveAcesso, ambiente).getEvento();
    String eventoAssinado = eventoAssinado(chaveAcesso, justificativa, protocoloAutorizacao,
        ambiente);
    SefazRetorno retorno = RetornoSefazParser.lerEvento(cliente().enviar(
        url, ServicoSefaz.EVENTO, MensagensSefaz.loteEvento(eventoAssinado, proximoLote())));
    log.info("SEFAZ evento de cancelamento: cStat={}", retorno.cStat());
    return retorno;
  }

  /**
   * Evento 110111 com o {@code infEvento} assinado pelo certificado do emitente, o mesmo do mTLS.
   * Falha aqui é antes de qualquer envio: vira conexão, e a nota segue autorizada.
   */
  private String eventoAssinado(String chaveAcesso, String justificativa,
      String protocoloAutorizacao, SefazAmbiente ambiente) {
    try {
      String documento = DocumentoEmitente.de(certificado.getCertificate(), chaveAcesso);
      String evento = MensagensSefaz.eventoCancelamento(chaveAcesso, documento,
          protocoloAutorizacao, justificativa, OffsetDateTime.now(clock.withZone(ZONA)),
          ambiente);
      return assinador.assinarElemento(evento, "infEvento",
          MensagensSefaz.idEventoCancelamento(chaveAcesso));
    } catch (RuntimeException e) {
      throw new SefazConexaoException(
          "Evento de cancelamento nao montado: " + e.getClass().getSimpleName(), e);
    }
  }

  /** Endereços da UF autorizadora (cUF da chave) ou o padrão do ambiente. */
  Endpoints endereco(String chaveAcesso, SefazAmbiente ambiente) {
    Webservices webservices = propriedades.getWebservices();
    String uf = UfIbge.uf(MensagensSefaz.cuf(chaveAcesso)).orElse(null);
    Endpoints endpoints = webservices.buscar(ambiente, uf);
    if (endpoints == null) {
      endpoints = webservices.buscar(ambiente, Webservices.PADRAO);
    }
    if (endpoints == null) {
      throw new SefazConexaoException("Sem endereco da SEFAZ para a UF " + uf + " em " + ambiente);
    }
    return endpoints;
  }

  private long proximoLote() {
    return idLote.incrementAndGet() % 1_000_000_000_000_000L;
  }

  private ClienteSoapSefaz cliente() {
    ClienteSoapSefaz atual = cliente;
    if (atual == null) {
      synchronized (this) {
        if (cliente == null) {
          cliente = fabricaCliente.apply(contextoTls());
        }
        atual = cliente;
      }
    }
    return atual;
  }

  /** TLS com o certificado do emitente (cliente) e o truststore configurado (servidor). */
  private SSLContext contextoTls() {
    try {
      char[] senha = new char[0];
      KeyStore chaves = KeyStore.getInstance("PKCS12");
      chaves.load(null, null);
      chaves.setKeyEntry("emitente", certificado.getPrivateKey(), senha,
          new Certificate[] {certificado.getCertificate()});
      KeyManagerFactory kmf =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(chaves, senha);

      TrustManagerFactory tmf =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(truststore());

      SSLContext ssl = SSLContext.getInstance("TLS");
      ssl.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
      return ssl;
    } catch (RuntimeException e) {
      // Certificado ausente ou invalido: nada foi enviado.
      throw new SefazConexaoException("Certificado do emitente indisponivel para o mTLS", e);
    } catch (Exception e) {
      throw new SefazConexaoException("Falha ao montar o TLS com a SEFAZ", e);
    }
  }

  /** Truststore configurado; sem caminho, o padrão da JVM ({@code null}). */
  private KeyStore truststore() throws Exception {
    SefazProperties.Truststore conf = propriedades.getTruststore();
    if (conf.getPath() == null || conf.getPath().isBlank()) {
      return null;
    }
    Resource recurso = new DefaultResourceLoader().getResource(conf.getPath());
    String tipo = conf.getPath().toLowerCase().endsWith(".jks") ? "JKS" : "PKCS12";
    KeyStore ks = KeyStore.getInstance(tipo);
    try (InputStream in = recurso.getInputStream()) {
      ks.load(in, conf.getPassword() == null ? null : conf.getPassword().toCharArray());
    }
    return ks;
  }
}
