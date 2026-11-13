package br.com.agrocontabil.assistente;

import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import java.util.List;

/**
 * O texto de lei que o modelo escreveu e a rota aceitou (#499), para a continuidade: a faixa
 * {@code lei.r} da carga e a concordância da reconferência.
 *
 * @param textoEntregue  o texto como foi entregue, com as referências, sem o glossário (depois do
 *                       "Sim,"/"Não," tirado no MISTO com pergunta separada)
 * @param textoComMarcas o texto do modelo, com as marcas [T1]..., antes de tirar o "Sim,"/"Não,"
 * @param trechos        os trechos que o modelo recebeu, na ordem das marcas
 */
public record DetalheLei(String textoEntregue, String textoComMarcas,
    List<TrechoRecuperado> trechos) {

  /** Copia a lista. */
  public DetalheLei {
    trechos = List.copyOf(trechos);
  }

  /** Sem o texto. */
  @Override
  public String toString() {
    return "DetalheLei[trechos=" + trechos.size() + "]";
  }
}
