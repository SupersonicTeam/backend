package br.com.agrocontabil.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Conjunto de tipos de documento numa coluna de texto separada por vírgula (issue #180) — a lista
 * do contrato, não uma string JSON. Mesmo desenho do {@link RegimesTributariosConverter}.
 */
@Converter
public class TiposDocumentoConverter
    implements AttributeConverter<Set<TipoDocumentoPendencia>, String> {

  @Override
  public String convertToDatabaseColumn(Set<TipoDocumentoPendencia> tipos) {
    if (tipos == null || tipos.isEmpty()) {
      return null;
    }
    return EnumSet.copyOf(tipos).stream().map(Enum::name).collect(Collectors.joining(","));
  }

  @Override
  public Set<TipoDocumentoPendencia> convertToEntityAttribute(String coluna) {
    if (coluna == null || coluna.isBlank()) {
      return EnumSet.noneOf(TipoDocumentoPendencia.class);
    }
    return Arrays.stream(coluna.split(",")).map(String::strip).filter(t -> !t.isEmpty())
        .map(TipoDocumentoPendencia::valueOf)
        .collect(Collectors.toCollection(() -> EnumSet.noneOf(TipoDocumentoPendencia.class)));
  }
}
