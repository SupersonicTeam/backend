package br.com.agrocontabil.assistente.rag;

import br.com.agrocontabil.assistente.DataSanitizer;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Busca textual na base de legislação, para o modo fallback (issue #146).
 *
 * <p>O embedding da busca vetorial ({@link BuscaLegislacaoService}) roda no Ollama: com ele fora,
 * a busca vetorial para junto com o chat. Esta busca usa só o PostgreSQL — a coluna
 * {@code conteudo_tsv_sem_acento} e o índice GIN do changeSet 0027 — sobre a
 * <strong>mesma</strong> tabela e com o <strong>mesmo</strong> filtro de vigência.
 *
 * <p>Como casa a pergunta:
 * <ul>
 *   <li><strong>sem acento dos dois lados</strong>: a pergunta passa pelo mesmo {@code translate}
 *       da coluna ({@link #COM_ACENTO} → {@link #SEM_ACENTO}), então "reducao de aliquota" casa com
 *       "redução de alíquota". Os dois mapas têm de ser iguais aos do changeSet 0027;</li>
 *   <li>a pergunta vira os lexemas do dicionário português ({@code to_tsvector}: sem stopwords,
 *       com radical), e a consulta é o <em>OU</em> deles. Um <em>E</em> de todas as palavras de uma
 *       pergunta em linguagem natural quase nunca casa;</li>
 *   <li>cada lexema é citado com {@code quote_literal} antes de virar {@code tsquery}: o texto do
 *       usuário nunca é interpretado como operador ({@code &}, {@code |}, {@code !}, {@code :*});
 *       </li>
 *   <li>o trecho precisa ter ao menos <strong>dois</strong> lexemas da pergunta (ou o único, se a
 *       pergunta tiver um só), senão "IBS" sozinho traria qualquer artigo da LC 214;</li>
 *   <li>ordem: mais lexemas casados, depois {@code ts_rank_cd} (densidade da cobertura).</li>
 * </ul>
 *
 * <p>Só PostgreSQL: o profile de teste (H2) não tem a tabela; os testes usam Testcontainers.
 */
@Service
public class BuscaTextualLegislacaoService {

  private static final Logger log = LoggerFactory.getLogger(BuscaTextualLegislacaoService.class);

  /** Mínimo de lexemas da pergunta que o trecho precisa conter (limitado aos da pergunta). */
  static final int LEXEMAS_MINIMOS = 2;

  /** Letras acentuadas que a coluna e a pergunta perdem (mesmo mapa do changeSet 0027). */
  static final String COM_ACENTO = "áàâãäéèêëíìîïóòôõöúùûüçñÁÀÂÃÄÉÈÊËÍÌÎÏÓÒÔÕÖÚÙÛÜÇÑ";

  /** Letra sem acento correspondente, posição a posição. */
  static final String SEM_ACENTO = "aaaaaeeeeiiiiooooouuuucnAAAAAEEEEIIIIOOOOOUUUUCN";

  private static final String SQL = """
      WITH termos AS (
        SELECT DISTINCT lexema
        FROM unnest(tsvector_to_array(to_tsvector('portuguese',
            translate(:pergunta, :comAcento, :semAcento)))) AS lexema
      ),
      consulta AS (
        SELECT string_agg(quote_literal(lexema), ' | ')::tsquery AS q, COUNT(*) AS n FROM termos
      ),
      candidatos AS (
        SELECT t.id, t.content, t.norma, t.dispositivo, t.metadata ->> 'url' AS url,
               ts_rank_cd(t.conteudo_tsv_sem_acento, c.q, 32) AS rank,
               (SELECT COUNT(*) FROM termos
                 WHERE t.conteudo_tsv_sem_acento @@ quote_literal(termos.lexema)::tsquery) AS casados,
               c.n AS total_termos
        FROM legislacao_trecho t, consulta c
        WHERE c.n > 0
          AND t.conteudo_tsv_sem_acento @@ c.q
          AND t.vigencia_inicio <= :data
          AND t.vigencia_fim >= :data
      )
      SELECT id, content, norma, dispositivo, url, rank, casados
      FROM candidatos
      WHERE casados >= LEAST(:minimo, total_termos)
      ORDER BY casados DESC, rank DESC, id
      LIMIT :limite
      """;

  private final NamedParameterJdbcTemplate jdbc;

  /** Constrói a busca sobre o banco da aplicação. */
  public BuscaTextualLegislacaoService(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Trechos vigentes na data que mais casam com a pergunta.
   *
   * @param pergunta pergunta do usuário (mascarada antes da consulta, regra de tudo que sai)
   * @param data     data de referência (hoje, em {@code America/Sao_Paulo})
   * @param limite   quantos trechos devolver (1 a 10)
   * @return trechos do mais para o menos relevante (pode ser vazia)
   * @throws AssistenteIndisponivelException se o banco falhar — sem modelo e sem base não há o que
   *         responder
   */
  public List<TrechoRecuperado> buscar(String pergunta, LocalDate data, int limite) {
    MapSqlParameterSource parametros = new MapSqlParameterSource()
        .addValue("pergunta", DataSanitizer.mascarar(pergunta))
        .addValue("comAcento", COM_ACENTO)
        .addValue("semAcento", SEM_ACENTO)
        .addValue("data", data.toString())
        .addValue("minimo", LEXEMAS_MINIMOS)
        .addValue("limite", Math.max(1, Math.min(limite, 10)));
    try {
      List<TrechoRecuperado> trechos = new ArrayList<>();
      jdbc.query(SQL, parametros, rs -> {
        trechos.add(new TrechoRecuperado(
            "T" + (trechos.size() + 1),
            rs.getString("id"),
            rs.getString("norma"),
            rs.getString("dispositivo"),
            rs.getString("url"),
            rs.getString("content"),
            rs.getDouble("rank")));
      });
      return trechos;
    } catch (DataAccessException e) {
      log.warn("Falha na busca textual de legislacao: {}", e.getClass().getName());
      throw new AssistenteIndisponivelException(
          "Falha na busca textual de legislacao: " + e.getClass().getSimpleName(), e);
    }
  }
}
