package br.com.agrocontabil.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.model.Escritorio;
import br.com.agrocontabil.model.MetodoVerificacaoCnpj;
import br.com.agrocontabil.model.TipoEscritorio;
import br.com.agrocontabil.repository.EscritorioRepository;
import br.com.agrocontabil.service.VerificacaoCnpjService.Motivo;
import br.com.agrocontabil.service.VerificacaoCnpjService.ResultadoVerificacao;
import br.com.agrocontabil.service.VerificacaoCnpjService.VerificacaoRecusadaException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("Verificacao do CNPJ do escritorio (#520)")
class VerificacaoCnpjServiceTest {

  private static final Instant AGORA = Instant.parse("2026-11-11T15:00:00Z");
  private static final Clock CLOCK = Clock.fixed(AGORA, ZoneId.of("America/Sao_Paulo"));
  private static final String CNPJ = "11444777000161";

  @Mock private EscritorioRepository escritorioRepository;
  @Mock private PlatformTransactionManager transactionManager;

  private VerificacaoCnpjService service;
  private Escritorio escritorio;

  @BeforeEach
  void setUp() {
    service = new VerificacaoCnpjService(escritorioRepository, CLOCK, transactionManager);
    escritorio = Escritorio.builder().id(UUID.randomUUID()).nome("Contabilidade Campo Verde")
        .tipo(TipoEscritorio.EMPRESARIAL).cnpj(CNPJ).build();
    // O teste do texto dos motivos nao abre transacao.
    lenient().when(transactionManager.getTransaction(any()))
        .thenReturn(new SimpleTransactionStatus());
  }

  private void escritorioSobLock() {
    when(escritorioRepository.findByIdForUpdate(escritorio.getId()))
        .thenReturn(Optional.of(escritorio));
  }

  private Motivo motivo(Runnable chamada) {
    try {
      chamada.run();
    } catch (VerificacaoRecusadaException e) {
      return e.motivo();
    }
    throw new AssertionError("a verificacao devia ser recusada");
  }

  @Test
  @DisplayName("verifica o CNPJ conferido (com mascara), sob lock, com o metodo e o instante")
  void verifica() {
    escritorioSobLock();

    ResultadoVerificacao resultado = service.verificar(escritorio.getId(), "11.444.777/0001-61",
        MetodoVerificacaoCnpj.MANUAL);

    assertThat(escritorio.getCnpjVerificadoEm()).isEqualTo(OffsetDateTime.now(CLOCK));
    assertThat(escritorio.getCnpjVerificacaoMetodo()).isEqualTo(MetodoVerificacaoCnpj.MANUAL);
    verify(escritorioRepository).saveAndFlush(escritorio);
    assertThat(resultado.antes()).isEqualTo("NAO_VERIFICADO");
    assertThat(resultado.depois()).isEqualTo("MANUAL");
    assertThat(resultado.mudou()).isTrue();
    // A auditoria leva o escritorio e o metodo, nunca o CNPJ.
    assertThat(resultado.recursoAuditado()).isEqualTo(escritorio.getId());
    assertThat(resultado.toString()).doesNotContain(CNPJ);
  }

  @Test
  @DisplayName("ja verificado: nada muda, mesmo com outro metodo")
  void jaVerificado() {
    OffsetDateTime antes = OffsetDateTime.parse("2026-11-01T10:00:00-03:00");
    escritorio.verificarCnpj(MetodoVerificacaoCnpj.DEMONSTRACAO, antes);
    escritorioSobLock();

    ResultadoVerificacao resultado = service.verificar(escritorio.getId(), CNPJ,
        MetodoVerificacaoCnpj.MANUAL);

    assertThat(resultado.mudou()).isFalse();
    assertThat(resultado.depois()).isEqualTo("DEMONSTRACAO");
    assertThat(escritorio.getCnpjVerificadoEm()).isEqualTo(antes);
    verify(escritorioRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("recusa: escritorio inexistente, sem CNPJ e CNPJ diferente do conferido")
  void recusas() {
    UUID inexistente = UUID.randomUUID();
    when(escritorioRepository.findByIdForUpdate(inexistente)).thenReturn(Optional.empty());
    assertThat(motivo(() -> service.verificar(inexistente, CNPJ, MetodoVerificacaoCnpj.MANUAL)))
        .isEqualTo(Motivo.ESCRITORIO_INEXISTENTE);

    escritorioSobLock();
    // O administrador trocou o CNPJ depois da conferencia: o gravado nao e o conferido.
    assertThat(motivo(() -> service.verificar(escritorio.getId(), "11222333000181",
        MetodoVerificacaoCnpj.MANUAL))).isEqualTo(Motivo.CNPJ_DIFERENTE_DO_CONFERIDO);
    assertThat(motivo(() -> service.verificar(escritorio.getId(), null,
        MetodoVerificacaoCnpj.MANUAL))).isEqualTo(Motivo.CNPJ_DIFERENTE_DO_CONFERIDO);

    escritorio.setCnpj(null);
    assertThat(motivo(() -> service.verificar(escritorio.getId(), CNPJ,
        MetodoVerificacaoCnpj.MANUAL))).isEqualTo(Motivo.SEM_CNPJ);
    assertThat(escritorio.cnpjVerificado()).isFalse();
    verify(escritorioRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("CNPJ ja verificado em outro escritorio: recusa (contestacao), pela consulta e pelo indice")
  void verificadoEmOutro() {
    escritorioSobLock();
    when(escritorioRepository.existsByCnpjAndCnpjVerificadoEmIsNotNullAndIdNot(CNPJ,
        escritorio.getId())).thenReturn(true);
    assertThat(motivo(() -> service.verificar(escritorio.getId(), CNPJ,
        MetodoVerificacaoCnpj.MANUAL))).isEqualTo(Motivo.CNPJ_VERIFICADO_EM_OUTRO_ESCRITORIO);
    assertThat(escritorio.cnpjVerificado()).isFalse();

    // Corrida: a consulta nao viu, o indice unico parcial do PostgreSQL barra.
    when(escritorioRepository.existsByCnpjAndCnpjVerificadoEmIsNotNullAndIdNot(CNPJ,
        escritorio.getId())).thenReturn(false, true);
    when(escritorioRepository.saveAndFlush(escritorio))
        .thenThrow(new DataIntegrityViolationException("uq_escritorio_cnpj_verificado"));
    assertThat(motivo(() -> service.verificar(escritorio.getId(), CNPJ,
        MetodoVerificacaoCnpj.MANUAL))).isEqualTo(Motivo.CNPJ_VERIFICADO_EM_OUTRO_ESCRITORIO);
  }

  @Test
  @DisplayName("outra violacao de integridade (o CHECK) nao vira contestacao: sobe como esta")
  void outraViolacaoSobe() {
    escritorioSobLock();
    DataIntegrityViolationException check =
        new DataIntegrityViolationException("ck_escritorio_cnpj_verificacao");
    when(escritorioRepository.saveAndFlush(escritorio)).thenThrow(check);

    assertThatThrownBy(() -> service.verificar(escritorio.getId(), CNPJ,
        MetodoVerificacaoCnpj.MANUAL)).isSameAs(check);
  }

  @Test
  @DisplayName("o CNPJ conferido perde so a pontuacao: letras ficam, em maiuscula (CNPJ alfanumerico)")
  void cnpjAlfanumerico() {
    escritorio.setCnpj("12ABC34501DE35");
    escritorioSobLock();

    assertThat(service.verificar(escritorio.getId(), "12.abc.345/01de-35",
        MetodoVerificacaoCnpj.MANUAL).mudou()).isTrue();
  }

  @Test
  @DisplayName("revogar: limpa a verificacao; sem ela, nada muda; inexistente e recusado")
  void revogar() {
    escritorio.verificarCnpj(MetodoVerificacaoCnpj.MANUAL, OffsetDateTime.now(CLOCK));
    escritorioSobLock();

    ResultadoVerificacao resultado = service.revogar(escritorio.getId());
    assertThat(escritorio.cnpjVerificado()).isFalse();
    assertThat(resultado.antes()).isEqualTo("MANUAL");
    assertThat(resultado.depois()).isEqualTo("NAO_VERIFICADO");
    verify(escritorioRepository).saveAndFlush(escritorio);

    assertThat(service.revogar(escritorio.getId()).mudou()).isFalse();

    UUID inexistente = UUID.randomUUID();
    when(escritorioRepository.findByIdForUpdate(inexistente)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.revogar(inexistente))
        .isInstanceOf(VerificacaoRecusadaException.class);
  }

  @Test
  @DisplayName("o texto do motivo vai so ao log do operador e nunca traz o CNPJ")
  void motivosSemCnpj() {
    for (Motivo motivo : Motivo.values()) {
      assertThat(motivo.descricao()).isNotBlank().doesNotContainPattern("\\d{14}");
      assertThat(new VerificacaoRecusadaException(motivo).getMessage())
          .isEqualTo(motivo.descricao());
    }
  }
}
