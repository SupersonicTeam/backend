package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.model.ClassificacaoTributariaAnexoItem;
import br.com.agrocontabil.model.NcmReferencia;
import br.com.agrocontabil.repository.ClassificacaoTributariaAnexoItemRepository;
import br.com.agrocontabil.repository.ClassificacaoTributariaAnexoNcmRepository;
import br.com.agrocontabil.repository.NcmReferenciaRepository;
import br.com.agrocontabil.service.TratamentoProdutoService.Catalogo;
import br.com.agrocontabil.service.TratamentoProdutoService.Descrito;
import br.com.agrocontabil.service.TratamentoProdutoService.TipoBusca;
import br.com.agrocontabil.service.TratamentoProdutoService.TratamentoProduto;
import br.com.agrocontabil.service.TratamentoTributarioNcm.Origem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Testes de unidade do {@link TratamentoProdutoService} (issue #283). */
class TratamentoProdutoServiceTest {

  private static final LocalDate DATA = LocalDate.of(2026, 11, 2);

  private ClassificacaoTributariaService classificacao;
  private NcmReferenciaRepository referencia;
  private ClassificacaoTributariaAnexoItemRepository itens;
  private ClassificacaoTributariaAnexoNcmRepository anexos;
  private TratamentoProdutoService service;

  @BeforeEach
  void setUp() {
    classificacao = mock(ClassificacaoTributariaService.class);
    referencia = mock(NcmReferenciaRepository.class);
    itens = mock(ClassificacaoTributariaAnexoItemRepository.class);
    anexos = mock(ClassificacaoTributariaAnexoNcmRepository.class);
    service = new TratamentoProdutoService(classificacao, referencia, itens, anexos);
    when(referencia.findAll()).thenReturn(List.of(
        new NcmReferencia("12019000", "Soja, mesmo triturada", true),
        new NcmReferencia("10059010", "Milho em grao", true)));
    when(itens.findAll()).thenReturn(List.of(ClassificacaoTributariaAnexoItem.builder().id(59)
        .cClassTrib("200038").anexo(9).descricao("Fertilizantes (adubos), conforme a NCM/SH")
        .build()));
  }

  @ParameterizedTest
  @CsvSource({"feijoes, feijao", "paes, pao", "graos, grao", "flores, flor", "ovos, ovo",
      "adubos, adubo", "ovo, ovo", "arroz, arroz", "bens, bem"})
  @DisplayName("singular aproximado do portugues")
  void singular(String plural, String singular) {
    assertThat(TratamentoProdutoService.singular(plural)).isEqualTo(singular);
  }

  @Test
  @DisplayName("palavras: sem acento, sem as vazias (inclusive 'ncm'), no singular, sem repetir")
  void palavras() {
    assertThat(TratamentoProdutoService.palavras("Grãos de SOJA, ncm e soja"))
        .containsExactly("grao", "soja");
    assertThat(TratamentoProdutoService.palavras("de o a")).isEmpty();
  }

  @ParameterizedTest
  @CsvSource(value = {"12019000|12019000", "1201.90.00|12019000", "NCM 1201.90.00|12019000",
      "ncm: 1201 90 00|12019000", "1201|", "soja|", "NCM soja|"}, delimiter = '|')
  @DisplayName("codigo NCM com pontos, espacos e prefixo; o resto nao e codigo")
  void codigoNcm(String termo, String codigo) {
    assertThat(TratamentoProdutoService.codigoNcm(termo)).isEqualTo(codigo);
  }

  @Test
  @DisplayName("artigo: so o 'observado o art.' da hipotese, nao outro art. citado antes")
  void artigo() {
    assertThat(TratamentoProdutoService.artigo("nos termos do art. 8º da EC, observado o art. 125"
        + " da LC")).isEqualTo("125");
    assertThat(TratamentoProdutoService.artigo("Observado o art. 137")).isEqualTo("137");
    assertThat(TratamentoProdutoService.artigo("Situacoes tributadas integralmente")).isNull();
    assertThat(TratamentoProdutoService.artigo(null)).isNull();
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      // referencia de NCM (changeSets 0007/0046): o trecho negado sai ate a virgula ou o fim
      "Soja, mesmo triturada, exceto para semeadura|soja,triturada|semeadura",
      "Soja, mesmo triturada (exceto para semeadura)|soja,triturada|semeadura",
      "Soja para semeadura|soja,semeadura|",
      "Outros ovos frescos de aves, com casca (exceto de galinha)|ovo,fresco,casca|galinha",
      "Suinos vivos (exceto reprodutores de raca pura)|suino,vivo|reprodutor,raca,pura",
      // "nao" nega so a palavra seguinte: o resto faz parte do nome do produto
      "Cafe nao torrado, nao descafeinado, em grao|cafe,grao|torrado,descafeinado",
      "Algodao em bruto, nao cardado nem penteado|algodao,bruto|cardado,penteado",
      // anexos da LC 214 (CSV do changeSet 0048): dentro de parenteses vale ate o ')'
      "Crustaceos (exceto lagostas e lagostim) e moluscos|crustaceo,molusco|lagosta,lagostim",
      "Farinha das posicoes 1101.00; ressalvados os produtos relacionados no Anexo I|"
          + "farinha,posicao|relacionado,produto,anexo",
      "Cereais do capitulo 10, ressalvados os produtos do Anexo I|cereai,capitulo|anexo",
      "Racoes para animais, concentrados, exceto para animais domesticos|"
          + "racao,animai,concentrado|domestico",
      "Queijo fresco não maturado e queijo do reino|queijo,fresco,reino|maturado",
      "Semente não certificada de primeira geração (S1)|"
          + "semente,primeira,geracao|certificada"})
  @DisplayName("#369: descricao sem o trecho negado (exceto, excluidos, ressalvados, salvo, nao)")
  void descricaoSemTrechoNegado(String descricao, String presentes, String ausentes) {
    List<String> palavras = Descrito.de("00000000", null, descricao).palavras();

    assertThat(palavras).contains(presentes.split(","));
    if (ausentes != null) {
      assertThat(palavras).doesNotContain(ausentes.split(","));
    }
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "soja salvo semente, milho|soja , milho",
      "graos excluidos os rejeitados; trigo|graos ; trigo",
      "frutas excetuadas as secas e as nozes. cafe|frutas . cafe",
      "ressalvados os produtos do anexo i|''",
      "x (exceto a (b, c), d), e y|x ( ), e y",
      "x (exceto a, b) y, z|x ( ) y, z",
      "arroz exceto sem fim|arroz",
      "cafe nao|cafe",
      "cafe nao torrado e nem moido|cafe e"})
  @DisplayName("#369: escopo exato do trecho negado (salvo, excluidos, ponto final, parenteses)")
  void escopoDoTrechoNegado(String entrada, String esperado) {
    assertThat(TratamentoProdutoService.semTrechoNegado(entrada).replaceAll("\s+", " ").strip())
        .isEqualTo(esperado.replace("'", ""));
  }

  @Test
  @DisplayName("#369: 'nao destinados a ...' nega a oracao inteira; sem negacao nada sai")
  void negacaoDeDestino() {
    assertThat(Descrito.de(null, 1, "Sal, nao destinados a alimentacao humana, a granel")
        .palavras()).contains("sal", "granel").doesNotContain("alimentacao", "humana");
    assertThat(Descrito.de(null, 2, "Grao de soja inteiro").palavras())
        .containsExactly("grao", "de", "soja", "inteiro");
  }

  @Test
  @DisplayName("#369: a pergunta com negacao tambem perde o trecho negado")
  void perguntaComNegacao() {
    assertThat(TratamentoProdutoService.palavras("soja exceto para semeadura"))
        .containsExactly("soja");
    assertThat(TratamentoProdutoService.palavras("soja para semeadura"))
        .containsExactly("soja", "semeadura");
  }

  @Test
  @DisplayName("#369: 'soja para semeadura' fica com a semente, nao com 'exceto para semeadura'")
  void semeaduraNaoCasaOExcluido() {
    Catalogo catalogo = new Catalogo(List.of(
        Descrito.de("12011000", null, "Soja para semeadura"),
        Descrito.de("12019000", null, "Soja, mesmo triturada, exceto para semeadura")));

    assertThat(TratamentoProdutoService.melhores(catalogo, List.of("soja", "semeadura")))
        .extracting(Descrito::ncm).containsExactly("12011000");
  }

  @Test
  @DisplayName("a palavra mais rara decide e, no empate, a referencia vence o item de anexo")
  void melhores() {
    Catalogo catalogo = new Catalogo(List.of(
        Descrito.de("12019000", null, "Soja, mesmo triturada"),
        Descrito.de("10059010", null, "Milho em grao"),
        Descrito.de("09011110", null, "Cafe em grao"),
        Descrito.de(null, 1, "Soja em grao para racao")));

    assertThat(TratamentoProdutoService.melhores(catalogo, List.of("grao", "soja")))
        .extracting(Descrito::itemId).containsExactly(1);
    assertThat(TratamentoProdutoService.melhores(catalogo, List.of("soja")))
        .extracting(Descrito::ncm).containsExactly("12019000");
    assertThat(TratamentoProdutoService.melhores(catalogo, List.of("trator"))).isEmpty();
  }

  @Test
  @DisplayName("item de anexo: os NCMs do item agrupados por tratamento, numa consulta em lote")
  void itemAgrupado() {
    when(anexos.findNcmsPermitidosDoItem(59, DATA))
        .thenReturn(List.of("31010000", "31021010", "31022100"));
    Map<String, TratamentoTributarioNcm> lote = new LinkedHashMap<>();
    lote.put("31010000", tratamento("31010000", "200038", "60"));
    lote.put("31021010", tratamento("31021010", "200038", "60"));
    lote.put("31022100", tratamento("31022100", "200003", "100"));
    when(classificacao.tratamentosPorNcm(anyCollection(), eq(DATA))).thenReturn(lote);

    var c = service.consultar("adubo", DATA);

    assertThat(c.tipo()).isEqualTo(TipoBusca.ITEM_ANEXO);
    assertThat(c.produtos())
        .extracting(TratamentoProduto::ncm, TratamentoProduto::quantidadeNcms,
            TratamentoProduto::cClassTrib)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(null, 2, "200038"),
            org.assertj.core.groups.Tuple.tuple("31022100", 1, "200003"));
    verify(classificacao, times(1)).tratamentosPorNcm(anyCollection(), any());
  }

  @Test
  @DisplayName("catalogo carregado uma vez so (tabelas estaticas)")
  void catalogoEmCache() {
    when(classificacao.tratamentosPorNcm(anyCollection(), any()))
        .thenReturn(Map.of("12019000", tratamento("12019000", "200036", "60")));

    service.consultar("soja", DATA);
    service.consultar("soja", DATA);

    verify(referencia, times(1)).findAll();
    verify(itens, times(1)).findAll();
  }

  @Test
  @DisplayName("NCM fora da referencia e de todo anexo: desconhecido; na referencia: conhecido")
  void ncmConhecido() {
    when(referencia.findById("99999999")).thenReturn(Optional.empty());
    when(classificacao.tratamentoPorNcm("99999999", DATA))
        .thenReturn(tratamento("99999999", "000001", "0"));
    when(referencia.findById("12019000"))
        .thenReturn(Optional.of(new NcmReferencia("12019000", "Soja", true)));
    when(classificacao.tratamentoPorNcm("12019000", DATA))
        .thenReturn(tratamento("12019000", "200036", "60"));

    assertThat(service.consultar("99999999", DATA).produtos().get(0).ncmConhecido()).isFalse();
    assertThat(service.consultar("NCM 1201.90.00", DATA).produtos().get(0))
        .satisfies(p -> {
          assertThat(p.ncmConhecido()).isTrue();
          assertThat(p.descricao()).isEqualTo("Soja");
        });
  }

  private static TratamentoTributarioNcm tratamento(String ncm, String codigo, String reducao) {
    boolean integral = codigo.equals("000001");
    return new TratamentoTributarioNcm(ncm, codigo, codigo.substring(0, 3),
        new BigDecimal(reducao), integral ? Origem.SEM_REDUCAO : Origem.ANEXO,
        integral ? null : "observado o art. 138", List.of(), "IT 2025.002 v1.70");
  }
}
