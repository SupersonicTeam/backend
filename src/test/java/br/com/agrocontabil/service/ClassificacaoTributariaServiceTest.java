package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.model.ClassificacaoTributaria;
import br.com.agrocontabil.model.ClassificacaoTributariaAnexoItem;
import br.com.agrocontabil.model.ClassificacaoTributariaAnexoNcm;
import br.com.agrocontabil.model.ClassificacaoTributariaAnexoNcm.Permissao;
import br.com.agrocontabil.repository.ClassificacaoTributariaAnexoNcmRepository;
import br.com.agrocontabil.repository.ClassificacaoTributariaRepository;
import br.com.agrocontabil.repository.NcmReferenciaRepository;
import br.com.agrocontabil.service.TratamentoTributarioNcm.Origem;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Testes de unidade do {@link ClassificacaoTributariaService} (issue #272). */
class ClassificacaoTributariaServiceTest {

  private static final LocalDate DATA = LocalDate.of(2026, 10, 29);

  private ClassificacaoTributariaRepository classificacoes;
  private ClassificacaoTributariaAnexoNcmRepository anexos;
  private NcmReferenciaRepository referencia;
  private ClassificacaoTributariaService service;

  @BeforeEach
  void setUp() {
    classificacoes = mock(ClassificacaoTributariaRepository.class);
    anexos = mock(ClassificacaoTributariaAnexoNcmRepository.class);
    referencia = mock(NcmReferenciaRepository.class);
    service = new ClassificacaoTributariaService(classificacoes, anexos, referencia);
    when(anexos.findVigentes(anyString(), any(LocalDate.class))).thenReturn(List.of());
    when(classificacoes.findVigente(anyString(), any(LocalDate.class))).thenReturn(List.of());
  }

  @Test
  @DisplayName("anexo de aliquota zero vence o in natura")
  void zeroVenceInNatura() {
    when(anexos.findVigentes(eq("04072100"), any())).thenReturn(List.of(
        linha("200014", 15, "04072100", Permissao.PERMITIDO, " ")));
    when(referencia.existsByCodigoAndInNaturaTrue("04072100")).thenReturn(true);
    classificacao("200014", "100");

    TratamentoTributarioNcm tratamento = service.tratamentoPorNcm("04072100", DATA);

    assertThat(tratamento.cClassTrib()).isEqualTo("200014");
    assertThat(tratamento.percentualReducao()).isEqualByComparingTo("100");
    // Celula vazia do CSV (gravada como '' pelo loadData) volta como nula.
    assertThat(tratamento.enquadramentos().get(0).condicao()).isNull();
  }

  @Test
  @DisplayName("in natura vence o anexo de 60% (soja em grao: 200036, nao 200038)")
  void inNaturaVenceSessenta() {
    when(anexos.findVigentes(eq("12019000"), any())).thenReturn(List.of(
        linha("200038", 9, "12019000", Permissao.PERMITIDO, null)));
    when(referencia.existsByCodigoAndInNaturaTrue("12019000")).thenReturn(true);
    classificacao("200036", "60");

    TratamentoTributarioNcm tratamento = service.tratamentoPorNcm("12019000", DATA);

    assertThat(tratamento.cClassTrib()).isEqualTo("200036");
    assertThat(tratamento.origem()).isEqualTo(Origem.IN_NATURA);
  }

  @Test
  @DisplayName("linha VEDADA tira o NCM do cClassTrib")
  void vedadoTira() {
    when(anexos.findVigentes(eq("10063021"), any())).thenReturn(List.of(
        linha("200034", 7, "10063021", Permissao.PERMITIDO, null),
        linha("200034", 7, "10063021", Permissao.VEDADO, null)));

    assertThat(service.enquadramentos("10063021", DATA)).isEmpty();
    assertThat(service.tratamentoPorNcm("10063021", DATA).cClassTrib()).isEqualTo("000001");
  }

  @Test
  @DisplayName("classificacao ausente ou fora de vigencia: sem reducao, integral (com WARN)")
  void classificacaoAusente() {
    when(anexos.findVigentes(eq("04072100"), any())).thenReturn(List.of(
        linha("200014", 15, "04072100", Permissao.PERMITIDO, null)));

    TratamentoTributarioNcm tratamento = service.tratamentoPorNcm("04072100", DATA);

    assertThat(tratamento.cClassTrib()).isEqualTo("000001");
    assertThat(tratamento.percentualReducao()).isEqualByComparingTo("0");
    assertThat(tratamento.origem()).isEqualTo(Origem.SEM_REDUCAO);
  }

  @Test
  @DisplayName("tabela oficial: pRedIBS igual a pRedCBS em todo cClassTrib (o calculo usa um so)")
  void reducaoIbsIgualCbsNaTabela() throws IOException {
    // O resolvedor aplica o percentual do IBS tambem a CBS. Se uma versao futura da tabela trouxer
    // percentuais diferentes, este teste falha antes de a CBS sair errada.
    List<String> linhas = ler("db/changelog/data/classificacao-tributaria.csv").lines()
        .skip(1).toList();
    assertThat(linhas).hasSizeGreaterThan(100);
    for (String linha : linhas) {
      String[] campos = linha.split(",");
      int n = campos.length;
      // As 4 ultimas colunas nao tem virgula: pRedIBS, pRedCBS, inicio, fim, versao.
      assertThat(campos[n - 5]).as(linha).isEqualTo(campos[n - 4]);
    }
  }

  @Test
  @DisplayName("#283: anexo de aliquota zero vence sem consultar o in natura (uma query a menos)")
  void inNaturaSoQuandoPreciso() {
    when(anexos.findVigentes(eq("04072100"), any())).thenReturn(List.of(
        linha("200014", 15, "04072100", Permissao.PERMITIDO, null)));
    classificacao("200014", "100");

    service.tratamentoPorNcm("04072100", DATA);

    verify(referencia, never()).existsByCodigoAndInNaturaTrue(anyString());
  }

  @Test
  @DisplayName("#283: lote = um a um, em uma consulta de anexos, uma de in natura e uma por codigo")
  void lote() {
    when(anexos.findVigentesEm(any(), any())).thenReturn(List.of(
        linha("200014", 15, "04072100", Permissao.PERMITIDO, null),
        linha("200038", 9, "12019000", Permissao.PERMITIDO, null),
        linha("200038", 9, "31010000", Permissao.PERMITIDO, null)));
    when(referencia.findCodigosInNatura(any())).thenReturn(List.of("12019000"));
    classificacao("200014", "100");
    classificacao("200036", "60");
    classificacao("200038", "60");

    var lote = service.tratamentosPorNcm(
        List.of("04072100", "12019000", "31010000", "99999999", "04072100", ""), DATA);

    assertThat(lote.keySet()).containsExactly("04072100", "12019000", "31010000", "99999999");
    assertThat(lote.values()).extracting(TratamentoTributarioNcm::cClassTrib)
        .containsExactly("200014", "200036", "200038", "000001");
    verify(classificacoes, times(1)).findVigente(eq("200038"), any());
    verify(anexos, never()).findVigentes(anyString(), any());
    assertThat(service.tratamentosPorNcm(List.of(), DATA)).isEmpty();
  }

  private void classificacao(String codigo, String reducao) {
    when(classificacoes.findVigente(eq(codigo), any())).thenReturn(List.of(
        ClassificacaoTributaria.builder().codigo(codigo).cst(codigo.substring(0, 3))
            .descricao("LC 214").percentualReducaoIbs(new BigDecimal(reducao))
            .percentualReducaoCbs(new BigDecimal(reducao)).dataInicio(LocalDate.of(2026, 1, 1))
            .versaoTabela("IT").build()));
  }

  private static ClassificacaoTributariaAnexoNcm linha(String cClassTrib, int anexo, String ncm,
      Permissao permissao, String condicao) {
    ClassificacaoTributariaAnexoItem item = ClassificacaoTributariaAnexoItem.builder()
        .id(anexo * 10 + permissao.ordinal()).cClassTrib(cClassTrib).anexo(anexo)
        .descricao("item").condicao(condicao).build();
    return ClassificacaoTributariaAnexoNcm.builder().item(item).ncm(ncm).permissao(permissao)
        .dataInicio(LocalDate.of(2026, 1, 1)).build();
  }

  private static String ler(String recurso) throws IOException {
    try (InputStream in = ClassificacaoTributariaServiceTest.class.getClassLoader()
        .getResourceAsStream(recurso)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
