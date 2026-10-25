package br.com.agrocontabil.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Conjunto de regimes numa coluna de texto separada por vírgula ({@code PRESUMIDO,REAL}); vazio
 * ou nulo = todos (issue #181).
 */
@Converter
public class RegimesTributariosConverter
    implements AttributeConverter<Set<RegimeTributario>, String> {

  @Override
  public String convertToDatabaseColumn(Set<RegimeTributario> regimes) {
    if (regimes == null || regimes.isEmpty()) {
      return null;
    }
    // Ordem do enum: a mesma lista grava sempre o mesmo texto.
    return EnumSet.copyOf(regimes).stream().map(Enum::name).collect(Collectors.joining(","));
  }

  @Override
  public Set<RegimeTributario> convertToEntityAttribute(String coluna) {
    if (coluna == null || coluna.isBlank()) {
      return EnumSet.noneOf(RegimeTributario.class);
    }
    return Arrays.stream(coluna.split(",")).map(String::strip).filter(r -> !r.isEmpty())
        .map(RegimeTributario::valueOf)
        .collect(Collectors.toCollection(() -> EnumSet.noneOf(RegimeTributario.class)));
  }
}
