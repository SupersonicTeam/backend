package br.com.agrocontabil.config.avaliacao;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Gabarito versionado da avaliação da Ceres (issue #158).
 *
 * @param versao    versão do gabarito: muda a cada pergunta alterada, para que dois relatórios só
 *                  sejam comparados se mediram a mesma coisa
 * @param perguntas perguntas, na ordem em que rodam
 */
public record Gabarito(String versao, List<PerguntaGabarito> perguntas) {

  /** Versão obrigatória, ao menos uma pergunta e ids sem repetição. */
  public Gabarito {
    Objects.requireNonNull(versao, "versao");
    if (perguntas == null || perguntas.isEmpty()) {
      throw new IllegalArgumentException("o gabarito nao tem perguntas");
    }
    perguntas = List.copyOf(perguntas);
    Set<String> ids = new HashSet<>();
    for (PerguntaGabarito p : perguntas) {
      if (!ids.add(p.id())) {
        throw new IllegalArgumentException("id repetido no gabarito: " + p.id());
      }
    }
  }
}
