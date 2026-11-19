package br.com.agrocontabil.assistente.continuidade;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.orquestracao.Intencao;
import br.com.agrocontabil.config.CaminhoCota;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  // --- o que o backend sabe de produto (#591) ---

  /** A tabela de produto do teste: termo → posições de NCM. */
  private static ResolucaoProduto tabela(String... paresTermoFamilia) {
    Map<String, Set<String>> mapa = new HashMap<>();
    for (int i = 0; i < paresTermoFamilia.length; i += 2) {
      mapa.computeIfAbsent(paresTermoFamilia[i], k -> new HashSet<>())
          .add(paresTermoFamilia[i + 1]);
    }
    return (termo, dia) -> mapa.getOrDefault(termo, Set.of());
  }

  @Test
  @DisplayName("Mesmo produto com outro nome: a tabela põe os dois na mesma família, sem prova")
  void mesmoProdutoOutroNome() {
    ResolucaoProduto tabela = tabela("fertilizante", "ncm:3105", "adubo", "ncm:3105");
    List<ChamadaAssinada> consulta = List.of(chamada("totalComprasPorInsumo",
        "{\"descricaoOuNcm\":\"fertilizante\"}"));

    assertThat(ConferenciaConsulta.contradiz("quanto comprei de adubo?", consulta, DIA, tabela))
        .isFalse();
  }

  @Test
  @DisplayName("NCM do modelo para o nome da pergunta: a mesma posição, sem prova")
  void ncmDoMesmoProduto() {
    ResolucaoProduto tabela = tabela("soja", "ncm:1201", "12019000", "ncm:1201");
    List<ChamadaAssinada> consulta = List.of(chamada("totalVendasPorProduto",
        "{\"descricaoOuNcm\":\"12019000\"}"));

    assertThat(ConferenciaConsulta.contradiz("quanto vendi de soja em grão?", consulta, DIA,
        tabela)).isFalse();
  }

  @Test
  @DisplayName("Outro produto: a tabela põe os dois em posições diferentes, é prova")
  void outroProduto() {
    ResolucaoProduto tabela = tabela("milho", "ncm:1005", "soja", "ncm:1201");
    List<ChamadaAssinada> milho = List.of(chamada("totalVendasPorProduto",
        "{\"descricaoOuNcm\":\"milho\"}"));

    assertThat(ConferenciaConsulta.contradiz("quanto vendi de soja?", milho, DIA, tabela))
        .isTrue();
    // Sem a tabela, a mesma pergunta não prova nada.
    assertThat(ConferenciaConsulta.contradiz("quanto vendi de soja?", milho, DIA)).isFalse();
  }

  @Test
  @DisplayName("NCM dito na pergunta de outro produto que o nome da consulta é prova")
  void ncmDaPerguntaDeOutroProduto() {
    ResolucaoProduto tabela = tabela("milho", "ncm:1005", "12019000", "ncm:1201");
    List<ChamadaAssinada> milho = List.of(chamada("totalVendasPorProduto",
        "{\"descricaoOuNcm\":\"milho\"}"));

    assertThat(ConferenciaConsulta.contradiz("vendas do produto de NCM 1201.90.00", milho, DIA,
        tabela)).isTrue();
  }

  @Test
  @DisplayName("Termo que a tabela não resolve, do lado da consulta ou da pergunta: sem prova")
  void termoQueNaoSeResolve() {
    List<ChamadaAssinada> consulta = List.of(chamada("totalComprasPorInsumo",
        "{\"descricaoOuNcm\":\"fertilizante\"}"));

    // O produto da consulta não se resolve.
    assertThat(ConferenciaConsulta.contradiz("quanto comprei de adubo?", consulta, DIA,
        tabela("adubo", "ncm:3105"))).isFalse();
    // Nenhum termo da pergunta se resolve.
    assertThat(ConferenciaConsulta.contradiz("quanto comprei de adubo?", consulta, DIA,
        tabela("fertilizante", "ncm:3105"))).isFalse();
  }

  @Test
  @DisplayName("Termo da pergunta que é parte do produto da consulta, e ano, não provam nada")
  void parteDoProdutoEAno() {
    ResolucaoProduto tabela = tabela("farinha", "ncm:1102", "trigo", "ncm:1101",
        "farinha de trigo", "ncm:1101", "2026", "ncm:2026", "insumo", "ncm:9999",
        "adubo", "ncm:3105");
    List<ChamadaAssinada> farinhaDeTrigo = List.of(chamada("totalVendasPorProduto",
        "{\"descricaoOuNcm\":\"farinha de trigo\"}"));

    assertThat(ConferenciaConsulta.contradiz("quanto vendi de farinha?", farinhaDeTrigo, DIA,
        tabela)).isFalse();
    // "insumo" resolve a outra posição: prova. O ano não entra como NCM (próxima asserção).
    assertThat(ConferenciaConsulta.contradiz("compras de insumo em 2026", List.of(chamada(
        "totalComprasPorInsumo", "{\"descricaoOuNcm\":\"adubo\"}")), DIA, tabela))
        .isTrue();
    assertThat(ConferenciaConsulta.contradiz("compras em 2026", List.of(chamada(
        "totalComprasPorInsumo", "{\"descricaoOuNcm\":\"adubo\"}")), DIA,
        tabela("adubo", "ncm:3105", "2026", "ncm:2026"))).isFalse();
  }

  @Test
  @DisplayName("Palavras sozinhas caem em outras posições, juntas no NCM da consulta: sem prova")
  void fraseInteiraAchaOProduto() {
    // "farelo" sozinho e "soja" sozinho acham outras posições; juntos, o farelo de soja (2304),
    // que é o NCM que o modelo escolheu.
    ResolucaoProduto tabela = (termo, dia) -> {
      if (termo.contains("farelo") && termo.contains("soja")) {
        return Set.of("ncm:2304");
      }
      return switch (termo) {
        case "farelo" -> Set.of("ncm:2302");
        case "soja" -> Set.of("ncm:1201");
        case "23040090" -> Set.of("ncm:2304");
        default -> Set.of();
      };
    };
    List<ChamadaAssinada> consulta = List.of(chamada("totalVendasPorProduto",
        "{\"descricaoOuNcm\":\"23040090\"}"));

    assertThat(ConferenciaConsulta.contradiz("quanto vendi de farelo de soja?", consulta, DIA,
        tabela)).isFalse();
  }

  @Test
  @DisplayName("Correção provada: outro produto na tabela; mesma família na consulta nova, não")
  void correcaoProvada() {
    ResolucaoProduto tabela = tabela("milho", "ncm:1005", "soja", "ncm:1201",
        "fertilizante", "ncm:3105", "fertilizante organico", "ncm:3105", "organico", "ncm:0709");
    List<ChamadaAssinada> milho = List.of(chamada("totalVendasPorProduto",
        "{\"descricaoOuNcm\":\"milho\"}"));
    List<ChamadaAssinada> soja = List.of(chamada("totalVendasPorProduto",
        "{\"descricaoOuNcm\":\"soja\"}"));

    assertThat(ConferenciaConsulta.correcaoProvada("quanto vendi de soja?", milho, soja, DIA,
        tabela)).isTrue();
    // Mês nomeado diferente é prova sem a tabela.
    assertThat(ConferenciaConsulta.correcaoProvada("quanto vendi de soja em setembro?",
        List.of(chamada("totalVendasPorProduto", "{\"descricaoOuNcm\":\"soja\","
            + "\"dataInicio\":\"2026-10-01\",\"dataFim\":\"2026-10-31\"}")), soja, DIA,
        ResolucaoProduto.NENHUMA)).isTrue();
    // A pergunta contradiz pelo produto, mas a consulta nova é da mesma família da anterior.
    List<ChamadaAssinada> fertilizante = List.of(chamada("totalComprasPorInsumo",
        "{\"descricaoOuNcm\":\"fertilizante\"}"));
    List<ChamadaAssinada> organico = List.of(chamada("totalComprasPorInsumo",
        "{\"descricaoOuNcm\":\"fertilizante organico\"}"));
    assertThat(ConferenciaConsulta.contradiz("quanto comprei de insumo organico?", fertilizante,
        DIA, tabela)).isTrue();
    assertThat(ConferenciaConsulta.correcaoProvada("quanto comprei de insumo organico?",
        fertilizante, organico, DIA, tabela)).isFalse();
  }

  @Test
  @DisplayName("Decisão: outro produto vai ao modelo; mesmo produto com outro nome, não")
  void decisaoComATabela() {
    ResolucaoProduto tabela = tabela("milho", "ncm:1005", "soja", "ncm:1201",
        "fertilizante", "ncm:3105", "adubo", "ncm:3105");
    ParConferido outroProduto = par("quanto vendi de soja?", List.of(chamada(
        "totalVendasPorProduto", "{\"descricaoOuNcm\":\"milho\"}")));
    ParConferido outroNome = par("quanto comprei de adubo?", List.of(chamada(
        "totalComprasPorInsumo", "{\"descricaoOuNcm\":\"fertilizante\"}")));

    assertThat(DecisaoContinuidade.consultaSemContradicao(outroProduto, tabela)).isFalse();
    assertThat(DecisaoContinuidade.custoDaConfirmacao(outroProduto, tabela))
        .isEqualTo(CaminhoCota.NORMAL);
    assertThat(DecisaoContinuidade.consultaSemContradicao(outroNome, tabela)).isTrue();
    assertThat(DecisaoContinuidade.custoDaConfirmacao(outroNome, tabela))
        .isEqualTo(CaminhoCota.SEM_MODELO);
  }

  // --- a dúvida que a tabela não decide: o julgamento de produto (#591) ---

  @Test
  @DisplayName("Produto fora da tabela e palavra da pergunta de outro grupo: dúvida, não prova")
  void duvidaDeProduto() {
    // "diesel" não está na tabela de produto; "herbicida" só em item de anexo (sem NCM único).
    List<ChamadaAssinada> diesel = List.of(chamada("totalComprasPorInsumo",
        "{\"descricaoOuNcm\":\"diesel\"}"));

    assertThat(ConferenciaConsulta.contradiz("quanto comprei de herbicida em setembro?", diesel,
        DIA, ResolucaoProduto.NENHUMA)).isFalse();
    assertThat(ConferenciaConsulta.duvidaDeProduto("quanto comprei de herbicida em setembro?",
        diesel, DIA, ResolucaoProduto.NENHUMA)).hasValueSatisfying(d -> {
          assertThat(d.produto()).isEqualTo("diesel");
          assertThat(d.termos()).contains("herbicida");
        });
  }

  @Test
  @DisplayName("Sem dúvida: produto na pergunta, tabela decide (mesmo ou outro) ou sem consulta")
  void semDuvida() {
    ResolucaoProduto tabela = tabela("milho", "ncm:1005", "soja", "ncm:1201",
        "fertilizante", "ncm:3105", "adubo", "ncm:3105");
    List<ChamadaAssinada> milho = List.of(chamada("totalVendasPorProduto",
        "{\"descricaoOuNcm\":\"milho\"}"));

    // O produto aparece na pergunta.
    assertThat(ConferenciaConsulta.duvidaDeProduto("quanto vendi de milho?", milho, DIA, tabela))
        .isEmpty();
    // A tabela diz outro produto: é prova, não dúvida.
    assertThat(ConferenciaConsulta.duvidaDeProduto("quanto vendi de soja?", milho, DIA, tabela))
        .isEmpty();
    // A tabela diz o mesmo produto.
    assertThat(ConferenciaConsulta.duvidaDeProduto("quanto comprei de adubo?", List.of(chamada(
        "totalComprasPorInsumo", "{\"descricaoOuNcm\":\"fertilizante\"}")), DIA, tabela)).isEmpty();
    // Outra contradição (mês) já é prova.
    assertThat(ConferenciaConsulta.duvidaDeProduto("quanto vendi de soja em setembro?",
        List.of(chamada("totalVendasPorProduto", "{\"descricaoOuNcm\":\"diesel\","
            + "\"dataInicio\":\"2026-10-01\",\"dataFim\":\"2026-10-31\"}")), DIA, tabela)).isEmpty();
    assertThat(ConferenciaConsulta.duvidaDeProduto("e agora?", List.of(), DIA, tabela)).isEmpty();
  }

  @Test
  @DisplayName("Decisão: a dúvida só vai ao julgamento com ele ligado, e só nos pares de dados")
  void decisaoDaDuvida() {
    ParConferido par = par("quanto comprei de herbicida em setembro?", List.of(chamada(
        "totalComprasPorInsumo", "{\"descricaoOuNcm\":\"diesel\"}")));
    ProvaDeProduto semJulgamento = ProvaDeProduto.de(ResolucaoProduto.NENHUMA);
    ProvaDeProduto comJulgamento = new ProvaDeProduto(ResolucaoProduto.NENHUMA,
        org.mockito.Mockito.mock(JulgamentoDeProduto.class));

    assertThat(DecisaoContinuidade.avaliarConsulta(par, semJulgamento))
        .isEqualTo(DecisaoContinuidade.Consulta.REEXECUTAR);
    assertThat(DecisaoContinuidade.custoDaConfirmacao(par, semJulgamento))
        .isEqualTo(CaminhoCota.SEM_MODELO);
    assertThat(DecisaoContinuidade.avaliarConsulta(par, comJulgamento))
        .isEqualTo(DecisaoContinuidade.Consulta.DUVIDA_DE_PRODUTO);
    // O caminho reserva o julgamento e o caminho normal de dados.
    assertThat(DecisaoContinuidade.custoDaConfirmacao(par, comJulgamento))
        .isEqualTo(CaminhoCota.CONFIRMACAO_COM_JULGAMENTO);
    // No MISTO a dúvida não vai ao julgamento: reexecuta como antes.
    ParConferido misto = new ParConferido(par.pergunta(), "Resposta.", "v1.a.b",
        new CargaTurno(EXP, Intencao.MISTO, false, par.carga().consulta(), HashDados.de("x"),
            null, false, null), 0, false);
    assertThat(DecisaoContinuidade.avaliarConsulta(misto, comJulgamento))
        .isEqualTo(DecisaoContinuidade.Consulta.REEXECUTAR);
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
