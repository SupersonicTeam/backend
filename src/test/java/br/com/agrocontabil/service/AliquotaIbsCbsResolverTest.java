package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.config.TributosProperties;
import br.com.agrocontabil.model.AliquotaIbsUf;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.AliquotaIbsUfRepository;
import br.com.agrocontabil.service.AliquotasVigentes.OrigemAliquota;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes unitários do {@link AliquotaIbsCbsResolver} (issue Agrocontabil#86).
 *
 * <p>Cobre a resolução única da alíquota de IBS por UF (RN002) compartilhada entre débito e
 * crédito, o fallback conservador para {@link TributosProperties} e a premissa de nota importada
 * (UF aplicável = destinatário, princípio do destino).
 */
class AliquotaIbsCbsResolverTest {

  private static final BigDecimal IBS_YML = new BigDecimal("0.0010");
  private static final BigDecimal CBS_YML = new BigDecimal("0.0090");

  private AliquotaIbsUfRepository aliquotaIbsUfRepository;
  private AliquotaIbsCbsResolver resolver;

  @BeforeEach
  void setUp() {
    TributosProperties props = new TributosProperties();
    props.getIbs().setAliquota(IBS_YML);
    props.getCbs().setAliquota(CBS_YML);
    aliquotaIbsUfRepository = mock(AliquotaIbsUfRepository.class);
    resolver = new AliquotaIbsCbsResolver(aliquotaIbsUfRepository, props);
  }

  @Test
  @DisplayName("debito e credito obtem a mesma aliquota por UF para a mesma nota (simetria #86)")
  void debitoECreditoObtemMesmaAliquota() {
    when(aliquotaIbsUfRepository.findByUf("MT"))
        .thenReturn(Optional.of(aliquota("MT", "0.0030", "0.0020")));
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
    when(aliquotaIbsUfRepository.findByUf("PR"))
        .thenReturn(Optional.of(aliquota("PR", "0.0007", "0.0003")));

    AliquotasVigentes vigentes = resolver.resolver(nota(OrigemNotaFiscal.PROPRIA, "MT", "PR"));

    assertThat(vigentes.ibsTotal()).isEqualByComparingTo("0.0010");
    verify(aliquotaIbsUfRepository).findByUf("PR");
  }

  @Test
  @DisplayName("sem UF determinavel cai para a aliquota do yml dividida 50/50 (fallback conservador)")
  void fallbackYmlQuandoUfAusente() {
    NotaFiscal semEndereco = NotaFiscal.builder().origem(OrigemNotaFiscal.PROPRIA).build();

    AliquotasVigentes vigentes = resolver.resolver(semEndereco);

    assertThat(vigentes.ibsEstadual()).isEqualByComparingTo("0.0005");
    assertThat(vigentes.ibsMunicipal()).isEqualByComparingTo("0.0005");
    assertThat(vigentes.ibsTotal()).isEqualByComparingTo(IBS_YML);
    assertThat(vigentes.origemIbs()).isEqualTo(OrigemAliquota.FALLBACK_YML);
    verifyNoInteractions(aliquotaIbsUfRepository);
  }

  @Test
  @DisplayName("UF nao cadastrada na tabela cai para a aliquota do yml dividida 50/50")
  void fallbackYmlQuandoUfNaoCadastrada() {
    when(aliquotaIbsUfRepository.findByUf("ZZ")).thenReturn(Optional.empty());

    AliquotasVigentes vigentes = resolver.resolver(nota(OrigemNotaFiscal.PROPRIA, "ZZ", "ZZ"));

    assertThat(vigentes.ibsTotal()).isEqualByComparingTo(IBS_YML);
    assertThat(vigentes.origemIbs()).isEqualTo(OrigemAliquota.FALLBACK_YML);
  }

  @Test
  @DisplayName("nota IMPORTADA usa a UF do destinatario (produtor) — principio do destino")
  void notaImportadaUsaUfDoDestinatario() {
    when(aliquotaIbsUfRepository.findByUf("PR"))
        .thenReturn(Optional.of(aliquota("PR", "0.0007", "0.0003")));
    // Cadastro do produtor diz MT, mas o XML importado traz o destinatario em PR: o dado do XML
    // prevalece e nenhuma consulta e feita para MT.
    NotaFiscal importada = nota(OrigemNotaFiscal.IMPORTADA, "MT", "PR");

    AliquotasVigentes vigentes = resolver.resolver(importada);

    assertThat(vigentes.ibsTotal()).isEqualByComparingTo("0.0010");
    verify(aliquotaIbsUfRepository).findByUf("PR");
  }

  @Test
  @DisplayName("nota IMPORTADA sem endereco no XML cai para a UF do cadastro do produtor")
  void notaImportadaSemEnderecoUsaUfDoProdutor() {
    when(aliquotaIbsUfRepository.findByUf("MT"))
        .thenReturn(Optional.of(aliquota("MT", "0.0030", "0.0020")));
    NotaFiscal importada = NotaFiscal.builder()
        .origem(OrigemNotaFiscal.IMPORTADA)
        .produtor(Produtor.builder().estado("mt").build())
        .build();

    AliquotasVigentes vigentes = resolver.resolver(importada);

    assertThat(vigentes.ibsTotal()).isEqualByComparingTo("0.0050");
    verify(aliquotaIbsUfRepository).findByUf("MT");
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
