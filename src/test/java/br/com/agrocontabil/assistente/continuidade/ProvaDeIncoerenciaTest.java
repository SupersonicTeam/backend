package br.com.agrocontabil.assistente.continuidade;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.orquestracao.Intencao;
import br.com.agrocontabil.config.CaminhoCota;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "Corrijo" só com prova (#591): a consulta anterior que a regra de texto não reconhece, mas que a
 * pergunta não contradiz, é reexecutada; só a contradição explícita manda o modelo escolher de
 * novo. Padrões escritos a partir da #581 (sinônimo ou NCM, situação com outro nome, compra ou
 * venda implícita, período relativo), nunca de um conjunto de medição.
 */
class ProvaDeIncoerenciaTest {

  private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
  /** Par emitido em 10/11/2026. */
  private static final long EXP = LocalDate.of(2026, 11, 10).atTime(15, 30).atZone(BRASILIA)
      .toEpochSecond();
  private static final LocalDate DIA = LocalDate.of(2026, 11, 10);

  // --- ausência de texto não é prova ---

  @Test
  @DisplayName("Sinônimo do produto: a palavra não aparece, nada contradiz")
  void sinonimoDoProduto() {
    List<ChamadaAssinada> consulta = List.of(chamada("totalComprasPorInsumo",
        "{\"descricaoOuNcm\":\"fertilizante\"}"));

    assertThat(ConferenciaConsulta.bate("quanto comprei de adubo?", consulta, DIA)).isFalse();
    assertThat(ConferenciaConsulta.contradiz("quanto comprei de adubo?", consulta, DIA)).isFalse();
  }

  @Test
  @DisplayName("NCM escolhido pelo modelo para um nome: sem dígitos na pergunta, sem prova")
  void ncmParaUmNome() {
    List<ChamadaAssinada> consulta = List.of(chamada("totalVendasPorProduto",
        "{\"descricaoOuNcm\":\"1201\"}"));

    assertThat(ConferenciaConsulta.bate("quanto vendi de soja em grão?", consulta, DIA)).isFalse();
    assertThat(ConferenciaConsulta.contradiz("quanto vendi de soja em grão?", consulta, DIA))
        .isFalse();
  }

  @Test
  @DisplayName("NCM diferente do que a pergunta traz é contradição")
  void ncmDiferente() {
    List<ChamadaAssinada> consulta = List.of(chamada("totalVendasPorProduto",
        "{\"descricaoOuNcm\":\"1201\"}"));

    assertThat(ConferenciaConsulta.contradiz("vendas do NCM 1005.90.10", consulta, DIA)).isTrue();
    assertThat(ConferenciaConsulta.contradiz("vendas do NCM 1201.90.00", consulta, DIA)).isFalse();
    assertThat(ConferenciaConsulta.contradiz("vendas do NCM 10059010", consulta, DIA)).isTrue();
  }

  @Test
  @DisplayName("Número de nota na pergunta não vira NCM: o NCM do modelo segue sem prova")
  void numeroDeNotaNaoEhNcm() {
    List<ChamadaAssinada> consulta = List.of(chamada("totalVendasPorProduto",
        "{\"descricaoOuNcm\":\"1201\"}"));

    assertThat(ConferenciaConsulta.contradiz("quanto vendi de soja no pedido 123456?", consulta,
        DIA)).isFalse();
  }

  @Test
  @DisplayName("Situação com outro nome: a pergunta não nomeia nenhuma, nada contradiz")
  void situacaoComOutroNome() {
    List<ChamadaAssinada> consulta = List.of(chamada("listarNotas",
        "{\"situacao\":\"COM_INCONSISTENCIAS\"}"));

    assertThat(ConferenciaConsulta.bate("quais notas deram problema?", consulta, DIA)).isFalse();
    assertThat(ConferenciaConsulta.contradiz("quais notas deram problema?", consulta, DIA))
        .isFalse();
  }

  @Test
  @DisplayName("Outra situação nomeada na pergunta é contradição")
  void outraSituacao() {
    List<ChamadaAssinada> rejeitadas = List.of(chamada("listarNotas",
        "{\"situacao\":\"REJEITADA\"}"));

    assertThat(ConferenciaConsulta.contradiz("tenho nota devolvida?", rejeitadas, DIA)).isTrue();
    assertThat(ConferenciaConsulta.contradiz("tenho nota devolvida?",
        List.of(chamada("listarNotas", "{}")), DIA)).isTrue();
  }

  @Test
  @DisplayName("Palavra de situação numa pergunta de crédito não é pedido de situação")
  void palavraDeSituacaoForaDeNotas() {
    List<ChamadaAssinada> creditos = List.of(chamada("consultarCreditos", "{}"));

    assertThat(ConferenciaConsulta.bate("qual o aproveitamento do meu crédito?", creditos, DIA))
        .isFalse();
    assertThat(ConferenciaConsulta.contradiz("qual o aproveitamento do meu crédito?", creditos,
        DIA)).isFalse();
  }

  @Test
  @DisplayName("Compra e venda na mesma pergunta: sem prova; só uma das palavras, contradição")
  void compraEVendaImplicitas() {
    List<ChamadaAssinada> saida = List.of(chamada("listarNotas", "{\"tipo\":\"SAIDA\"}"));

    assertThat(ConferenciaConsulta.contradiz("minhas notas de compra", saida, DIA)).isTrue();
    assertThat(ConferenciaConsulta.contradiz("as notas de compra que entraram na venda", saida,
        DIA)).isFalse();
    assertThat(ConferenciaConsulta.contradiz("minhas notas do mês", saida, DIA)).isFalse();
  }

  // --- número, período e mês nomeado são prova ---

  @Test
  @DisplayName("Nota de outro número é contradição; nota por extenso, sem prova")
  void notaDeOutroNumero() {
    List<ChamadaAssinada> nota12 = List.of(chamada("consultarNota", "{\"numero\":12}"));

    assertThat(ConferenciaConsulta.contradiz("e a nota 13?", nota12, DIA)).isTrue();
    assertThat(ConferenciaConsulta.contradiz("e a nota 12?", nota12, DIA)).isFalse();
    assertThat(ConferenciaConsulta.contradiz("e a nota doze?", nota12, DIA)).isFalse();
  }

  @Test
  @DisplayName("Período relativo e mês nomeado diferentes do da consulta são contradição")
  void periodo() {
    List<ChamadaAssinada> setembro = List.of(chamada("totalVendasPorProduto",
        "{\"descricaoOuNcm\":\"soja\",\"dataInicio\":\"2026-09-01\",\"dataFim\":\"2026-09-30\"}"));
    List<ChamadaAssinada> outubro = List.of(chamada("totalVendasPorProduto",
        "{\"descricaoOuNcm\":\"soja\",\"dataInicio\":\"2026-10-01\",\"dataFim\":\"2026-10-31\"}"));

    assertThat(ConferenciaConsulta.contradiz("quanto vendi de soja no mês passado?", setembro,
        DIA)).isTrue();
    assertThat(ConferenciaConsulta.contradiz("quanto vendi de soja no mês passado?", outubro,
        DIA)).isFalse();
    assertThat(ConferenciaConsulta.contradiz("quanto vendi de soja em setembro?", outubro, DIA))
        .isTrue();
    assertThat(ConferenciaConsulta.contradiz("quanto vendi de soja em setembro?",
        List.of(chamada("totalVendasPorProduto", "{\"descricaoOuNcm\":\"soja\"}")), DIA)).isTrue();
  }

  @Test
  @DisplayName("Consulta vazia ou sem contradição nenhuma")
  void semContradicao() {
    assertThat(ConferenciaConsulta.contradiz("quanto vendi de soja?", List.of(), DIA)).isFalse();
    assertThat(ConferenciaConsulta.contradiz("quanto vendi de soja?",
        List.of(chamada("totalVendasPorProduto", "{\"descricaoOuNcm\":\"soja\"}")), DIA)).isFalse();
  }

  // --- decisão de caminho e custo ---

  @Test
  @DisplayName("Par sem prova de erro: reexecuta sem modelo (custo só da leitura)")
  void decisaoSemProva() {
    ParConferido par = par("quanto comprei de adubo?", List.of(chamada("totalComprasPorInsumo",
        "{\"descricaoOuNcm\":\"fertilizante\"}")));

    assertThat(DecisaoContinuidade.consultaSemContradicao(par)).isTrue();
    assertThat(DecisaoContinuidade.custoDaConfirmacao(par)).isEqualTo(CaminhoCota.SEM_MODELO);
  }

  @Test
  @DisplayName("Par com contradição explícita: o modelo escolhe de novo")
  void decisaoComProva() {
    ParConferido par = par("quanto vendi de soja em setembro?", List.of(chamada(
        "totalVendasPorProduto",
        "{\"descricaoOuNcm\":\"soja\",\"dataInicio\":\"2026-10-01\",\"dataFim\":\"2026-10-31\"}")));

    assertThat(DecisaoContinuidade.consultaSemContradicao(par)).isFalse();
    assertThat(DecisaoContinuidade.custoDaConfirmacao(par)).isEqualTo(CaminhoCota.NORMAL);
  }

  @Test
  @DisplayName("Par sem consulta gravada não é reexecutável")
  void decisaoSemConsulta() {
    ParConferido par = new ParConferido("quanto vendi de soja?", "Resposta.", "v1.a.b",
        new CargaTurno(EXP, Intencao.DADOS, false, null, null, null, false, null), 0, false);

    assertThat(DecisaoContinuidade.consultaSemContradicao(par)).isFalse();
  }

  private static ParConferido par(String pergunta, List<ChamadaAssinada> consulta) {
    return new ParConferido(pergunta, "Resposta.", "v1.a.b",
        new CargaTurno(EXP, Intencao.DADOS, false, consulta, HashDados.de("x"), null, false, null),
        0, false);
  }

  private static ChamadaAssinada chamada(String funcao, String argumentos) {
    return ChamadaAssinada.de(funcao, argumentos).orElseThrow();
  }
}
