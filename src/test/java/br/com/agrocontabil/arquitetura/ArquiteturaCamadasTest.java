package br.com.agrocontabil.arquitetura;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import jakarta.persistence.Entity;

/**
 * Camadas do projeto inteiro: {@code Controller → Service → Repository} (CLAUDE.md, issue #141).
 *
 * <p>As regras são {@link FreezingArchRule}: violações que já existiam quando a regra nasceu ficam
 * na linha de base ({@code src/test/resources/archunit_store}) e são tratadas depois; qualquer
 * violação <strong>nova</strong> quebra o teste. Ao corrigir uma violação antiga, ela sai da linha
 * de base sozinha na próxima execução.
 */
@AnalyzeClasses(packages = "br.com.agrocontabil", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquiteturaCamadasTest {

  @ArchTest
  static final ArchRule camadas = FreezingArchRule.freeze(layeredArchitecture()
      .consideringOnlyDependenciesInLayers()
      .layer("Controller").definedBy("br.com.agrocontabil.controller..")
      .layer("Service").definedBy("br.com.agrocontabil.service..")
      .layer("Repository").definedBy("br.com.agrocontabil.repository..")
      .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
      .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
      .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service"));

  @ArchTest
  static final ArchRule controllerSemRepository = FreezingArchRule.freeze(noClasses()
      .that().resideInAPackage("br.com.agrocontabil.controller..")
      .should().dependOnClassesThat().resideInAPackage("br.com.agrocontabil.repository..")
      .because("controller so orquestra: recebe DTO, chama service, devolve resposta"));

  @ArchTest
  static final ArchRule controllerNaoExpoeEntidade = FreezingArchRule.freeze(noClasses()
      .that().resideInAPackage("br.com.agrocontabil.controller..")
      .should().dependOnClassesThat().areAnnotatedWith(Entity.class)
      .because("entidades JPA nunca sao expostas na API: request e response sao DTOs"));
}
