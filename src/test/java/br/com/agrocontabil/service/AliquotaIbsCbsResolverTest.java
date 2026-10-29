package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.config.TributosProperties;
import br.com.agrocontabil.model.AliquotaCbs;
import br.com.agrocontabil.model.AliquotaIbsUf;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.AliquotaCbsRepository;
import br.com.agrocontabil.repository.AliquotaIbsUfRepository;
import br.com.agrocontabil.service.AliquotasVigentes.OrigemAliquota;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes unitários do {@link AliquotaIbsCbsResolver} (issue Agrocontabil#86).
 *
 * <p>Cobre a resolução única da alíquota de IBS por UF (RN002) compartilhada entre débito e
 * crédito, o fallback conservador para {@link TributosProperties}, a premissa de nota importada
 * (UF aplicável = destinatário, princípio do destino) e a seleção da vigência pela data de emissão
 * em {@code America/Sao_Paulo} (issue #85).
 */
class AliquotaIbsCbsResolverTest {

  private static final BigDecimal IBS_YML = new BigDecimal("0.0010");
  private static final BigDecimal CBS_YML = new BigDecimal("0.0090");

  private static final ZoneId BRT = ZoneId.of("America/Sao_Paulo");

  private AliquotaIbsUfRepository aliquotaIbsUfRepository;
  private AliquotaCbsRepository aliquotaCbsRepository;
  private AliquotaIbsCbsResolver resolver;

  @BeforeEach
  void setUp() {
    TributosProperties props = new TributosProperties();
    props.getIbs().setAliquota(IBS_YML);
    props.getCbs().setAliquota(CBS_YML);
    aliquotaIbsUfRepository = mock(AliquotaIbsUfRepository.class);
    aliquotaCbsRepository = mock(AliquotaCbsRepository.class);
    resolver = new AliquotaIbsCbsResolver(aliquotaIbsUfRepository, aliquotaCbsRepository, props);
  }

  @Test
  @DisplayName("debito e credito obtem a mesma aliquota por UF para a mesma nota (simetria #86)")
  void debitoECreditoObtemMesmaAliquota() {
    when(aliquotaIbsUfRepository.findVigente(eq("MT"), any(LocalDate.class)))
        .thenReturn(List.of(aliquota("MT", "0.0030", "0.0020")));
    NotaFiscal notaPropria = nota(OrigemNotaFiscal.PROPRIA, "MT", "MT");
    NotaFiscal notaImportada = nota(OrigemNotaFiscal.IMPORTADA, "MT", "MT");

    AliquotasVigentes debito = resolver.resolver(notaPropria);
    AliquotasVigentes credito = resolver.resolver(notaImportada);

    assertThat(debito.ibsTotal()).isEqualByComparingTo("0.0050");
    assertThat(credito.ibsTotal()).isEqualByComparingTo("0.0050");
    assertThat(debito.ibsEstadual()).isEqualByComparingTo("0.0030");
    assertThat(debito.ibsMunicipal()).isEqualByComparingTo("0.0020");
    assertThat(debito.origemIbs()).isEqualTo(OrigemAliquota.TABELA_UF);
    assertThat(credito.origemIbs()).isEqualTo(OrigemAliquota.TABELA_UF);
    assertThat(debito.cbs()).isEqualByComparingTo(CBS_YML);
  }

  @Test
  @DisplayName("operacao interestadual usa a UF de destino (RN002)")
  void interestadualUsaUfDeDestino() {
    when(aliquotaIbsUfRepository.findVigente(eq("PR"), any(LocalDate.class)))
        .thenReturn(List.of(aliquota("PR", "0.0007", "0.0003")));

    AliquotasVigentes vigentes = resolver.resolver(nota(OrigemNotaFiscal.PROPRIA, "MT", "PR"));

    assertThat(vigentes.ibsTotal()).isEqualByComparingTo("0.0010");
    verify(aliquotaIbsUfRepository).findVigente(eq("PR"), any(LocalDate.class));
  }

  @Test
  @DisplayName("sem UF determinavel em 2026 cai para o yml todo estadual (EC 132; NT 2026.010) — #277")
  void fallbackYmlEm2026TodoEstadual() {
    NotaFiscal semEndereco = NotaFiscal.builder().origem(OrigemNotaFiscal.PROPRIA)
        .dataEmissao(OffsetDateTime.of(2026, 12, 31, 23, 0, 0, 0, ZoneOffset.ofHours(-3)))
        .build();

    AliquotasVigentes vigentes = resolver.resolver(semEndereco);

    assertThat(vigentes.ibsEstadual()).isEqualByComparingTo(IBS_YML);
    assertThat(vigentes.ibsMunicipal()).isEqualByComparingTo("0");
    assertThat(vigentes.ibsTotal()).isEqualByComparingTo(IBS_YML);
    assertThat(vigentes.origemIbs()).isEqualTo(OrigemAliquota.FALLBACK_YML);
    verifyNoInteractions(aliquotaIbsUfRepository);
  }

  @Test
  @DisplayName("sem UF determinavel a partir de 2027 divide o yml 50/50 (LC 214, art. 344) — #277")
  void fallbackYmlDesde2027Dividido() {
    NotaFiscal semEndereco = NotaFiscal.builder().origem(OrigemNotaFiscal.PROPRIA)
        .dataEmissao(OffsetDateTime.of(2027, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(-3)))
        .build();

    AliquotasVigentes vigentes = resolver.resolver(semEndereco);

    assertThat(vigentes.ibsEstadual()).isEqualByComparingTo("0.0005");
    assertThat(vigentes.ibsMunicipal()).isEqualByComparingTo("0.0005");
    assertThat(vigentes.ibsTotal()).isEqualByComparingTo(IBS_YML);
    assertThat(vigentes.origemIbs()).isEqualTo(OrigemAliquota.FALLBACK_YML);
    verifyNoInteractions(aliquotaIbsUfRepository);
  }

  @Test
  @DisplayName("fallback usa a data de Brasilia: 01/01/2027 02h UTC ainda e 2026 (#277)")
  void fallbackUsaDataDeBrasilia() {
    NotaFiscal semEndereco = NotaFiscal.builder().origem(OrigemNotaFiscal.PROPRIA)
        .dataEmissao(OffsetDateTime.of(2027, 1, 1, 2, 0, 0, 0, ZoneOffset.UTC))
        .build();

    assertThat(resolver.resolver(semEndereco).ibsMunicipal()).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("UF nao cadastrada na tabela cai para a aliquota do yml dividida 50/50")
  void fallbackYmlQuandoUfNaoCadastrada() {
    when(aliquotaIbsUfRepository.findVigente(eq("ZZ"), any(LocalDate.class))).thenReturn(List.of());

    AliquotasVigentes vigentes = resolver.resolver(nota(OrigemNotaFiscal.PROPRIA, "ZZ", "ZZ"));

    assertThat(vigentes.ibsTotal()).isEqualByComparingTo(IBS_YML);
    assertThat(vigentes.origemIbs()).isEqualTo(OrigemAliquota.FALLBACK_YML);
  }

  @Test
  @DisplayName("nota IMPORTADA usa a UF do destinatario (produtor) — principio do destino")
  void notaImportadaUsaUfDoDestinatario() {
    when(aliquotaIbsUfRepository.findVigente(eq("PR"), any(LocalDate.class)))
        .thenReturn(List.of(aliquota("PR", "0.0007", "0.0003")));
    // Cadastro do produtor diz MT, mas o XML importado traz o destinatario em PR: o dado do XML
    // prevalece e nenhuma consulta e feita para MT.
    NotaFiscal importada = nota(OrigemNotaFiscal.IMPORTADA, "MT", "PR");

    AliquotasVigentes vigentes = resolver.resolver(importada);

    assertThat(vigentes.ibsTotal()).isEqualByComparingTo("0.0010");
    verify(aliquotaIbsUfRepository).findVigente(eq("PR"), any(LocalDate.class));
  }

  @Test
  @DisplayName("nota IMPORTADA sem endereco no XML cai para a UF do cadastro do produtor")
  void notaImportadaSemEnderecoUsaUfDoProdutor() {
    when(aliquotaIbsUfRepository.findVigente(eq("MT"), any(LocalDate.class)))
        .thenReturn(List.of(aliquota("MT", "0.0030", "0.0020")));
    NotaFiscal importada = NotaFiscal.builder()
        .origem(OrigemNotaFiscal.IMPORTADA)
        .produtor(Produtor.builder().estado("mt").build())
        .build();

    AliquotasVigentes vigentes = resolver.resolver(importada);

    assertThat(vigentes.ibsTotal()).isEqualByComparingTo("0.0050");
    verify(aliquotaIbsUfRepository).findVigente(eq("MT"), any(LocalDate.class));
  }

  // ---------------------------------------------------------------------------------------
  // Vigencia temporal (issue #85)
  // ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("seleciona a vigencia de IBS pela data de emissao da nota (#85)")
  void selecionaVigenciaPelaDataDeEmissao() {
    AliquotaIbsUf vigencia2026 = aliquota("MT", "0.0005", "0.0005");
    AliquotaIbsUf vigencia2027 = aliquota("MT", "0.0010", "0.0010");
    when(aliquotaIbsUfRepository.findVigente("MT", LocalDate.of(2027, 3, 15)))
        .thenReturn(List.of(vigencia2027));
    when(aliquotaIbsUfRepository.findVigente("MT", LocalDate.of(2026, 6, 10)))
        .thenReturn(List.of(vigencia2026));

    NotaFiscal em2027 = nota(OrigemNotaFiscal.PROPRIA, "MT", "MT");
    em2027.setDataEmissao(OffsetDateTime.of(2027, 3, 15, 10, 0, 0, 0, ZoneOffset.ofHours(-3)));
    NotaFiscal em2026 = nota(OrigemNotaFiscal.PROPRIA, "MT", "MT");
    em2026.setDataEmissao(OffsetDateTime.of(2026, 6, 10, 10, 0, 0, 0, ZoneOffset.ofHours(-3)));

    assertThat(resolver.resolver(em2027).ibsTotal()).isEqualByComparingTo("0.0020");
    assertThat(resolver.resolver(em2026).ibsTotal()).isEqualByComparingTo("0.0010");
  }

  @Test
  @DisplayName("converte a data de emissao para America/Sao_Paulo antes de consultar a vigencia")
  void converteDataEmissaoParaBrt() {
    // 2027-01-01T01:00Z ainda e 2026-12-31 (22h) em BRT.
    when(aliquotaIbsUfRepository.findVigente("MT", LocalDate.of(2026, 12, 31)))
        .thenReturn(List.of(aliquota("MT", "0.0005", "0.0005")));
    NotaFiscal nota = nota(OrigemNotaFiscal.PROPRIA, "MT", "MT");
    nota.setDataEmissao(OffsetDateTime.of(2027, 1, 1, 1, 0, 0, 0, ZoneOffset.UTC));

    AliquotasVigentes vigentes = resolver.resolver(nota);

    assertThat(vigentes.origemIbs()).isEqualTo(OrigemAliquota.TABELA_UF);
    verify(aliquotaIbsUfRepository).findVigente("MT", LocalDate.of(2026, 12, 31));
  }

  @Test
  @DisplayName("CBS vem da tabela de vigencia quando ha linha; senao cai para o yml")
  void cbsVigenteEFallback() {
    LocalDate data = LocalDate.of(2026, 6, 10);
    when(aliquotaCbsRepository.findVigente(data)).thenReturn(List.of(
        AliquotaCbs.builder().dataInicio(LocalDate.of(2026, 1, 1))
            .aliquota(new BigDecimal("0.0120")).build()));
    NotaFiscal comVigencia = nota(OrigemNotaFiscal.PROPRIA, "MT", "MT");
    comVigencia.setDataEmissao(data.atTime(10, 0).atZone(BRT).toOffsetDateTime());

    AliquotasVigentes vigentes = resolver.resolver(comVigencia);
    assertThat(vigentes.cbs()).isEqualByComparingTo("0.0120");
    assertThat(vigentes.origemCbs()).isEqualTo(OrigemAliquota.TABELA_VIGENCIA);

    LocalDate semLinha = LocalDate.of(2034, 1, 1);
    when(aliquotaCbsRepository.findVigente(semLinha)).thenReturn(List.of());
    NotaFiscal semVigencia = nota(OrigemNotaFiscal.PROPRIA, "MT", "MT");
    semVigencia.setDataEmissao(semLinha.atTime(10, 0).atZone(BRT).toOffsetDateTime());

    AliquotasVigentes fallback = resolver.resolver(semVigencia);
    assertThat(fallback.cbs()).isEqualByComparingTo(CBS_YML);
    assertThat(fallback.origemCbs()).isEqualTo(OrigemAliquota.FALLBACK_YML);
  }

  @Test
  @DisplayName("data de emissao fora de qualquer vigencia de IBS cai no fallback do yml, sem lancar")
  void dataForaDeVigenciaCaiNoFallback() {
    LocalDate antesDaReforma = LocalDate.of(2025, 12, 31);
    when(aliquotaIbsUfRepository.findVigente("MT", antesDaReforma)).thenReturn(List.of());
    NotaFiscal nota = nota(OrigemNotaFiscal.PROPRIA, "MT", "MT");
    nota.setDataEmissao(antesDaReforma.atTime(10, 0).atZone(BRT).toOffsetDateTime());

    AliquotasVigentes vigentes = resolver.resolver(nota);

    assertThat(vigentes.ibsTotal()).isEqualByComparingTo(IBS_YML);
    assertThat(vigentes.origemIbs()).isEqualTo(OrigemAliquota.FALLBACK_YML);
  }

  @Test
  @DisplayName("sem dataEmissao (fixture) usa a data de hoje em BRT")
  void semDataEmissaoUsaHojeBrt() {
    LocalDate hoje = LocalDate.now(BRT);
    when(aliquotaIbsUfRepository.findVigente("MT", hoje))
        .thenReturn(List.of(aliquota("MT", "0.0030", "0.0020")));
    NotaFiscal semData = nota(OrigemNotaFiscal.PROPRIA, "MT", "MT");

    AliquotasVigentes vigentes = resolver.resolver(semData);

    assertThat(vigentes.ibsTotal()).isEqualByComparingTo("0.0050");
    verify(aliquotaIbsUfRepository).findVigente("MT", hoje);
  }

  // ---------------------------------------------------------------------------------------

  private static AliquotaIbsUf aliquota(String uf, String estadual, String municipal) {
    return AliquotaIbsUf.builder()
        .uf(uf)
        .aliquotaEstadual(new BigDecimal(estadual))
        .aliquotaMunicipal(new BigDecimal(municipal))
        .build();
  }

  private static NotaFiscal nota(OrigemNotaFiscal origem, String ufEmitente, String ufDestino) {
    return NotaFiscal.builder()
        .origem(origem)
        .produtor(Produtor.builder().estado(ufEmitente).build())
        .destinatarioEndereco(EnderecoDestinatario.builder().uf(ufDestino).build())
        .build();
  }
}
