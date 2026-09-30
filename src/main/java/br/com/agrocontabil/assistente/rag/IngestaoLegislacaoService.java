package br.com.agrocontabil.assistente.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Ingestão da legislação na base vetorial da Ceres (issue #156).
 *
 * <p><strong>Idempotente pelo hash:</strong> cada trecho tem id derivado do SHA-256 de norma +
 * dispositivo + texto. Rodar a ingestão duas vezes com o mesmo texto não insere nada (e a coluna
 * {@code hash} tem índice único como segunda barreira).
 *
 * <p><strong>Nova redação = nova vigência, nunca update do texto</strong> (mesma regra das
 * alíquotas, issue #85): o trecho novo entra com {@code vigenciaInicio} da fonte, e a redação
 * anterior do mesmo dispositivo tem só o {@code vigenciaFim} fechado na véspera. A pergunta feita
 * numa data recebe a redação vigente naquela data.
 *
 * <p><strong>Ordem que se repara sozinha:</strong> em cada lote, primeiro insere as redações novas
 * (com embedding), depois fecha as anteriores. O fechamento roda para todo dispositivo do texto,
 * inclusive os inalterados: se uma execução cair entre as duas etapas, a próxima fecha o que ficou
 * aberto — sem buraco de vigência e sem duplicar trecho.
 *
 * <p>Sem {@code @Transactional}: o embedding (rede) acontece dentro do {@code VectorStore.add}, e
 * uma transação aberta durante a geração de centenas de embeddings estouraria o timeout e prenderia
 * uma conexão. Cada comando é curto e a ordem acima garante a consistência.
 *
 * <p>Só legislação pública entra aqui (issue #143): esta base não tem dono.
 */
@Service
public class IngestaoLegislacaoService {

  /** Dispositivos por lote: um {@code add} (com embedding) e um fechamento por lote. */
  static final int LOTE = 50;

  private static final Logger log = LoggerFactory.getLogger(IngestaoLegislacaoService.class);

  private final VectorStore vectorStore;
  private final NamedParameterJdbcTemplate jdbc;

  /** Constrói o serviço sobre o {@code PgVectorStore} e o JDBC da mesma base. */
  public IngestaoLegislacaoService(VectorStore vectorStore, NamedParameterJdbcTemplate jdbc) {
    this.vectorStore = vectorStore;
    this.jdbc = jdbc;
  }

  /**
   * Ingere os dispositivos de uma norma.
   *
   * @param fonte        norma, link oficial, captura e vigência
   * @param dispositivos dispositivos fatiados do texto oficial
   * @return quantos entraram, quantos já estavam e quantas vigências foram fechadas
   */
  public ResumoIngestao ingerir(FonteLegislacao fonte, List<DispositivoLegal> dispositivos) {
    int novos = 0;
    int inalterados = 0;
    int fechadas = 0;
    for (int i = 0; i < dispositivos.size(); i += LOTE) {
      List<DispositivoLegal> lote = dispositivos.subList(i, Math.min(i + LOTE, dispositivos.size()));
      Map<String, DispositivoLegal> porHash = new HashMap<>();
      for (DispositivoLegal d : lote) {
        porHash.put(hash(fonte.norma(), d), d);
      }
      Set<String> existentes = hashesExistentes(porHash.keySet());

      List<Document> documentos = new ArrayList<>();
      for (Map.Entry<String, DispositivoLegal> e : porHash.entrySet()) {
        if (existentes.contains(e.getKey())) {
          inalterados++;
        } else {
          documentos.add(documento(fonte, e.getValue(), e.getKey()));
        }
      }
      if (!documentos.isEmpty()) {
        vectorStore.add(documentos);
        novos += documentos.size();
      }
      for (Map.Entry<String, DispositivoLegal> e : porHash.entrySet()) {
        fechadas += fecharRedacaoAnterior(fonte, e.getValue(), e.getKey());
      }
    }
    log.info("Ingestao de {}: {} dispositivos, {} novos, {} inalterados, {} vigencias fechadas",
        fonte.norma(), dispositivos.size(), novos, inalterados, fechadas);
    return new ResumoIngestao(dispositivos.size(), novos, inalterados, fechadas);
  }

  /**
   * Fecha, na véspera da vigência nova, a redação ainda aberta do mesmo dispositivo que tenha outro
   * texto e tenha começado antes. Só o {@code vigenciaFim} muda — nunca o texto.
   */
  private int fecharRedacaoAnterior(FonteLegislacao fonte, DispositivoLegal d, String hashAtual) {
    return jdbc.update("""
        UPDATE legislacao_trecho
           SET metadata = jsonb_set(metadata::jsonb, '{vigenciaFim}', to_jsonb(:fim::text))::json
         WHERE norma = :norma
           AND dispositivo = :dispositivo
           AND vigencia_fim = :aberta
           AND hash <> :hash
           AND vigencia_inicio < :inicio
        """, new MapSqlParameterSource()
        .addValue("fim", fonte.vigenciaInicio().minusDays(1).toString())
        .addValue("norma", fonte.norma())
        .addValue("dispositivo", d.dispositivo())
        .addValue("aberta", MetadadosTrecho.VIGENCIA_ABERTA)
        .addValue("hash", hashAtual)
        .addValue("inicio", fonte.vigenciaInicio().toString()));
  }

  private Set<String> hashesExistentes(Set<String> hashes) {
    if (hashes.isEmpty()) {
      return Set.of();
    }
    return new HashSet<>(jdbc.queryForList(
        "SELECT hash FROM legislacao_trecho WHERE hash IN (:hashes)",
        new MapSqlParameterSource("hashes", hashes), String.class));
  }

  private static Document documento(FonteLegislacao fonte, DispositivoLegal d, String hash) {
    Map<String, Object> metadados = new HashMap<>();
    metadados.put(MetadadosTrecho.NORMA, fonte.norma());
    metadados.put(MetadadosTrecho.DISPOSITIVO, d.dispositivo());
    metadados.put(MetadadosTrecho.URL, fonte.url());
    metadados.put(MetadadosTrecho.HASH, hash);
    metadados.put(MetadadosTrecho.VIGENCIA_INICIO, fonte.vigenciaInicio().toString());
    metadados.put(MetadadosTrecho.VIGENCIA_FIM, MetadadosTrecho.VIGENCIA_ABERTA);
    metadados.put(MetadadosTrecho.DATA_CAPTURA, fonte.dataCaptura().toString());
    // Id deterministico: o mesmo trecho sempre tem o mesmo id (o ON CONFLICT do PgVectorStore
    // nunca duplica).
    String id = UUID.nameUUIDFromBytes(hash.getBytes(StandardCharsets.UTF_8)).toString();
    return Document.builder().id(id).text(d.texto()).metadata(metadados).build();
  }

  /** SHA-256 hex de norma + dispositivo + texto. */
  static String hash(String norma, DispositivoLegal d) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      byte[] bytes = (norma + "\n" + d.dispositivo() + "\n" + d.texto())
          .getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(sha.digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 indisponivel", e);
    }
  }
}
