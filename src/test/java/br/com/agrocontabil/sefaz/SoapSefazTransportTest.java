package br.com.agrocontabil.sefaz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.config.CertificadoDigitalProperties;
import br.com.agrocontabil.config.SefazProperties;
import br.com.agrocontabil.config.SefazProperties.Endpoints;
import br.com.agrocontabil.exception.SefazConexaoException;
import br.com.agrocontabil.exception.SefazRespostaIndeterminadaException;
import br.com.agrocontabil.nfe.AssinaturaValidator;
import br.com.agrocontabil.nfe.KeyStoreCertificadoProvider;
import br.com.agrocontabil.nfe.NfeAssinador;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Transporte SOAP + mTLS da SEFAZ (issue #128) contra um servidor falso que exige certificado de
 * cliente: mensagens, mapeamento de cStat e a classificação de falhas do contrato.
 */
class SoapSefazTransportTest {

  /** Chave de uma NF-e do PR (cUF 41). */
  private static final String CHAVE = "41261011444777000161550010000001011000000014";
  private static final String NFE_ASSINADA =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?><NFe xmlns=\"http://www.portalfiscal.inf.br/nfe\">"
          + "<infNFe Id=\"NFe" + CHAVE + "\" versao=\"4.00\"/></NFe>";

  private ServidorSefazFalso servidor;

  @BeforeEach
  void subir() throws Exception {
    servidor = new ServidorSefazFalso();
  }

  @AfterEach
  void derrubar() {
    servidor.close();
  }

  private SefazProperties propriedades(String base, boolean comTruststore) {
    SefazProperties p = new SefazProperties();
    p.getMock().setEnabled(false);
    p.getTimeout().setConnectMs(2_000);
    p.getTimeout().setReadMs(1_000);
    Endpoints padrao = new Endpoints();
    padrao.setAutorizacao(base + "/autorizacao");
    padrao.setConsulta(base + "/consulta");
    padrao.setEvento(base + "/evento");
    p.getWebservices().getHomologacao().put("PADRAO", padrao);
    if (comTruststore) {
      p.getTruststore().setPath("classpath:certs/sefaz-fake-truststore.p12");
      p.getTruststore().setPassword("changeit");
    }
    return p;
  }

  private static final KeyStoreCertificadoProvider CERTIFICADO = new KeyStoreCertificadoProvider(
      new CertificadoDigitalProperties("classpath:certs/nfe-teste.p12", "changeit", "nfe-teste"));

  private SoapSefazTransport transporte(SefazProperties p) {
    return new SoapSefazTransport(p, CERTIFICADO, new NfeAssinador(CERTIFICADO),
        Clock.fixed(Instant.parse("2026-10-14T13:00:00Z"), ZoneOffset.UTC));
  }

  private static String retEnvEvento(String cStatLote, String infEvento) {
    return ServidorSefazFalso.envelope(ServicoSefaz.EVENTO.namespace(),
        "<retEnvEvento xmlns=\"http://www.portalfiscal.inf.br/nfe\" versao=\"1.00\">"
            + "<idLote>1</idLote><tpAmb>2</tpAmb><verAplic>SVRS</verAplic><cOrgao>41</cOrgao>"
            + "<cStat>" + cStatLote + "</cStat><xMotivo>Lote de evento processado</xMotivo>"
            + (infEvento == null ? "" : "<retEvento versao=\"1.00\"><infEvento>" + infEvento
                + "</infEvento></retEvento>")
            + "</retEnvEvento>");
  }

  private SoapSefazTransport transporte() {
    return transporte(propriedades(servidor.url(""), true));
  }

  private static String retEnviNFe(String cStatLote, String infProt) {
    return ServidorSefazFalso.envelope(ServicoSefaz.AUTORIZACAO.namespace(),
        "<retEnviNFe xmlns=\"http://www.portalfiscal.inf.br/nfe\" versao=\"4.00\">"
            + "<tpAmb>2</tpAmb><verAplic>SVRS</verAplic><cStat>" + cStatLote + "</cStat>"
            + "<xMotivo>Lote processado</xMotivo><cUF>41</cUF>"
            + (infProt == null ? "" : "<protNFe versao=\"4.00\"><infProt>" + infProt
                + "</infProt></protNFe>")
            + "</retEnviNFe>");
  }

  private static String retConsSitNFe(String cStat, String extra) {
    return ServidorSefazFalso.envelope(ServicoSefaz.CONSULTA.namespace(),
        "<retConsSitNFe xmlns=\"http://www.portalfiscal.inf.br/nfe\" versao=\"4.00\">"
            + "<tpAmb>2</tpAmb><verAplic>SVRS</verAplic><cStat>" + cStat + "</cStat>"
            + "<xMotivo>motivo " + cStat + "</xMotivo><cUF>41</cUF><chNFe>" + CHAVE + "</chNFe>"
            + extra + "</retConsSitNFe>");
  }

  @Nested
  @DisplayName("Envio (NFeAutorizacao4)")
  class Envio {

    @Test
    @DisplayName("Autorizada: protocolo e data do fisco; mTLS e envelope SOAP 1.2 corretos")
    void autorizada() {
      servidor.responder(200, retEnviNFe("104", "<tpAmb>2</tpAmb><chNFe>" + CHAVE + "</chNFe>"
          + "<dhRecbto>2026-10-14T10:15:30-03:00</dhRecbto><nProt>141260000012345</nProt>"
          + "<cStat>100</cStat><xMotivo>Autorizado o uso da NF-e</xMotivo>"));

      SefazRetorno r = transporte().enviar(NFE_ASSINADA, CHAVE, SefazAmbiente.HOMOLOGACAO);

      assertThat(r.situacao()).isEqualTo(SituacaoSefaz.AUTORIZADA);
      assertThat(r.cStat()).isEqualTo("100");
      assertThat(r.protocolo()).isEqualTo("141260000012345");
      assertThat(r.dataAutorizacao()).isNotNull();

      ServidorSefazFalso.Recebida req = servidor.recebidas().get(0);
      assertThat(req.caminho()).isEqualTo("/autorizacao");
      assertThat(req.certificadoCliente()).contains("CN=NFe Teste AgroContabil");
      assertThat(req.contentType()).startsWith("application/soap+xml")
          .contains("action=\"" + ServicoSefaz.AUTORIZACAO.acao() + "\"");
      assertThat(req.corpo()).contains("<indSinc>1</indSinc>", "<NFe xmlns=")
          .contains("nfeDadosMsg xmlns=\"" + ServicoSefaz.AUTORIZACAO.namespace() + "\"");
      // A NF-e vai dentro do enviNFe sem a propria declaracao XML.
      assertThat(req.corpo().indexOf("<?xml")).isEqualTo(req.corpo().lastIndexOf("<?xml"));
    }

    @Test
    @DisplayName("204 (duplicidade) volta como rejeicao com o cStat: o servico reconcilia")
    void duplicidade() {
      servidor.responder(200, retEnviNFe("104",
          "<cStat>204</cStat><xMotivo>Rejeicao: Duplicidade de NF-e</xMotivo>"));

      SefazRetorno r = transporte().enviar(NFE_ASSINADA, CHAVE, SefazAmbiente.HOMOLOGACAO);

      assertThat(r.situacao()).isEqualTo(SituacaoSefaz.REJEITADA);
      assertThat(r.cStat()).isEqualTo("204");
    }

    @Test
    @DisplayName("Denegada (301) e lote recebido assincrono (103) mapeados")
    void denegadaEAssincrono() {
      servidor.responder(200, retEnviNFe("104",
          "<cStat>301</cStat><xMotivo>Uso Denegado</xMotivo><nProt>1</nProt>"));
      assertThat(transporte().enviar(NFE_ASSINADA, CHAVE, SefazAmbiente.HOMOLOGACAO).situacao())
          .isEqualTo(SituacaoSefaz.DENEGADA);

      servidor.responder(200, retEnviNFe("103", null));
      assertThat(transporte().enviar(NFE_ASSINADA, CHAVE, SefazAmbiente.HOMOLOGACAO).situacao())
          .isEqualTo(SituacaoSefaz.PENDENTE);
    }

    @Test
    @DisplayName("Servico paralisado (108) nao processou nada: falha de conexao, reenvio seguro")
    void servicoParalisado() {
      servidor.responder(200, retEnviNFe("108", null));

      assertThatThrownBy(() -> transporte().enviar(NFE_ASSINADA, CHAVE,
          SefazAmbiente.HOMOLOGACAO)).isInstanceOf(SefazConexaoException.class);
    }
  }

  @Nested
  @DisplayName("Consulta (NFeConsultaProtocolo4)")
  class Consulta {

    @Test
    @DisplayName("217: a chave nao consta no fisco")
    void naoConsta() {
      servidor.responder(200, retConsSitNFe("217", ""));

      SefazRetorno r = transporte().consultarStatus(CHAVE, SefazAmbiente.HOMOLOGACAO);

      assertThat(r.situacao()).isEqualTo(SituacaoSefaz.NAO_ENCONTRADA);
      assertThat(servidor.recebidas().get(0).corpo())
          .contains("<consSitNFe", "<tpAmb>2</tpAmb>", "<chNFe>" + CHAVE + "</chNFe>");
    }

    @Test
    @DisplayName("101: cancelada, com protocolo e data do EVENTO, nao os da autorizacao")
    void canceladaComProtocoloDoEvento() {
      servidor.responder(200, retConsSitNFe("101",
          "<protNFe versao=\"4.00\"><infProt><nProt>141260000012345</nProt>"
              + "<dhRecbto>2026-10-01T10:00:00-03:00</dhRecbto><cStat>100</cStat></infProt>"
              + "</protNFe><procEventoNFe versao=\"1.00\"><retEvento versao=\"1.00\"><infEvento>"
              + "<tpEvento>110111</tpEvento><cStat>135</cStat><nProt>141260000099999</nProt>"
              + "<dhRegEvento>2026-10-02T09:30:00-03:00</dhRegEvento></infEvento></retEvento>"
              + "</procEventoNFe>"));

      SefazRetorno r = transporte().consultarStatus(CHAVE, SefazAmbiente.HOMOLOGACAO);

      assertThat(r.situacao()).isEqualTo(SituacaoSefaz.CANCELADA);
      assertThat(r.protocolo()).isEqualTo("141260000099999");
      assertThat(r.dataAutorizacao())
          .isEqualTo(java.time.OffsetDateTime.parse("2026-10-02T09:30:00-03:00"));
    }

    @Test
    @DisplayName("100 autorizada; codigo desconhecido fica pendente (nunca rejeitada)")
    void autorizadaEPendente() {
      servidor.responder(200, retConsSitNFe("100",
          "<protNFe versao=\"4.00\"><infProt><nProt>141260000012345</nProt>"
              + "<dhRecbto>2026-10-01T10:00:00-03:00</dhRecbto><cStat>100</cStat></infProt>"
              + "</protNFe>"));
      SefazRetorno autorizada = transporte().consultarStatus(CHAVE, SefazAmbiente.HOMOLOGACAO);
      assertThat(autorizada.situacao()).isEqualTo(SituacaoSefaz.AUTORIZADA);
      assertThat(autorizada.protocolo()).isEqualTo("141260000012345");

      servidor.responder(200, retConsSitNFe("105", ""));
      assertThat(transporte().consultarStatus(CHAVE, SefazAmbiente.HOMOLOGACAO).situacao())
          .isEqualTo(SituacaoSefaz.PENDENTE);
    }
  }

  @Nested
  @DisplayName("Cancelamento (NFeRecepcaoEvento4)")
  class Cancelamento {

    private static final String JUSTIFICATIVA = "Erro no valor <unitario> & quantidade";

    @Test
    @DisplayName("Evento 110111 assinado e valido; registrado (135) volta com protocolo do evento")
    void registrado() {
      servidor.responder(200, retEnvEvento("128", "<tpAmb>2</tpAmb><cStat>135</cStat>"
          + "<xMotivo>Evento registrado e vinculado a NF-e</xMotivo><chNFe>" + CHAVE + "</chNFe>"
          + "<tpEvento>110111</tpEvento><nProt>141260000099999</nProt>"
          + "<dhRegEvento>2026-10-14T10:00:05-03:00</dhRegEvento>"));

      SefazRetorno r = transporte().cancelar(CHAVE, JUSTIFICATIVA, "141260000012345",
          SefazAmbiente.HOMOLOGACAO);

      assertThat(r.cStat()).isEqualTo("135");
      assertThat(r.protocolo()).isEqualTo("141260000099999");
      String corpo = servidor.recebidas().get(0).corpo();
      assertThat(servidor.recebidas().get(0).caminho()).isEqualTo("/evento");
      assertThat(corpo).contains("<envEvento", "<infEvento Id=\"ID110111" + CHAVE + "01\"",
          "<cOrgao>41</cOrgao>", "<CNPJ>11444777000161</CNPJ>", "<tpEvento>110111</tpEvento>",
          "<nProt>141260000012345</nProt>", "<dhEvento>2026-10-14T10:00:00-03:00</dhEvento>",
          // Justificativa escapada: o texto do usuario nao vira XML.
          "Erro no valor &lt;unitario&gt; &amp; quantidade");
      String evento = corpo.substring(corpo.indexOf("<evento"), corpo.indexOf("</evento>") + 9);
      assertThatCode(() -> new AssinaturaValidator().validar(evento)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("573 (evento ja registrado) volta como rejeicao com o cStat: o servico reconcilia")
    void duplicidadeDeEvento() {
      servidor.responder(200, retEnvEvento("128", "<cStat>573</cStat>"
          + "<xMotivo>Rejeicao: Duplicidade de Evento</xMotivo>"));

      SefazRetorno r = transporte().cancelar(CHAVE, "Erro de digitacao na nota", "1",
          SefazAmbiente.HOMOLOGACAO);

      assertThat(r.situacao()).isEqualTo(SituacaoSefaz.REJEITADA);
      assertThat(r.cStat()).isEqualTo("573");
    }

    @Test
    @DisplayName("Lote recusado (sem 128) e rejeicao; servico paralisado e conexao")
    void loteRecusadoEParalisado() {
      servidor.responder(200, retEnvEvento("215", null));
      assertThat(transporte().cancelar(CHAVE, "Erro de digitacao na nota", "1",
          SefazAmbiente.HOMOLOGACAO).cStat()).isEqualTo("215");

      servidor.responder(200, retEnvEvento("109", null));
      assertThatThrownBy(() -> transporte().cancelar(CHAVE, "Erro de digitacao na nota", "1",
          SefazAmbiente.HOMOLOGACAO)).isInstanceOf(SefazConexaoException.class);
    }
  }

  @Nested
  @DisplayName("Classificacao de falhas")
  class Falhas {

    @Test
    @DisplayName("Conexao recusada: a requisicao nao saiu")
    void conexaoRecusada() throws Exception {
      int porta;
      try (ServerSocket livre = new ServerSocket(0)) {
        porta = livre.getLocalPort();
      }
      SoapSefazTransport t = transporte(propriedades("https://localhost:" + porta, true));

      assertThatThrownBy(() -> t.enviar(NFE_ASSINADA, CHAVE, SefazAmbiente.HOMOLOGACAO))
          .isInstanceOf(SefazConexaoException.class);
    }

    @Test
    @DisplayName("Tempo de resposta estourado depois de enviar: indeterminada")
    void tempoDeLeitura() {
      servidor.atrasar(3_000).responder(200, retEnviNFe("104", "<cStat>100</cStat>"));

      assertThatThrownBy(() -> transporte().enviar(NFE_ASSINADA, CHAVE,
          SefazAmbiente.HOMOLOGACAO)).isInstanceOf(SefazRespostaIndeterminadaException.class);
      assertThat(servidor.recebidas()).hasSize(1);
    }

    @Test
    @DisplayName("Cabecalhos chegam e o corpo trava: o prazo cobre a resposta inteira")
    void corpoTravado() {
      servidor.travarNoCorpo(10_000).responder(200, retEnviNFe("104", "<cStat>100</cStat>"));

      long inicio = System.nanoTime();
      assertThatThrownBy(() -> transporte().enviar(NFE_ASSINADA, CHAVE,
          SefazAmbiente.HOMOLOGACAO)).isInstanceOf(SefazRespostaIndeterminadaException.class);
      // connect-ms (2 s) + read-ms (1 s) do teste, com folga; sem o prazo, esperaria os 10 s.
      assertThat(java.time.Duration.ofNanos(System.nanoTime() - inicio).toMillis())
          .isLessThan(6_000);
    }

    @Test
    @DisplayName("Servidor sem a cadeia confiavel: handshake falha antes de enviar, conexao")
    void servidorNaoConfiavel() {
      // Sem truststore configurado vale o da JVM, que nao conhece o certificado do servidor.
      SoapSefazTransport t = transporte(propriedades(servidor.url(""), false));

      assertThatThrownBy(() -> t.enviar(NFE_ASSINADA, CHAVE, SefazAmbiente.HOMOLOGACAO))
          .isInstanceOf(SefazConexaoException.class);
      assertThat(servidor.recebidas()).isEmpty();
    }

    @Test
    @DisplayName("HTTP 500 e SOAP Fault: indeterminada; HTTP 404: conexao (nada processado)")
    void statusHttp() {
      servidor.responder(500, "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\">"
          + "<soap:Body><soap:Fault/></soap:Body></soap:Envelope>");
      assertThatThrownBy(() -> transporte().enviar(NFE_ASSINADA, CHAVE,
          SefazAmbiente.HOMOLOGACAO)).isInstanceOf(SefazRespostaIndeterminadaException.class);

      servidor.responder(404, "");
      assertThatThrownBy(() -> transporte().enviar(NFE_ASSINADA, CHAVE,
          SefazAmbiente.HOMOLOGACAO)).isInstanceOf(SefazConexaoException.class);
    }

    @Test
    @DisplayName("Resposta malformada, sem cStat ou com DOCTYPE: indeterminada")
    void respostaIlegivel() {
      for (String corpo : new String[] {"<nao-e-xml", ServidorSefazFalso.envelope(
          ServicoSefaz.AUTORIZACAO.namespace(), "<outro/>"),
          "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///c:/windows/win.ini\">]>"
              + "<x>&e;</x>"}) {
        servidor.responder(200, corpo);
        assertThatThrownBy(() -> transporte().enviar(NFE_ASSINADA, CHAVE,
            SefazAmbiente.HOMOLOGACAO)).as(corpo)
            .isInstanceOf(SefazRespostaIndeterminadaException.class);
      }
    }

    @Test
    @DisplayName("Resposta acima do tamanho maximo: indeterminada, sem ler tudo para a memoria")
    void respostaGrandeDemais() {
      servidor.responder(200, "<x>" + "a".repeat(ClienteSoapSefaz.TAMANHO_MAXIMO_RESPOSTA) + "</x>");

      assertThatThrownBy(() -> transporte().enviar(NFE_ASSINADA, CHAVE,
          SefazAmbiente.HOMOLOGACAO)).isInstanceOf(SefazRespostaIndeterminadaException.class);
    }
  }

  @Nested
  @DisplayName("Enderecos por UF")
  class Enderecos {

    @Test
    @DisplayName("UF com endereco proprio vence o PADRAO; sem nenhum, conexao")
    void ufVencePadrao() {
      SefazProperties p = propriedades(servidor.url(""), true);
      Endpoints pr = new Endpoints();
      pr.setAutorizacao(servidor.url("/pr/autorizacao"));
      pr.setConsulta(servidor.url("/pr/consulta"));
      pr.setEvento(servidor.url("/pr/evento"));
      // Chave em minusculas, como chega de variavel de ambiente.
      p.getWebservices().getHomologacao().put("pr", pr);
      SoapSefazTransport t = transporte(p);

      assertThat(t.endereco(CHAVE, SefazAmbiente.HOMOLOGACAO).getConsulta()).endsWith("/pr/consulta");
      // MT (51) nao tem entrada propria: cai no PADRAO.
      assertThat(t.endereco("51" + CHAVE.substring(2), SefazAmbiente.HOMOLOGACAO).getConsulta())
          .endsWith("/consulta").doesNotContain("/pr/");
      assertThatThrownBy(() -> t.endereco(CHAVE, SefazAmbiente.PRODUCAO))
          .isInstanceOf(SefazConexaoException.class);
    }
  }
}
