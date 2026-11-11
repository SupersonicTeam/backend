package br.com.agrocontabil.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Lê o {@code historico} da pergunta à Ceres (#499) e para no 4º elemento: um corpo com milhares
 * de itens vira um 400 de corpo ilegível, com mensagem fixa, em vez de milhares de erros de
 * validação. Item {@code null} fica na lista, para a validação apontar {@code historico[i]}.
 */
public final class HistoricoConversaDeserializer extends StdDeserializer<List<TurnoConversa>> {

  /** Trocas anteriores aceitas, no máximo. */
  public static final int MAXIMO_ITENS = 3;

  public HistoricoConversaDeserializer() {
    super(List.class);
  }

  @Override
  public List<TurnoConversa> deserialize(JsonParser parser, DeserializationContext contexto)
      throws IOException {
    if (!parser.isExpectedStartArrayToken()) {
      throw MismatchedInputException.from(parser, List.class, "historico deve ser uma lista");
    }
    List<TurnoConversa> itens = new ArrayList<>();
    JsonToken token;
    while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
      if (itens.size() == MAXIMO_ITENS) {
        throw MismatchedInputException.from(parser, List.class,
            "historico com mais de " + MAXIMO_ITENS + " itens");
      }
      itens.add(token == JsonToken.VALUE_NULL
          ? null : contexto.readValue(parser, TurnoConversa.class));
    }
    return itens;
  }
}
