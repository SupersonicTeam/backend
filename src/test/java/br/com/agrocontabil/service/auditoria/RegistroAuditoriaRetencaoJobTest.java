package br.com.agrocontabil.service.auditoria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import br.com.agrocontabil.config.AuditoriaProperties;
import br.com.agrocontabil.repository.RegistroAuditoriaRepository;
import br.com.agrocontabil.service.GuiaFiscalService;
import br.com.agrocontabil.service.LoginService;
import br.com.agrocontabil.service.NotaFiscalService;
import br.com.agrocontabil.service.SefazService;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Retencao da auditoria e cobertura das operacoes criticas (#149, RNF012)")
class RegistroAuditoriaRetencaoJobTest {

  @Test
  @DisplayName("corte no 1o de janeiro de 5 anos atras: fato de mar/2026 vive ate o fim de 2031")
  void apagaAnterioresACincoAnos() {
    RegistroAuditoriaRepository repository = mock(RegistroAuditoriaRepository.class);
    Clock clock = Clock.fixed(Instant.parse("2031-10-20T06:30:00Z"),
        ZoneId.of("America/Sao_Paulo"));

    new RegistroAuditoriaRetencaoJob(repository, new AuditoriaProperties(5), clock)
        .aplicarRetencao();

    verify(repository).apagarAnterioresA(
        OffsetDateTime.parse("2026-01-01T00:00:00-03:00"));
  }

  @Test
  @DisplayName("retencao abaixo de 5 anos impede a aplicacao de subir")
  void retencaoMinima() {
    assertThatThrownBy(() -> new AuditoriaProperties(4))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("RNF012");
  }

  @Test
  @DisplayName("criar, editar, excluir, emitir, transmitir e cancelar nota estao anotados")
  void operacoesCriticasAnotadas() {
    assertThat(anotados(NotaFiscalService.class))
        .contains("create", "update", "delete", "gerarXml");
    assertThat(anotados(SefazService.class)).contains("enviar", "cancelar");
    assertThat(anotados(GuiaFiscalService.class)).contains("criar", "marcarComoPaga");
    assertThat(anotados(LoginService.class)).contains("sairDeTodos");
  }

  private static String[] anotados(Class<?> tipo) {
    return Arrays.stream(tipo.getDeclaredMethods())
        .filter(m -> m.isAnnotationPresent(Auditado.class))
        .map(Method::getName)
        .toArray(String[]::new);
  }
}
