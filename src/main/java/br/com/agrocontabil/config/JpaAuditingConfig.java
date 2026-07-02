package br.com.agrocontabil.config;

import br.com.agrocontabil.service.AuthenticatedProdutorProvider;
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
 * atual ({@link AuthenticatedProdutorProvider}). Hoje essa identidade é o produtor mockado
 * ({@code MockProdutorFilter}); quando a autenticação real (com papel de contador) chegar na
 * Sprint 8, este componente passa a devolver o usuário real <strong>sem alterar</strong> os
 * serviços que o consomem.
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
   * <p>Devolve o ID do produtor autenticado como {@code String}; devolve {@link Optional#empty()}
   * quando não há contexto de segurança (ex.: jobs agendados), caso em que o Spring registra o
   * auditor como nulo.
   *
   * @param produtorProvider provedor do produtor autenticado (identidade mock até a Sprint 8)
   * @return o auditor atual (ID do produtor), ou vazio fora de um contexto autenticado
   */
  @Bean
  public AuditorAware<String> auditorAware(AuthenticatedProdutorProvider produtorProvider) {
    return () -> {
      try {
        return Optional.of(produtorProvider.get().getId().toString());
      } catch (IllegalStateException semAuth) {
        // Sem contexto autenticado (ex.: job agendado) — sem auditor.
        return Optional.empty();
      }
    };
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
