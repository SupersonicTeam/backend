package br.com.agrocontabil.config;

import br.com.agrocontabil.security.UsuarioAutenticado;
import br.com.agrocontabil.security.UsuarioAutenticadoProvider;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Habilita o Spring Data JPA Auditing e fornece a identidade do auditor atual
 * (issue Agrocontabil#42 — RN005).
 *
 * <p>O {@link AuditorAware} resolve <em>quem</em> está agindo a partir da identidade autenticada
 * atual ({@link UsuarioAutenticadoProvider}) — produtor <em>ou</em> contador, desde a issue #93.
 * Registrar o contador importa justamente na RN005: a reabertura de período é ação dele, e um
 * rastro que só soubesse gravar produtor apontaria para a pessoa errada.
 *
 * <p><strong>Coexistência com {@code @PrePersist}/{@code @PreUpdate}:</strong> as entidades já
 * preenchem {@code createdAt}/{@code updatedAt} por callbacks manuais. Para não conflitar com esse
 * padrão, a reabertura da apuração <em>não</em> usa {@code @LastModifiedBy}/{@code @EntityListeners}
 * na entidade; em vez disso, o {@code ApuracaoService} lê este {@link AuditorAware} e grava a
 * identidade/instante em colunas dedicadas ({@code reaberto_por}/{@code data_reabertura}). Assim os
 * dois mecanismos convivem sem sobreposição de listeners.
 *
 * <p>Nunca expõe CPF/CNPJ: o auditor é o <strong>ID</strong> do produtor (UUID), não o documento
 * (LGPD).
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {

  /**
   * {@link AuditorAware} baseado na identidade autenticada atual.
   *
   * <p>Devolve o ID da identidade autenticada como {@code String}; devolve
   * {@link Optional#empty()} quando não há contexto de segurança (ex.: jobs agendados), caso em que
   * o Spring registra o auditor como nulo.
   *
   * <p>Consulta {@link UsuarioAutenticadoProvider#tentar()}, que não lança: o {@code catch} que
   * existia aqui pegava {@code IllegalStateException} e ficou inerte quando a issue #92 trocou essa
   * exceção por {@code NaoAutenticadoException} — um job agendado que gravasse entidade auditada
   * propagaria a exceção em vez de gravar sem auditor.
   *
   * @param usuarioProvider provedor da identidade autenticada (mock até o login real)
   * @return o auditor atual (ID do produtor ou do contador), ou vazio fora de um contexto
   *     autenticado
   */
  @Bean
  public AuditorAware<String> auditorAware(UsuarioAutenticadoProvider usuarioProvider) {
    return () -> usuarioProvider.tentar().map(UsuarioAutenticado::id).map(Object::toString);
  }

  /**
   * Relógio da aplicação (fuso {@code America/Sao_Paulo}), injetado onde a data corrente é regra
   * de negócio — como a janela de 3 meses da RN005 no {@code ApuracaoService}. Ter um bean
   * dedicado permite substituí-lo por um {@link Clock#fixed} nos testes, tornando a borda
   * temporal determinística.
   *
   * @return o relógio de referência do sistema
   */
  @Bean
  public Clock clock() {
    return Clock.system(ZoneId.of("America/Sao_Paulo"));
  }
}
