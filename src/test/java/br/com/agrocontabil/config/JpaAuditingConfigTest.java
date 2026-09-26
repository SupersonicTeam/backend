package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.Contador;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.security.UsuarioAutenticadoProvider;
import br.com.agrocontabil.support.AutenticacaoTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.AuditorAware;

/**
 * Testes do auditor do JPA — quem fica registrado como autor da ação (RN005, issue #93).
 *
 * <p>Cobre a regressão que passou despercebida desde a issue #92: o bean capturava
 * {@code IllegalStateException} para tratar "sem contexto autenticado", mas o provider passou a
 * lançar {@code NaoAutenticadoException}. O {@code catch} ficou inerte e um job agendado que
 * gravasse entidade auditada propagaria a exceção em vez de gravar sem auditor.
 */
@DisplayName("JpaAuditingConfig — auditor da acao (#93)")
class JpaAuditingConfigTest {

  private AuditorAware<String> auditor;

  @BeforeEach
  void setUp() {
    AutenticacaoTestSupport.limpar();
    auditor = new JpaAuditingConfig().auditorAware(new UsuarioAutenticadoProvider());
  }

  @AfterEach
  void tearDown() {
    AutenticacaoTestSupport.limpar();
  }

  @Test
  @DisplayName("sem contexto autenticado (job agendado) devolve vazio, sem lancar")
  void semContextoDevolveVazio() {
    assertThat(auditor.getCurrentAuditor()).isEmpty();
  }

  @Test
  @DisplayName("produtor autenticado e registrado pelo ID, nunca pelo CPF/CNPJ (LGPD)")
  void produtorAutenticadoRegistraId() {
    Produtor produtor =
        Produtor.builder().id(UUID.randomUUID()).nomeCompleto("Joao").cpfCnpj("123.456.789-00")
            .build();
    AutenticacaoTestSupport.autenticarComo(produtor);

    assertThat(auditor.getCurrentAuditor()).contains(produtor.getId().toString());
  }

  @Test
  @DisplayName("contador autenticado e registrado pelo proprio ID — a reabertura e acao dele")
  void contadorAutenticadoRegistraId() {
    Contador contador =
        Contador.builder().id(UUID.randomUUID()).nomeCompleto("Escritorio").build();
    AutenticacaoTestSupport.autenticarComo(contador);

    assertThat(auditor.getCurrentAuditor()).contains(contador.getId().toString());
  }
}
