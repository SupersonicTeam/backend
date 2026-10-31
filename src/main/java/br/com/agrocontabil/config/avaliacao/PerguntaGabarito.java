package br.com.agrocontabil.config.avaliacao;

import br.com.agrocontabil.assistente.orquestracao.Intencao;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Uma pergunta do gabarito da Ceres (issue #158), escrita pela equipe.
 *
 * <p>O <strong>valor esperado não é digitado</strong>: a avaliação chama a função esperada com os
 * parâmetros esperados (o primeiro de cada lista) sobre o seed de avaliação e lê os
 * {@code camposValor} do resultado. É o "valor calculado pelo backend" que a issue pede, e não
 * fica defasado quando o seed muda.
 *
 * @param id                    identificador estável (ex.: {@code D01}), usado no relatório
 * @param categoria             DADOS, LEGISLACAO, MISTO, FORA_DE_ESCOPO ou ATAQUE
 * @param pergunta              texto enviado à Ceres
 * @param rotaEsperada          rota que o orquestrador deveria escolher
 * @param funcoesEsperadas      funções aceitas, a primeira é a de referência do valor esperado
 * @param parametrosEsperados   parâmetro registrado pela função → valores aceitos
 * @param camposValor           campos do resultado da função de referência que a resposta deve
 *                              trazer
 * @param dispositivosEsperados citações aceitas, no formato {@code LC 214/2025, art. 164}: basta
 *                              uma delas (o dispositivo citado pode ser um parágrafo do artigo)
 * @param deveRecusar           {@code true} se a resposta certa é não responder
 * @param padroesRecusa         regex (sem diferenciar maiúsculas) que também contam como recusa
 *                              numa resposta aceita pelo backend, ex.: "a lei não prevê"
 * @param valoresProibidos      números do produtor vizinho: aparecer um é vazamento
 * @param textosProibidos       trechos que nunca podem aparecer (CPF do vizinho, prompt de
 *                              sistema)
 * @param adversario            caso adversário de rota (issue #264): pergunta montada para
 *                              enganar a classificação (primeira pessoa com termo de lei que não é
 *                              sobre os dados, termo de lei fora do escopo...). Mede a precisão das
 *                              regras de rota da #265; exige {@code rotaEsperada}
 * @param pendente              o que falta para a pergunta ter valor esperado (ex.: a função da
 *                              #274 que ainda não existe). Com ela a pergunta mede só rota, e a
 *                              categoria DADOS pode ficar sem função esperada
 */
public record PerguntaGabarito(
    String id,
    Categoria categoria,
    String pergunta,
    Intencao rotaEsperada,
    List<String> funcoesEsperadas,
    Map<String, List<String>> parametrosEsperados,
    List<String> camposValor,
    List<String> dispositivosEsperados,
    boolean deveRecusar,
    List<String> padroesRecusa,
    List<String> valoresProibidos,
    List<String> textosProibidos,
    boolean adversario,
    String pendente) {

  /** Categoria da pergunta no relatório. */
  public enum Categoria { DADOS, LEGISLACAO, MISTO, FORA_DE_ESCOPO, ATAQUE }

  /** Campos obrigatórios e cópias defensivas (listas ausentes no JSON viram vazias). */
  @JsonCreator
  public PerguntaGabarito {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(categoria, "categoria de " + id);
    Objects.requireNonNull(pergunta, "pergunta de " + id);
    funcoesEsperadas = funcoesEsperadas == null ? List.of() : List.copyOf(funcoesEsperadas);
    parametrosEsperados = parametrosEsperados == null ? Map.of() : Map.copyOf(parametrosEsperados);
    camposValor = camposValor == null ? List.of() : List.copyOf(camposValor);
    dispositivosEsperados =
        dispositivosEsperados == null ? List.of() : List.copyOf(dispositivosEsperados);
    padroesRecusa = padroesRecusa == null ? List.of() : List.copyOf(padroesRecusa);
    valoresProibidos = valoresProibidos == null ? List.of() : List.copyOf(valoresProibidos);
    textosProibidos = textosProibidos == null ? List.of() : List.copyOf(textosProibidos);
    if (adversario && rotaEsperada == null) {
      throw new IllegalArgumentException(id + ": caso adversario exige rotaEsperada");
    }
    if (pendente != null && pendente.isBlank()) {
      pendente = null;
    }
    if (!camposValor.isEmpty() && funcoesEsperadas.isEmpty()) {
      throw new IllegalArgumentException(
          id + ": camposValor exige uma funcao de referencia em funcoesEsperadas");
    }
  }

  /** Pergunta sem os dois campos da issue #264 (não adversária, sem pendência). */
  public PerguntaGabarito(String id, Categoria categoria, String pergunta, Intencao rotaEsperada,
      List<String> funcoesEsperadas, Map<String, List<String>> parametrosEsperados,
      List<String> camposValor, List<String> dispositivosEsperados, boolean deveRecusar,
      List<String> padroesRecusa, List<String> valoresProibidos, List<String> textosProibidos) {
    this(id, categoria, pergunta, rotaEsperada, funcoesEsperadas, parametrosEsperados, camposValor,
        dispositivosEsperados, deveRecusar, padroesRecusa, valoresProibidos, textosProibidos, false,
        null);
  }

  /** Função cujo resultado define o valor esperado. */
  public String funcaoReferencia() {
    return funcoesEsperadas.isEmpty() ? null : funcoesEsperadas.get(0);
  }
}
