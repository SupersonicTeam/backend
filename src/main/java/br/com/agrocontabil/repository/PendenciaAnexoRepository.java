package br.com.agrocontabil.repository;

import br.com.agrocontabil.model.PendenciaAnexo;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Anexos das pendências (issue #180). As listagens usam {@link AnexoResumo} — só metadados, sem
 * o conteúdo do arquivo. O anexo {@code INFECTADO} (#227) fica fora das listas, da contagem e do
 * teto: é registro de segurança (hash e assinatura), sem arquivo.
 */
public interface PendenciaAnexoRepository extends JpaRepository<PendenciaAnexo, UUID> {

  /**
   * Metadados dos anexos visíveis da pendência (limpos e em verificação), do mais recente ao
   * mais antigo.
   *
   * @param pendenciaId pendência
   * @return anexos sem o conteúdo
   */
  @Query("SELECT new br.com.agrocontabil.repository.AnexoResumo("
      + "a.id, a.nomeArquivo, a.mime, a.tamanho, a.sha256, a.situacao, a.enviadoEm)"
      + " FROM PendenciaAnexo a WHERE a.pendenciaId = :pendenciaId"
      + " AND a.situacao <> br.com.agrocontabil.model.SituacaoAnexo.INFECTADO"
      + " ORDER BY a.enviadoEm DESC, a.id")
  List<AnexoResumo> findResumos(@Param("pendenciaId") UUID pendenciaId);

  /**
   * Quantos anexos visíveis a pendência tem (teto de 10; o infectado não ocupa vaga).
   *
   * @param pendenciaId pendência
   * @return quantidade
   */
  @Query("SELECT COUNT(a) FROM PendenciaAnexo a WHERE a.pendenciaId = :pendenciaId"
      + " AND a.situacao <> br.com.agrocontabil.model.SituacaoAnexo.INFECTADO")
  long contarVisiveis(@Param("pendenciaId") UUID pendenciaId);

  /**
   * Anexos à espera do antivírus (#227): primeiro os nunca tentados, depois os tentados há mais
   * tempo — um anexo que o clamd recusa sempre vai para o fim da fila.
   *
   * @param pagina tamanho do lote
   * @return ids
   */
  @Query("SELECT a.id FROM PendenciaAnexo a"
      + " WHERE a.situacao = br.com.agrocontabil.model.SituacaoAnexo.EM_VERIFICACAO"
      + " ORDER BY a.verificadoEm ASC NULLS FIRST, a.enviadoEm ASC, a.id")
  List<UUID> idsEmVerificacao(Pageable pagina);

  /**
   * O anexo ainda em verificação, com o arquivo (job do antivírus).
   *
   * @param id anexo
   * @return o anexo, se ainda estiver em verificação
   */
  @Query("SELECT new br.com.agrocontabil.repository.AnexoEmVerificacao("
      + "a.id, a.pendenciaId, a.enviadoPor, a.conteudo) FROM PendenciaAnexo a WHERE a.id = :id"
      + " AND a.situacao = br.com.agrocontabil.model.SituacaoAnexo.EM_VERIFICACAO")
  Optional<AnexoEmVerificacao> findEmVerificacao(@Param("id") UUID id);

  /**
   * Anota a tentativa sem veredito (o anexo vai para o fim da fila).
   *
   * @param id    anexo
   * @param agora instante da tentativa
   * @return linhas alteradas
   */
  @Modifying
  @Query("UPDATE PendenciaAnexo a SET a.verificadoEm = :agora WHERE a.id = :id"
      + " AND a.situacao = br.com.agrocontabil.model.SituacaoAnexo.EM_VERIFICACAO")
  int registrarTentativa(@Param("id") UUID id, @Param("agora") OffsetDateTime agora);

  /**
   * Libera o anexo verificado sem ameaça.
   *
   * @param id    anexo
   * @param agora instante do veredito
   * @return 1 se ainda estava em verificação
   */
  @Modifying
  @Query("UPDATE PendenciaAnexo a SET a.situacao = br.com.agrocontabil.model.SituacaoAnexo.LIMPO,"
      + " a.verificadoEm = :agora WHERE a.id = :id"
      + " AND a.situacao = br.com.agrocontabil.model.SituacaoAnexo.EM_VERIFICACAO")
  int marcarLimpo(@Param("id") UUID id, @Param("agora") OffsetDateTime agora);

  /**
   * Marca a ameaça e descarta o conteúdo, no mesmo comando.
   *
   * @param id         anexo
   * @param assinatura nome da assinatura
   * @param agora      instante do veredito
   * @return 1 se ainda estava em verificação
   */
  @Modifying
  @Query("UPDATE PendenciaAnexo a"
      + " SET a.situacao = br.com.agrocontabil.model.SituacaoAnexo.INFECTADO,"
      + " a.conteudo = NULL, a.assinaturaVirus = :assinatura, a.verificadoEm = :agora"
      + " WHERE a.id = :id"
      + " AND a.situacao = br.com.agrocontabil.model.SituacaoAnexo.EM_VERIFICACAO")
  int marcarInfectado(@Param("id") UUID id, @Param("assinatura") String assinatura,
      @Param("agora") OffsetDateTime agora);

  /**
   * Anexo da pendência (download).
   *
   * @param id          anexo
   * @param pendenciaId pendência
   * @return o anexo, com o conteúdo
   */
  Optional<PendenciaAnexo> findByIdAndPendenciaId(UUID id, UUID pendenciaId);

  /**
   * Quantidade de anexos por pendência, numa consulta (listas, sem N+1).
   *
   * @param pendencias pendências
   * @return contagens (pendência sem anexo não aparece)
   */
  @Query("SELECT new br.com.agrocontabil.repository.ContagemPorPendencia(a.pendenciaId, COUNT(a))"
      + " FROM PendenciaAnexo a WHERE a.pendenciaId IN :pendencias"
      + " AND a.situacao <> br.com.agrocontabil.model.SituacaoAnexo.INFECTADO"
      + " GROUP BY a.pendenciaId")
  List<ContagemPorPendencia> contarPorPendencia(
      @Param("pendencias") Collection<UUID> pendencias);

  /**
   * Apaga os anexos enviados antes do corte (retenção de 5 anos).
   *
   * @param corte instante de corte
   * @return linhas removidas
   */
  @Modifying
  @Query("DELETE FROM PendenciaAnexo a WHERE a.enviadoEm < :corte")
  int apagarAnterioresA(@Param("corte") OffsetDateTime corte);

  /**
   * Apaga os anexos das pendências do produtor (exclusão LGPD).
   *
   * @param produtorId produtor
   * @return linhas removidas
   */
  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM PendenciaAnexo a WHERE a.pendenciaId IN"
      + " (SELECT p.id FROM Pendencia p WHERE p.produtorId = :produtorId)")
  int apagarDoProdutor(@Param("produtorId") UUID produtorId);

  /**
   * Apaga os anexos das pendências do escritório (o escritório sai).
   *
   * @param escritorioId escritório
   * @return linhas removidas
   */
  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM PendenciaAnexo a WHERE a.pendenciaId IN"
      + " (SELECT p.id FROM Pendencia p WHERE p.escritorioId = :escritorioId)")
  int apagarDoEscritorio(@Param("escritorioId") UUID escritorioId);
}
