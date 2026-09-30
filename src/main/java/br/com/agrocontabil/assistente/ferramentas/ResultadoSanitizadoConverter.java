package br.com.agrocontabil.assistente.ferramentas;

import br.com.agrocontabil.assistente.DataSanitizer;
import java.lang.reflect.Type;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.ai.tool.execution.ToolCallResultConverter;

/**
 * Serializa o resultado de uma função da Ceres e aplica o {@link DataSanitizer} antes de ele ir
 * ao modelo (issue #144).
 *
 * <p>Declarado em cada {@code @Tool} ({@code resultConverter}); o {@code DefinicaoFerramentasTest}
 * falha se uma função nova esquecer. É o ponto único por onde o resultado de qualquer função passa
 * a caminho do provedor. Instanciado pelo Spring AI por reflexão: precisa do construtor sem
 * argumentos.
 */
public class ResultadoSanitizadoConverter implements ToolCallResultConverter {

  private final DefaultToolCallResultConverter padrao = new DefaultToolCallResultConverter();

  @Override
  public String convert(Object resultado, Type tipo) {
    return DataSanitizer.mascarar(padrao.convert(resultado, tipo));
  }
}
