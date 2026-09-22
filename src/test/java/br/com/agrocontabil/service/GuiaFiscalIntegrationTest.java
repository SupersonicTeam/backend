package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.agrocontabil.dto.GuiaFiscalRequest;
import br.com.agrocontabil.dto.GuiaFiscalResponse;
import br.com.agrocontabil.dto.VencimentoFiscalResponse;
import br.com.agrocontabil.exception.GuiaJaPagaException;
import br.com.agrocontabil.exception.ResourceNotFoundException;
import br.com.agrocontabil.model.GuiaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusGuiaFiscal;
import br.com.agrocontabil.model.TipoGuiaFiscal;
import br.com.agrocontabil.repository.GuiaFiscalRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.support.TestDatabaseCleaner;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teste de integração das guias fiscais e do calendário fiscal (issue #74) contra H2 (profile
 * {@code test}).
 *
 * <p>Exercita a pilha real ({@link GuiaFiscalService} + {@link CalendarioFiscalService} +
 * {@link GuiaFiscalRepository}). Confirma o fluxo completo: criação sem autenticação de
 * Contador (produtorId explícito), listagem, pagamento com anti-IDOR real e o reflexo no
 * calendário fiscal do produtor.
 */
@SpringBootTest
@Import(TestDatabaseCleaner.class)
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-nota-fiscal-test.sql",
    executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@Transactional
class GuiaFiscalIntegrationTest {

  @Autowired private GuiaFiscalService guiaFiscalService;
  @Autowired private CalendarioFiscalService calendarioFiscalService;
  @Autowired private GuiaFiscalRepository guiaFiscalRepository;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private TestDatabaseCleaner cleaner;

  private Produtor produtor;
  private Produtor outroProdutor;

  @BeforeEach
  void setUp() {
    cleaner.limparTudo();

    produtor = produtorRepository.save(
        Produtor.builder().nomeCompleto("Fazenda Boa Vista").cpfCnpj("98765432100")
            .estado("MT").build());
    outroProdutor = produtorRepository.save(
        Produtor.builder().nomeCompleto("Sitio Vizinho").cpfCnpj("11122233344")
            .estado("PR").build());
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private void autenticarComo(Produtor p) {
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(p, null, List.of()));
  }

  @Test
  @DisplayName("Fluxo completo: cria guia, aparece na listagem e no calendario, produtor paga")
  void fluxoCompletoCriarListarPagar() {
    GuiaFiscalRequest request = new GuiaFiscalRequest(
        produtor.getId(), TipoGuiaFiscal.ICMS, LocalDate.of(2026, 8, 20),
        new BigDecimal("1250.00"), "Referente a agosto/2026");

    GuiaFiscalResponse criada = guiaFiscalService.criar(request);
    assertThat(criada.status()).isEqualTo("PENDENTE");

    List<GuiaFiscalResponse> guias = guiaFiscalService.listar(produtor.getId());
    assertThat(guias).hasSize(1);

    // Calendario e Dashboard/Apuracao: tela do proprio produtor, exige autenticacao (anti-IDOR).
    autenticarComo(produtor);
    List<VencimentoFiscalResponse> vencimentos =
        calendarioFiscalService.listarVencimentos(produtor.getId());
    assertThat(vencimentos).hasSize(1);
    assertThat(vencimentos.get(0).status()).isEqualTo("pendente");

    GuiaFiscalResponse paga = guiaFiscalService.marcarComoPaga(criada.id());
    assertThat(paga.status()).isEqualTo("PAGA");
    assertThat(paga.dataPagamento()).isNotNull();

    assertThat(calendarioFiscalService.listarVencimentos(produtor.getId()).get(0).status())
        .isEqualTo("pago");
  }

  @Test
  @DisplayName("marcarComoPaga() respeita o anti-IDOR real: outro produtor autenticado nao paga a guia")
  void marcarComoPagaRespeitaAntiIdor() {
    GuiaFiscal guia = guiaFiscalRepository.save(GuiaFiscal.builder()
        .produtor(produtor)
        .tipoGuia(TipoGuiaFiscal.FUNRURAL)
        .dataVencimento(LocalDate.of(2026, 8, 20))
        .valor(new BigDecimal("300.00"))
        .status(StatusGuiaFiscal.PENDENTE)
        .build());

    autenticarComo(outroProdutor);

    assertThatThrownBy(() -> guiaFiscalService.marcarComoPaga(guia.getId()))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  @DisplayName("listarVencimentos() respeita o anti-IDOR real: outro produtor autenticado nao "
      + "consulta o calendario de outrem (PR #75, revisao Copilot)")
  void listarVencimentosRespeitaAntiIdor() {
    guiaFiscalRepository.save(GuiaFiscal.builder()
        .produtor(produtor)
        .tipoGuia(TipoGuiaFiscal.ICMS)
        .dataVencimento(LocalDate.of(2026, 8, 20))
        .valor(new BigDecimal("300.00"))
        .status(StatusGuiaFiscal.PENDENTE)
        .build());

    autenticarComo(outroProdutor);

    assertThatThrownBy(() -> calendarioFiscalService.listarVencimentos(produtor.getId()))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  @DisplayName("marcarComoPaga() lanca 409 ao tentar pagar guia ja paga")
  void marcarComoPagaLancaConflitoParaGuiaJaPaga() {
    GuiaFiscal guia = guiaFiscalRepository.save(GuiaFiscal.builder()
        .produtor(produtor)
        .tipoGuia(TipoGuiaFiscal.GPS)
        .dataVencimento(LocalDate.of(2026, 8, 20))
        .valor(new BigDecimal("300.00"))
        .status(StatusGuiaFiscal.PAGA)
        .build());

    autenticarComo(produtor);

    assertThatThrownBy(() -> guiaFiscalService.marcarComoPaga(guia.getId()))
        .isInstanceOf(GuiaJaPagaException.class);
  }

  @Test
  @DisplayName("criar() lanca 404 real quando o produtorId nao existe no banco")
  void criarLancaNotFoundParaProdutorInexistente() {
    GuiaFiscalRequest request = new GuiaFiscalRequest(
        UUID.randomUUID(), TipoGuiaFiscal.OUTRO, LocalDate.of(2026, 8, 20),
        new BigDecimal("50.00"), null);

    assertThatThrownBy(() -> guiaFiscalService.criar(request))
        .isInstanceOf(ResourceNotFoundException.class);
  }
}
