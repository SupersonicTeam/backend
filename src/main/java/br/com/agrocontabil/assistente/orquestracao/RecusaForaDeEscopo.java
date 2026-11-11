package br.com.agrocontabil.assistente.orquestracao;

import br.com.agrocontabil.assistente.PromptsCeres;
import br.com.agrocontabil.assistente.SinalRecusaFirme;

/**
 * O texto da recusa de fora de escopo (#494). Função pura: a classificação e a pergunta bastam.
 *
 * <ul>
 *   <li>Injeção pega pelo {@link FiltroInjecao}, pedido recusado antes do classificador
 *       ({@link SinalRecusaFirme#inequivoco}, #497), fora de escopo que o classificador por modelo
 *       marcou como pedido sobre outra pessoa ou ordem ao sistema
 *       ({@link ClassificacaoIntencao#recusaFirme()}, #497) e pedido com
 *       {@link SinalRecusaFirme} (outra pessoa, documento ou identificador, ataque que o filtro
 *       não pega) recebem a
 *       {@link PromptsCeres#RECUSA_FIRME}: sem exemplo nem convite.</li>
 *   <li>Todo o resto recebe a {@link PromptsCeres#RECUSA_GENTIL}, inclusive confiança baixa,
 *       classificação indeterminada e classificação cortada pelo teto. É um texto só, sem "tente
 *       escrever de outro jeito": a injeção que escapa do filtro e cai na confiança baixa não
 *       ganha convite para reformular o ataque, e a classificação cortada é falha do servidor,
 *       não do produtor.</li>
 * </ul>
 */
final class RecusaForaDeEscopo {

  private RecusaForaDeEscopo() {}

  /**
   * A recusa de uma pergunta classificada como fora de escopo.
   *
   * @param classificacao a classificação (a camada diz se foi o filtro de injeção)
   * @param pergunta      a pergunta do usuário
   * @return o texto da recusa
   */
  static String texto(ClassificacaoIntencao classificacao, String pergunta) {
    if (classificacao.camada() == CamadaClassificacao.FILTRO_INJECAO
        || classificacao.camada() == CamadaClassificacao.SINAL_RECUSA_FIRME
        || classificacao.recusaFirme()
        || SinalRecusaFirme.presente(pergunta)) {
      return PromptsCeres.RECUSA_FIRME;
    }
    return PromptsCeres.RECUSA_GENTIL;
  }
}
