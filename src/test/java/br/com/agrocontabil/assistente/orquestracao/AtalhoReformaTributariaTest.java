package br.com.agrocontabil.assistente.orquestracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.PerguntaInterrompidaException;
import br.com.agrocontabil.assistente.porta.DadosProdutorPorta;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.Produto;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.TratamentoConsultado;
import br.com.agrocontabil.assistente.rag.BuscaTextualLegislacaoService;
import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import br.com.agrocontabil.model.DesfechoInteracao;
import br.com.agrocontabil.model.TipoProdutor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Atalho "O que muda para mim com o IBS e o CBS?" (#347): texto do backend personalizado, citações
 * com o texto da base, e cada parte do perfil que falha fica de fora sem derrubar a resposta.
 */
class AtalhoReformaTributariaTest {

  private static final LocalDate HOJE = LocalDate.of(2026, 11, 5);
  private static final Produto SOJA = new Produto("12019000", "Soja, mesmo triturada", 1,
      "515001", new BigDecimal("60.00"), true, null, "137", null, true);

  private static Map<String, String> lei;

  private final BuscaTextualLegislacaoService busca = mock(BuscaTextualLegislacaoService.class);
  private final DadosProdutorPorta dados = mock(DadosProdutorPorta.class);
  private final TratamentoProdutoPorta tratamento = mock(TratamentoProdutoPorta.class);
  private final AtalhoReformaTributaria atalho =
      new AtalhoReformaTributaria(busca, dados, tratamento);
  private ContextoAssistente contexto;

  @BeforeAll
  static void carregarFixture() throws IOException {
    try (InputStream in = AtalhoReformaTributariaTest.class
        .getResourceAsStream("/ceres/atalho-reforma-trechos.json")) {
      lei = new ObjectMapper().readValue(in, new TypeReference<Map<String, String>>() { });
    }
  }

  @BeforeEach
  void setUp() {
    contexto = new ContextoAssistente(UUID.randomUUID(), HOJE);
    when(busca.buscarDispositivos(anyList(), any())).thenAnswer(inv -> {
      List<String> chaves = inv.getArgument(0);
      return chaves.stream().filter(lei::containsKey).map(AtalhoReformaTributariaTest::trecho)
          .toList();
    });
    when(dados.perfilDoAtalho(any(), any(), any(), anyInt())).thenReturn(
        new DadosProdutorPorta.PerfilAtalho(TipoProdutor.PESSOA_FISICA, true,
            List.of("12019000")));
    when(tratamento.consultar(eq("12019000"), any()))
        .thenReturn(new TratamentoConsultado("12019000", List.of(SOJA), "IT 2025.002 v1.70"));
  }

  @Test
  @DisplayName("Texto personalizado do backend e as citacoes com o texto da base, na ordem do texto")
  void textoECitacoes() {
    ResultadoOrquestracao r = atalho.responder(contexto);

    TextoReformaTributaria.Texto esperado = TextoReformaTributaria.montar(
        new TextoReformaTributaria.Perfil(TipoProdutor.PESSOA_FISICA, true, List.of(SOJA)));
    assertThat(r.rota()).isEqualTo(Intencao.LEGISLACAO);
    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(r.exibirFontes()).isFalse();
    assertThat(r.modoFallback()).isFalse();
    assertThat(r.texto()).isEqualTo(esperado.texto())
        .contains("Soja, mesmo triturada (NCM 1201.90.00)")
        .contains(TextoReformaTributaria.PAGAMENTO_PESSOA_FISICA)
        .contains(TextoReformaTributaria.PROXIMO_COM_ESCRITORIO);
    assertThat(r.citacoes()).extracting(AtalhoReformaTributariaTest::chave)
        .containsExactlyElementsOf(esperado.dispositivos());
    assertThat(r.citacoes()).allSatisfy(c -> assertThat(c.trecho()).isEqualTo(lei.get(chave(c))));
    assertThat(contexto.trechosCitados()).hasSize(esperado.dispositivos().size());
  }

  @Test
  @DisplayName("Base de legislacao fora: o texto do backend sai inteiro, sem citacoes")
  void baseFora() {
    when(busca.buscarDispositivos(anyList(), any()))
        .thenThrow(new AssistenteIndisponivelException("base fora", null));

    ResultadoOrquestracao r = atalho.responder(contexto);

    assertThat(r.desfecho()).isEqualTo(DesfechoInteracao.RESPONDIDA);
    assertThat(r.texto()).startsWith(TextoReformaTributaria.TITULO);
    assertThat(r.citacoes()).isEmpty();
  }

  @Test
  @DisplayName("Perfil indisponivel: texto generico (sem Funrural, sem produtos, proximo passo "
      + "com o contador), e o atalho responde")
  void perfilIndisponivel() {
    when(dados.perfilDoAtalho(any(), any(), any(), anyInt()))
        .thenThrow(new IllegalStateException("banco fora"));

    ResultadoOrquestracao r = atalho.responder(contexto);

    assertThat(r.texto()).isEqualTo(
        TextoReformaTributaria.montar(TextoReformaTributaria.Perfil.DESCONHECIDO).texto());
    assertThat(r.texto()).contains(TextoReformaTributaria.VENDA_SEM_PRODUTOS)
        .contains(TextoReformaTributaria.PAGAMENTO_OUTROS)
        .contains(TextoReformaTributaria.PROXIMO_GENERICO);
    verifyNoInteractions(tratamento);
  }

  @Test
  @DisplayName("Perfil das vendas do ano: de 1º de janeiro a hoje, ate 3 NCMs; NCM desconhecido "
      + "ou consulta que falha ficam de fora")
  void produtosDoPerfil() {
    when(dados.perfilDoAtalho(contexto.produtorId(), LocalDate.of(2026, 1, 1), HOJE,
        AtalhoReformaTributaria.MAX_PRODUTOS)).thenReturn(new DadosProdutorPorta.PerfilAtalho(
            TipoProdutor.PESSOA_JURIDICA, false, List.of("99999999", "12019000", "10063021")));
    when(tratamento.consultar(eq("99999999"), any())).thenReturn(new TratamentoConsultado(
        "99999999", List.of(new Produto("99999999", null, 1, "000001", BigDecimal.ZERO, false,
            null, null, null, false)), "v"));
    when(tratamento.consultar(eq("10063021"), any()))
        .thenThrow(new IllegalStateException("tabela fora"));

    TextoReformaTributaria.Perfil perfil = atalho.perfil(contexto);

    assertThat(perfil.tipo()).isEqualTo(TipoProdutor.PESSOA_JURIDICA);
    assertThat(perfil.atendidoPorEscritorio()).isFalse();
    assertThat(perfil.produtos()).containsExactly(SOJA);
  }

  @Test
  @DisplayName("Produto de outro NCM na consulta (grupo do anexo) nao entra pelo NCM pedido")
  void soONcmPedido() {
    Produto outro = new Produto("12011000", "Soja para semeadura", 1, "000001", BigDecimal.ZERO,
        false, null, null, null, true);
    when(tratamento.consultar(eq("12019000"), any()))
        .thenReturn(new TratamentoConsultado("12019000", List.of(outro, SOJA), "v"));

    assertThat(atalho.perfil(contexto).produtos()).containsExactly(SOJA);
  }

  @Test
  @DisplayName("Pergunta ja interrompida: nada e consultado")
  void jaInterrompida() {
    contexto.interromper(DesfechoInteracao.TEMPO_ESGOTADO);

    assertThatThrownBy(() -> atalho.responder(contexto))
        .isInstanceOf(PerguntaInterrompidaException.class);
    verifyNoInteractions(dados, tratamento, busca);
  }

  @Test
  @DisplayName("Interrupcao vinda das portas sobe (nao vira perfil generico nem produto a menos)")
  void interrupcaoDasPortas() {
    when(tratamento.consultar(eq("12019000"), any()))
        .thenThrow(new PerguntaInterrompidaException());
    assertThatThrownBy(() -> atalho.responder(contexto))
        .isInstanceOf(PerguntaInterrompidaException.class);

    when(dados.perfilDoAtalho(any(), any(), any(), anyInt()))
        .thenThrow(new PerguntaInterrompidaException());
    assertThatThrownBy(() -> atalho.responder(contexto))
        .isInstanceOf(PerguntaInterrompidaException.class);
  }

  private static TrechoRecuperado trecho(String chave) {
    int barra = chave.indexOf('|');
    return new TrechoRecuperado(null, "id-" + chave, chave.substring(0, barra),
        chave.substring(barra + 1), "https://www.planalto.gov.br", lei.get(chave), 0);
  }

  private static String chave(CitacaoLegal citacao) {
    return citacao.norma() + "|" + citacao.dispositivo();
  }
}
