package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.dto.GuiaFiscalRequest;
import br.com.agrocontabil.dto.GuiaFiscalResponse;
import br.com.agrocontabil.dto.NovaGuiaFiscalRequest;
import br.com.agrocontabil.exception.GuiaJaPagaException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.GuiaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.TipoGuiaFiscal;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.security.ProdutorEmContextoResolver;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Testes unitários do {@link GuiaFiscalService} (issue #74).
 *
 * <p>Cobre a criação e a listagem escopadas pelo {@code ProdutorEmContextoResolver} (produtorId
 * explícito, 404 quando inacessível ou inexistente) e o pagamento com o padrão anti-IDOR real
 * ({@code AuthenticatedProdutorProvider} + {@code findByIdAndProdutorId}).
 */
@ExtendWith(MockitoExtension.class)
class GuiaFiscalServiceTest {

  @Mock private GuiaFiscalRepository guiaFiscalRepository;
  @Mock private ProdutorEmContextoResolver produtorEmContexto;
  @Mock private AuthenticatedProdutorProvider produtorProvider;

  private GuiaFiscalService service;
  private Produtor produtor;

  private static final Clock CLOCK_FIXO = Clock.fixed(
      Instant.parse("2026-07-15T12:00:00Z"), ZoneId.of("America/Sao_Paulo"));

  @BeforeEach
  void setUp() {
    service = new GuiaFiscalService(guiaFiscalRepository, produtorEmContexto, produtorProvider,
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
    when(produtorEmContexto.resolver(produtor.getId())).thenReturn(produtor);
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
    when(produtorEmContexto.resolver(produtor.getId()))
        .thenThrow(new ResourceNotFoundException("Produtor", produtor.getId()));

    assertThatThrownBy(() -> service.criar(requestValido()))
        .isInstanceOf(ResourceNotFoundException.class);

    verify(guiaFiscalRepository, never()).save(any());
  }

  @Test
  @DisplayName("criar() lanca 404 para produtorId de outro produtor e nao grava nada (#94)")
  void criarLancaNotFoundParaProdutorDeOutroDono() {
    UUID outroProdutorId = UUID.randomUUID();
    GuiaFiscalRequest requestDeOutroDono = new GuiaFiscalRequest(
        outroProdutorId, TipoGuiaFiscal.DARF, LocalDate.of(2026, 8, 20),
        new BigDecimal("500.00"), null);
    when(produtorEmContexto.resolver(outroProdutorId))
        .thenThrow(new ResourceNotFoundException("Produtor", outroProdutorId));

    assertThatThrownBy(() -> service.criar(requestDeOutroDono))
        .isInstanceOf(ResourceNotFoundException.class);

    // O resolver decide antes de qualquer escrita: nada e gravado para o produtor alheio.
    verify(guiaFiscalRepository, never()).save(any());
  }

  @Test
  @DisplayName("listar() lanca 404 para produtorId de outro produtor, sem consultar as guias (#94)")
  void listarLancaNotFoundParaProdutorDeOutroDono() {
    UUID outroProdutorId = UUID.randomUUID();
    org.mockito.Mockito.doThrow(new ResourceNotFoundException("Produtor", outroProdutorId))
        .when(produtorEmContexto).exigirAcesso(outroProdutorId);

    assertThatThrownBy(() -> service.listar(outroProdutorId, 0, 100))
        .isInstanceOf(ResourceNotFoundException.class);

    verify(guiaFiscalRepository, never()).findByProdutorId(any(), any());
  }

  @Test
  @DisplayName("listar() retorna as guias do produtor mapeadas, sem expor a entidade")
  void listarRetornaGuiasDoProdutor() {
    GuiaFiscal guia = guiaPendente();
    when(guiaFiscalRepository.findByProdutorId(eq(produtor.getId()), any()))
        .thenReturn(new PageImpl<>(List.of(guia), PageRequest.of(0, 100), 1));

    Page<GuiaFiscalResponse> guias = service.listar(produtor.getId(), 0, 100);

    assertThat(guias.getContent()).hasSize(1);
    assertThat(guias.getContent().get(0).id()).isEqualTo(guia.getId());
    assertThat(guias.getTotalElements()).isEqualTo(1);
  }

  @Test
  @DisplayName("listar() pagina com a pagina e o tamanho pedidos e ordem fixa (issue #103)")
  void listarPaginaComOrdemFixa() {
    when(guiaFiscalRepository.findByProdutorId(eq(produtor.getId()), any()))
        .thenReturn(Page.empty());

    service.listar(produtor.getId(), 2, 50);

    ArgumentCaptor<Pageable> pagina = ArgumentCaptor.forClass(Pageable.class);
    verify(guiaFiscalRepository).findByProdutorId(eq(produtor.getId()), pagina.capture());
    assertThat(pagina.getValue().getPageNumber()).isEqualTo(2);
    assertThat(pagina.getValue().getPageSize()).isEqualTo(50);
    // Vencimento desc com desempate por id: guias do mesmo dia nao pulam nem repetem entre paginas.
    assertThat(pagina.getValue().getSort()).isEqualTo(
        Sort.by(Sort.Direction.DESC, "dataVencimento").and(Sort.by(Sort.Direction.DESC, "id")));
  }

  @Test
  @DisplayName("listar() retorna lista vazia quando o proprio produtor nao tem guias")
  void listarRetornaVazioQuandoNaoHaGuias() {
    when(guiaFiscalRepository.findByProdutorId(eq(produtor.getId()), any()))
        .thenReturn(Page.empty());

    assertThat(service.listar(produtor.getId(), 0, 100)).isEmpty();
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

  // -------------------------------------------------------------------------
  // Rotas separadas (issue #273)
  // -------------------------------------------------------------------------

  private NovaGuiaFiscalRequest novaRequest() {
    return new NovaGuiaFiscalRequest(
        TipoGuiaFiscal.ICMS, LocalDate.of(2026, 8, 20), new BigDecimal("1250.00"), null);
  }

  @Test
  @DisplayName("criarDoProdutorAutenticado() grava para o produtor da identidade, sem resolver id")
  void criarDoProdutorAutenticadoUsaAIdentidade() {
    when(produtorProvider.get()).thenReturn(produtor);
    when(guiaFiscalRepository.save(any(GuiaFiscal.class))).thenAnswer(inv -> inv.getArgument(0));

    GuiaFiscalResponse response = service.criarDoProdutorAutenticado(novaRequest());

    assertThat(response.produtorId()).isEqualTo(produtor.getId());
    assertThat(response.status()).isEqualTo("PENDENTE");
    verify(produtorEmContexto, never()).resolver(any());
  }

  @Test
  @DisplayName("criarDaCarteira() exige o contador com acesso antes de gravar; negado = nada gravado")
  void criarDaCarteiraExigeContadorComAcesso() {
    UUID alvo = UUID.randomUUID();
    org.mockito.Mockito.doThrow(new ResourceNotFoundException("Produtor", alvo))
        .when(produtorEmContexto).exigirContadorComAcesso(alvo);

    assertThatThrownBy(() -> service.criarDaCarteira(alvo, novaRequest()))
        .isInstanceOf(ResourceNotFoundException.class);

    verify(guiaFiscalRepository, never()).save(any());
  }

  @Test
  @DisplayName("criarDaCarteira() grava para o produtor do path")
  void criarDaCarteiraGravaParaOProdutorDoPath() {
    when(produtorEmContexto.resolver(produtor.getId())).thenReturn(produtor);
    when(guiaFiscalRepository.save(any(GuiaFiscal.class))).thenAnswer(inv -> inv.getArgument(0));

    GuiaFiscalResponse response = service.criarDaCarteira(produtor.getId(), novaRequest());

    assertThat(response.produtorId()).isEqualTo(produtor.getId());
    verify(produtorEmContexto).exigirContadorComAcesso(produtor.getId());
  }

  @Test
  @DisplayName("listarDaCarteira() exige o contador com acesso antes de consultar")
  void listarDaCarteiraExigeContadorComAcesso() {
    UUID alvo = UUID.randomUUID();
    org.mockito.Mockito.doThrow(new ResourceNotFoundException("Produtor", alvo))
        .when(produtorEmContexto).exigirContadorComAcesso(alvo);

    assertThatThrownBy(() -> service.listarDaCarteira(alvo, 0, 100))
        .isInstanceOf(ResourceNotFoundException.class);

    verify(guiaFiscalRepository, never()).findByProdutorId(any(), any());
  }

  @Test
  @DisplayName("listarDoProdutorAutenticado() consulta só o produtor da identidade")
  void listarDoProdutorAutenticadoUsaAIdentidade() {
    when(produtorProvider.get()).thenReturn(produtor);
    when(guiaFiscalRepository.findByProdutorId(eq(produtor.getId()), any(Pageable.class)))
        .thenReturn(org.springframework.data.domain.Page.empty());

    assertThat(service.listarDoProdutorAutenticado(0, 100).getTotalElements()).isZero();

    verify(guiaFiscalRepository).findByProdutorId(eq(produtor.getId()), any(Pageable.class));
  }
}
