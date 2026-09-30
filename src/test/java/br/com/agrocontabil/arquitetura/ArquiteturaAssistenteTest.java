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
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

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
          "br.com.agrocontabil.service..", "br.com.agrocontabil.repository..")
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
      .because("a chamada ao modelo nunca roda dentro de transacao: uma pergunta lenta prenderia "
          + "uma conexao do pool e derrubaria a emissao (licao das issues #96 e #99)");

  @ArchTest
  static final ArchRule metodosDoModuloNaoAbremTransacao = methods()
      .that().areDeclaredInClassesThat().resideInAPackage(MODULO)
      .and().areDeclaredInClassesThat().resideOutsideOfPackage(ADAPTADOR)
      .should().notBeAnnotatedWith(Transactional.class)
      .because("mesma razao: transacao so nos services, chamados pelos adaptadores, e curta");
}
