package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.AssistenteInteracao;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Auditoria das perguntas à Ceres (issue #144). */
public interface AssistenteInteracaoRepository extends JpaRepository<AssistenteInteracao, UUID> {

  /**
   * Interações de um produtor, da mais recente para a mais antiga.
   *
   * @param produtorId produtor
   * @return interações do produtor
   */
  List<AssistenteInteracao> findByProdutorIdOrderByCriadoEmDesc(UUID produtorId);

  /**
   * Completa o registro da pergunta com o desfecho da explicação (#268), que vem depois da
   * resposta. Uma vez só: o registro que já tem desfecho de explicação não muda. SQL nativo de
   * propósito: o registro é imutável na entidade (as colunas não são atualizáveis por ela).
   *
   * @param id             registro da pergunta
   * @param desfecho       nome do {@code DesfechoExplicacao}
   * @param latenciaMs     tempo da explicação
   * @param tokensEntrada  tokens de entrada da chamada, ou nulo
   * @param tokensSaida    tokens de saída da chamada, ou nulo
   * @return linhas atualizadas (0 se o registro não existe ou já tinha desfecho)
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(nativeQuery = true, value = """
      UPDATE assistente_interacao
      SET desfecho_explicacao = :desfecho, latencia_explicacao_ms = :latenciaMs,
          tokens_entrada_explicacao = :tokensEntrada, tokens_saida_explicacao = :tokensSaida
      WHERE id = :id AND desfecho_explicacao IS NULL
      """)
  int registrarExplicacao(@Param("id") UUID id, @Param("desfecho") String desfecho,
      @Param("latenciaMs") long latenciaMs, @Param("tokensEntrada") Integer tokensEntrada,
      @Param("tokensSaida") Integer tokensSaida);
}
