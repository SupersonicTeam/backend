package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.dto.VencimentoFiscalResponse;
import br.com.agrocontabil.exception.ContatoAusenteException;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.UrgenciaVencimento;
import br.com.agrocontabil.repository.NotificacaoRepository;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.repository.VinculoCarteiraRepository;
import br.com.agrocontabil.security.ProdutorEmContextoResolver;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

@DisplayName("NotificacaoService — faixas, chave e recusas (#182)")
class NotificacaoServiceTest {

  private static final LocalDate HOJE = LocalDate.of(2026, 3, 3);

  @Test
  @DisplayName("URGENTE = hoje ou amanha; PROXIMO = 2 a 7 dias; ATRASADO = data passada")
  void faixas() {
    assertThat(NotificacaoService.urgencia(HOJE, HOJE)).isEqualTo(UrgenciaVencimento.URGENTE);
    assertThat(NotificacaoService.urgencia(HOJE.plusDays(1), HOJE))
        .isEqualTo(UrgenciaVencimento.URGENTE);
    assertThat(NotificacaoService.urgencia(HOJE.plusDays(2), HOJE))
        .isEqualTo(UrgenciaVencimento.PROXIMO);
    assertThat(NotificacaoService.urgencia(HOJE.plusDays(7), HOJE))
        .isEqualTo(UrgenciaVencimento.PROXIMO);
    assertThat(NotificacaoService.urgencia(HOJE.minusDays(1), HOJE))
        .isEqualTo(UrgenciaVencimento.ATRASADO);
  }

  @Test
  @DisplayName("chave do aviso: tipo, item e data; cabe na coluna de 120")
  void chave() {
    String obrigacao = UUID.randomUUID() + ":" + HOJE;
    String chave = NotificacaoService.chave(new VencimentoFiscalResponse(obrigacao, "DCTFWeb",
        null, HOJE, "obrigacao", null, null, "DCTFWeb", null, null));

    assertThat(chave).isEqualTo("obrigacao:" + obrigacao + ":" + HOJE).hasSizeLessThan(120);
  }

  @Test
  @DisplayName("sem WhatsApp: 422 antes de olhar o calendario e de gravar")
  void contatoAntesDoCalendario() {
    AuthenticatedProdutorProvider provider = mock(AuthenticatedProdutorProvider.class);
    CalendarioFiscalService calendario = mock(CalendarioFiscalService.class);
    NotificacaoRepository repository = mock(NotificacaoRepository.class);
    VinculoCarteiraRepository vinculos = mock(VinculoCarteiraRepository.class);
    Produtor semTelefone = new Produtor();
    semTelefone.setId(UUID.randomUUID());
    when(provider.get()).thenReturn(semTelefone);
    NotificacaoService service = new NotificacaoService(provider,
        mock(ProdutorEmContextoResolver.class), mock(ProdutorRepository.class), vinculos,
        calendario, repository, (n, d) -> { }, mock(PlatformTransactionManager.class),
        Clock.fixed(HOJE.atStartOfDay(ZoneId.of("America/Sao_Paulo")).toInstant(),
            ZoneId.of("America/Sao_Paulo")), 10);

    assertThatThrownBy(service::proximasDoProdutor).isInstanceOf(ContatoAusenteException.class);
    verifyNoInteractions(calendario, repository);
  }

  @Test
  @DisplayName("teto por hora abaixo de 1 impede a subida")
  void tetoInvalido() {
    assertThatThrownBy(() -> new NotificacaoService(null, null, null, null, null, null, null,
        mock(PlatformTransactionManager.class), Clock.systemUTC(), 0))
        .isInstanceOf(IllegalStateException.class);
  }
}
