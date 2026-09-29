package br.com.agrocontabil.assistente.llm;

import java.util.Locale;
import org.springframework.ai.ollama.api.ThinkOption;
import org.springframework.core.convert.converter.Converter;

/**
 * Converte {@code spring.ai.ollama.chat.options.think-option} de texto para {@link ThinkOption}
 * (issue #141). O Spring AI 1.1 declara a opção como interface, que o binder não sabe instanciar a
 * partir do yml.
 *
 * <p>Por que importa: o {@code qwen3} raciocina ("thinking") por padrão. Os tokens de raciocínio
 * contam no teto {@code num-predict} — a resposta pode sair cortada ou vazia — e multiplicam a
 * latência em CPU. A Ceres não precisa raciocinar sobre números (eles vêm das funções), então o
 * padrão do projeto é {@code false}.
 *
 * <p>Valores aceitos: {@code true}, {@code false}, {@code low}, {@code medium}, {@code high}.
 * Vazio = não enviar a opção (modelo sem suporte a thinking). Qualquer outro valor impede a subida.
 */
public class ThinkOptionConverter implements Converter<String, ThinkOption> {

  @Override
  public ThinkOption convert(String valor) {
    String normalizado = valor.trim().toLowerCase(Locale.ROOT);
    return switch (normalizado) {
      case "" -> null;
      case "true" -> ThinkOption.ThinkBoolean.ENABLED;
      case "false" -> ThinkOption.ThinkBoolean.DISABLED;
      case "low" -> ThinkOption.ThinkLevel.LOW;
      case "medium" -> ThinkOption.ThinkLevel.MEDIUM;
      case "high" -> ThinkOption.ThinkLevel.HIGH;
      default -> throw new IllegalArgumentException(
          "spring.ai.ollama.chat.options.think-option invalido: '" + valor
              + "' (use true, false, low, medium ou high)");
    };
  }
}
