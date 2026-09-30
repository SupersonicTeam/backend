package br.com.agrocontabil.arquitetura;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fronteira do módulo da Ceres (issue #141; decisão de 2026-09-29).
 *
 * <p>A Ceres é um <strong>módulo isolado dentro do monólito</strong>: fala com o resto do backend
 * só pelas interfaces de {@code assistente.porta}, implementadas em {@code assistente.adaptador}.
 * Extraí-la como serviço no futuro é trocar os adaptadores por clientes HTTP — sem reescrever
 * orquestração, ferramentas nem RAG. Estas regras impedem que a fronteira se desfaça aos poucos.
 */
@AnalyzeClasses(packages = "br.com.agrocontabil", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquiteturaAssistenteTest {

  private static final String MODULO = "br.com.agrocontabil.assistente..";
  private static final String ADAPTADOR = "br.com.agrocontabil.assistente.adaptador..";

  /** Entidade JPA: a regra é sobre entidades, não sobre enums do pacote {@code model}. */
  private static final com.tngtech.archunit.base.DescribedPredicate<JavaClass> ENTIDADE =
      describe("entidades JPA", c -> c.isAnnotatedWith(Entity.class));

  @ArchTest
  static final ArchRule moduloSoEnxergaOBackendPelasPortas = noClasses()
      .that().resideInAPackage(MODULO)
      .and().resideOutsideOfPackage(ADAPTADOR)
      .should().dependOnClassesThat().resideInAnyPackage(
          // Lista do que o modulo NAO enxerga: todo o dominio e a infraestrutura de negocio. O que
          // sobra (dto, exception, common, config, enums de model) e contrato ou suporte.
          "br.com.agrocontabil.service..", "br.com.agrocontabil.repository..",
          "br.com.agrocontabil.security..", "br.com.agrocontabil.controller..",
          "br.com.agrocontabil.nfe..", "br.com.agrocontabil.sefaz..",
          "br.com.agrocontabil.danfe..", "br.com.agrocontabil.mapper..",
          "br.com.agrocontabil.event..", "br.com.agrocontabil.validation..")
      .orShould().dependOnClassesThat(ENTIDADE)
      .because("a Ceres so fala com o resto do backend pelas interfaces de assistente.porta; "
          + "assim ela pode virar um servico separado trocando so os adaptadores");

  @ArchTest
  static final ArchRule adaptadorPassaPelosServices = noClasses()
      .that().resideInAPackage(ADAPTADOR)
      .should().dependOnClassesThat(assignableTo(Repository.class))
      .because("o adaptador chama os services, onde vivem o anti-IDOR e a regra fiscal; "
          + "repository direto pularia os dois");

  @ArchTest
  static final ArchRule ninguemDependeDoModuloAlemDoControllerEConfig = noClasses()
      .that().resideOutsideOfPackage(MODULO)
      .and().resideOutsideOfPackages(
          "br.com.agrocontabil.controller..", "br.com.agrocontabil.config..")
      .should().dependOnClassesThat().resideInAPackage(MODULO)
      .because("o resto do backend nao conhece a Ceres: so o controller a expoe e a "
          + "configuracao a monta");

  @ArchTest
  static final ArchRule moduloNaoAbreTransacao = noClasses()
      .that().resideInAPackage(MODULO)
      .and().resideOutsideOfPackage(ADAPTADOR)
      .should().beAnnotatedWith(Transactional.class)
      .orShould().beAnnotatedWith(jakarta.transaction.Transactional.class)
      // Transacao programatica tambem conta: um TransactionTemplate em volta da chamada ao modelo
      // prenderia a conexao do mesmo jeito.
      .orShould().dependOnClassesThat().belongToAnyOf(
          TransactionTemplate.class, TransactionOperations.class,
          PlatformTransactionManager.class)
      .because("a chamada ao modelo nunca roda dentro de transacao: uma pergunta lenta prenderia "
          + "uma conexao do pool e derrubaria a emissao (licao das issues #96 e #99)");

  @ArchTest
  static final ArchRule acessoDiretoAoBancoSoNoRag = noClasses()
      .that().resideInAPackage(MODULO)
      .and().resideOutsideOfPackage("br.com.agrocontabil.assistente.rag..")
      .should().dependOnClassesThat().belongToAnyOf(
          JdbcTemplate.class, NamedParameterJdbcTemplate.class, JdbcClient.class,
          DataSource.class, EntityManager.class)
      .because("so o rag/ acessa o banco direto, e so a propria tabela (legislacao_trecho, "
          + "legislacao publica, sem dono). Dado de produtor passa pela porta e pelo service, "
          + "onde esta o filtro por produtor: SQL direto do modulo pularia o anti-IDOR");

  @ArchTest
  static final ArchRule metodosDoModuloNaoAbremTransacao = methods()
      .that().areDeclaredInClassesThat().resideInAPackage(MODULO)
      .and().areDeclaredInClassesThat().resideOutsideOfPackage(ADAPTADOR)
      .should().notBeAnnotatedWith(Transactional.class)
      .andShould().notBeAnnotatedWith(jakarta.transaction.Transactional.class)
      .because("mesma razao: transacao so nos services, chamados pelos adaptadores, e curta");
}
