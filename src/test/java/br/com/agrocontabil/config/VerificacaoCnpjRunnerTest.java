package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.agrocontabil.model.MetodoVerificacaoCnpj;
import br.com.agrocontabil.service.VerificacaoCnpjService;
import br.com.agrocontabil.service.VerificacaoCnpjService.Motivo;
import br.com.agrocontabil.service.VerificacaoCnpjService.ResultadoVerificacao;
import br.com.agrocontabil.service.VerificacaoCnpjService.VerificacaoRecusadaException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;

@DisplayName("Comando da verificacao manual do CNPJ (#520)")
class VerificacaoCnpjRunnerTest {

  private static final UUID ESCRITORIO = UUID.fromString("3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10");

  private final VerificacaoCnpjService service = mock(VerificacaoCnpjService.class);

  private VerificacaoCnpjRunner runner(String escritorio, String cnpj, boolean revogar) {
    return new VerificacaoCnpjRunner(service, mock(ConfigurableApplicationContext.class),
        escritorio, cnpj, revogar);
  }

  @Test
  @DisplayName("so no profile verificacao-cnpj, nunca como endpoint")
  void soNoProfileProprio() {
    assertThat(VerificacaoCnpjRunner.class.getAnnotation(Profile.class).value())
        .containsExactly("verificacao-cnpj");
  }

  @Test
  @DisplayName("verifica com o metodo MANUAL e sai com 0, mesmo sem mudanca")
  void verifica() {
    when(service.verificar(ESCRITORIO, "11444777000161", MetodoVerificacaoCnpj.MANUAL))
        .thenReturn(new ResultadoVerificacao(ESCRITORIO, "NAO_VERIFICADO", "MANUAL"))
        .thenReturn(new ResultadoVerificacao(ESCRITORIO, "MANUAL", "MANUAL"));

    assertThat(runner(ESCRITORIO.toString(), "11444777000161", false).executar()).isZero();
    assertThat(runner(" " + ESCRITORIO + " ", "11444777000161", false).executar()).isZero();
  }

  @Test
  @DisplayName("revoga sem pedir o CNPJ")
  void revoga() {
    when(service.revogar(ESCRITORIO))
        .thenReturn(new ResultadoVerificacao(ESCRITORIO, "MANUAL", "NAO_VERIFICADO"));

    assertThat(runner(ESCRITORIO.toString(), "", true).executar()).isZero();
    verify(service).revogar(ESCRITORIO);
  }

  @Test
  @DisplayName("parametro faltando ou invalido: sai com 1 sem chamar o servico")
  void parametros() {
    assertThat(runner("", "11444777000161", false).executar()).isEqualTo(1);
    assertThat(runner("nao-e-uuid", "11444777000161", false).executar()).isEqualTo(1);
    assertThat(runner(ESCRITORIO.toString(), " ", false).executar()).isEqualTo(1);
    verifyNoInteractions(service);
  }

  @Test
  @DisplayName("marca recusada ou erro inesperado: sai com 1")
  void recusada() {
    when(service.verificar(any(), any(), any())).thenThrow(
        new VerificacaoRecusadaException(Motivo.CNPJ_DIFERENTE_DO_CONFERIDO));
    assertThat(runner(ESCRITORIO.toString(), "11444777000161", false).executar()).isEqualTo(1);

    when(service.revogar(any())).thenThrow(new IllegalStateException("banco fora"));
    assertThat(runner(ESCRITORIO.toString(), "", true).executar()).isEqualTo(1);
  }
}
