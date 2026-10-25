package br.com.agrocontabil.config;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.DadoPessoalCifradoConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * Toda coluna cifrada pelo {@link DadoPessoalCifradoConverter} está no
 * {@link CifragemLegadoRunner#COLUNAS_CIFRADAS} (#147; regra do CLAUDE.md). Sem isso, uma linha
 * gravada em claro (seed, inserção manual) nunca seria cifrada e a busca por igualdade a perderia.
 */
@DisplayName("CifragemLegadoRunner cobre toda coluna com DadoPessoalCifradoConverter")
class CifragemColunasCobertasTest {

  @Test
  @DisplayName("nenhuma coluna cifrada fica fora de COLUNAS_CIFRADAS")
  void todaColunaCifradaEstaNoRunner() throws Exception {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

    List<String> faltando = new ArrayList<>();
    for (BeanDefinition definicao : scanner.findCandidateComponents("br.com.agrocontabil.model")) {
      Class<?> entidade = Class.forName(definicao.getBeanClassName());
      Table tabela = entidade.getAnnotation(Table.class);
      for (Field campo : entidade.getDeclaredFields()) {
        Convert convert = campo.getAnnotation(Convert.class);
        if (convert == null || convert.converter() != DadoPessoalCifradoConverter.class) {
          continue;
        }
        String coluna = campo.getAnnotation(Column.class).name();
        List<String> cobertas = CifragemLegadoRunner.COLUNAS_CIFRADAS.get(tabela.name());
        if (cobertas == null || !cobertas.contains(coluna)) {
          faltando.add(tabela.name() + "." + coluna);
        }
      }
    }

    assertThat(faltando).isEmpty();
  }
}
