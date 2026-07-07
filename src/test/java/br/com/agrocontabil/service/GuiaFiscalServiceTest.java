package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.dto.GuiaFiscalRequest;
import br.com.agrocontabil.dto.GuiaFiscalResponse;
import br.com.agrocontabil.exception.GuiaJaPagaException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.GuiaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.TipoGuiaFiscal;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Testes unitários do {@link GuiaFiscalService} (issue #74).
 *
 * <p>Cobre a criação sem autenticação de Contador (produtorId explícito, 404 se inexistente), a
 * listagem tolerante a produtor inexistente e o pagamento com o padrão anti-IDOR real
 * ({@code AuthenticatedProdutorProvider} + {@code findByIdAndProdutorId}).
 */
@ExtendWith(MockitoExtension.class)
class GuiaFiscalServiceTest {

  @Mock private GuiaFiscalRepository guiaFiscalRepository;
  @Mock private ProdutorRepository produtorRepository;
  @Mock private AuthenticatedProdutorProvider produtorProvider;

  private GuiaFiscalService service;
  private Produtor produtor;

  private static final Clock CLOCK_FIXO = Clock.fixed(
      Instant.parse("2026-07-15T12:00:00Z"), ZoneId.of("America/Sao_Paulo"));

  @BeforeEach
  void setUp() {
    service = new GuiaFiscalService(guiaFiscalRepository, produtorRepository, produtorProvider,
        CLOCK_FIXO);
    produtor = Produtor.builder().id(UUID.randomUUID()).nomeCompleto("Fazenda Boa Vista").build();
  }

  private GuiaFiscalRequest requestValido() {
    return new GuiaFiscalRequest(
        produtor.getId(), TipoGuiaFiscal.ICMS, LocalDate.of(2026, 8, 20),
        new BigDecimal("1250.00"), "Referente a agosto/2026");
  }

  @Test
  @DisplayName("criar() persiste a guia PENDENTE para o produtorId informado no request")
  void criarPersisteGuiaPendente() {
    when(produtorRepository.findById(produtor.getId())).thenReturn(Optional.of(produtor));
    when(guiaFiscalRepository.save(any(GuiaFiscal.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    GuiaFiscalResponse response = service.criar(requestValido());

    assertThat(response.produtorId()).isEqualTo(produtor.getId());
    assertThat(response.tipoGuia()).isEqualTo("ICMS");
    assertThat(response.status()).isEqualTo("PENDENTE");
    assertThat(response.dataPagamento()).isNull();
  }

  @Test
  @DisplayName("criar() lanca 404 quando o produtorId informado nao existe")
  void criarLancaNotFoundParaProdutorInexistente() {
    UUID produtorInexistente = UUID.randomUUID();
    when(produtorRepository.findById(produtorInexistente)).thenReturn(Optional.empty());
    GuiaFiscalRequest request = new GuiaFiscalRequest(
        produtorInexistente, TipoGuiaFiscal.DARF, LocalDate.of(2026, 8, 20),
        new BigDecimal("500.00"), null);

    assertThatThrownBy(() -> service.criar(request))
        .isInstanceOf(ResourceNotFoundException.class);

    verify(guiaFiscalRepository, never()).save(any());
  }

  @Test
  @DisplayName("listar() retorna as guias do produtor mapeadas, sem expor a entidade")
  void listarRetornaGuiasDoProdutor() {
    GuiaFiscal guia = guiaPendente();
    when(guiaFiscalRepository.findByProdutorIdOrderByDataVencimentoDesc(produtor.getId()))
        .thenReturn(List.of(guia));

    List<GuiaFiscalResponse> guias = service.listar(produtor.getId());

    assertThat(guias).hasSize(1);
    assertThat(guias.get(0).id()).isEqualTo(guia.getId());
  }

  @Test
  @DisplayName("listar() retorna lista vazia quando o produtor nao tem guias (nao valida existencia)")
  void listarRetornaVazioSemValidarProdutor() {
    UUID produtorId = UUID.randomUUID();
    when(guiaFiscalRepository.findByProdutorIdOrderByDataVencimentoDesc(produtorId))
        .thenReturn(List.of());

    assertThat(service.listar(produtorId)).isEmpty();
    verify(produtorRepository, never()).findById(any());
  }

  @Test
  @DisplayName("marcarComoPaga() usa o produtor autenticado (anti-IDOR) e preenche dataPagamento")
  void marcarComoPagaAtualizaStatusEData() {
    autenticarProdutor();
    GuiaFiscal guia = guiaPendente();
    when(guiaFiscalRepository.findByIdAndProdutorId(guia.getId(), produtor.getId()))
        .thenReturn(Optional.of(guia));

    GuiaFiscalResponse response = service.marcarComoPaga(guia.getId());

    assertThat(response.status()).isEqualTo("PAGA");
    assertThat(response.dataPagamento()).isEqualTo(OffsetDateTime.now(CLOCK_FIXO));
  }

  @Test
  @DisplayName("marcarComoPaga() lanca 404 quando a guia nao pertence ao produtor autenticado")
  void marcarComoPagaLancaNotFoundParaGuiaDeOutroProdutor() {
    autenticarProdutor();
    UUID guiaId = UUID.randomUUID();
    when(guiaFiscalRepository.findByIdAndProdutorId(guiaId, produtor.getId()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.marcarComoPaga(guiaId))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  @DisplayName("marcarComoPaga() lanca 409 quando a guia ja esta paga")
  void marcarComoPagaLancaConflitoParaGuiaJaPaga() {
    autenticarProdutor();
    GuiaFiscal guiaJaPaga = guiaPendente();
    guiaJaPaga.setStatus(StatusGuiaFiscal.PAGA);
    guiaJaPaga.setDataPagamento(OffsetDateTime.now(CLOCK_FIXO).minusDays(1));
    when(guiaFiscalRepository.findByIdAndProdutorId(guiaJaPaga.getId(), produtor.getId()))
        .thenReturn(Optional.of(guiaJaPaga));

    assertThatThrownBy(() -> service.marcarComoPaga(guiaJaPaga.getId()))
        .isInstanceOf(GuiaJaPagaException.class);
  }

  private void autenticarProdutor() {
    when(produtorProvider.get()).thenReturn(produtor);
  }

  private GuiaFiscal guiaPendente() {
    return GuiaFiscal.builder()
        .id(UUID.randomUUID())
        .produtor(produtor)
        .tipoGuia(TipoGuiaFiscal.ICMS)
        .dataVencimento(LocalDate.of(2026, 8, 20))
        .valor(new BigDecimal("1250.00"))
        .status(StatusGuiaFiscal.PENDENTE)
        .createdAt(OffsetDateTime.now(CLOCK_FIXO))
        .build();
  }
}
