package br.com.agrocontabil.assistente.porta;

import java.util.Optional;
import java.util.UUID;

/**
 * Assinatura das trocas da continuidade (#499). A Ceres escreve e lê a carga (JSON); a porta só
 * prende o par ao produtor com o MAC do servidor, sem a Ceres conhecer a chave.
 */
public interface HistoricoConversaPorta {

  /**
   * Assina um par.
   *
   * @param produtorId produtor do token
   * @param carga      carga em JSON compacto
   * @param pergunta   pergunta considerada
   * @param resposta   resposta entregue
   * @return a assinatura; vazio se o par não pode ser assinado (texto com surrogate solto, carga
   *         acima do teto)
   */
  Optional<String> assinar(UUID produtorId, String carga, String pergunta, String resposta);

  /**
   * Confere um par recebido do cliente. Silenciosa: nunca lança nem registra o valor.
   *
   * @param produtorId produtor do token
   * @param assinatura assinatura recebida
   * @param pergunta   pergunta recebida
   * @param resposta   resposta recebida
   * @return a carga (JSON), se o MAC confere
   */
  Optional<String> conferir(UUID produtorId, String assinatura, String pergunta, String resposta);
}
