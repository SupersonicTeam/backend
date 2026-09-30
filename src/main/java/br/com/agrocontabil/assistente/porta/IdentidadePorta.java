package br.com.agrocontabil.assistente.porta;

import java.util.UUID;

/**
 * Porta da identidade da requisição (issue #141, fronteira do módulo).
 *
 * <p>Chamada <strong>uma vez, na thread da requisição</strong>, antes de qualquer chamada ao
 * modelo. O id devolvido vai para o {@code ContextoAssistente} e é dali que as funções o tiram —
 * elas nunca consultam a identidade de novo (com streaming rodam em outra thread, issue #143).
 */
public interface IdentidadePorta {

  /**
   * Produtor autenticado na requisição corrente.
   *
   * @return id do produtor
   * @throws br.com.agrocontabil.exception.NaoAutenticadoException sem identidade válida (401)
   * @throws br.com.agrocontabil.exception.PapelNaoAutorizadoException quem pergunta é contador (403)
   */
  UUID produtorAutenticado();
}
