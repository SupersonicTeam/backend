package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.StatusPendencia;
import br.com.agrocontabil.model.TipoDocumentoPendencia;
import br.com.agrocontabil.repository.AnexoResumo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Pendência (pedido de documento) — issue #180. {@code anexos} vem só no detalhe e depois de
 * anexar; nas listas vem vazio, com o total em {@code quantidadeAnexos}.
 *
 * @param id               pendência
 * @param produtorId       produtor
 * @param escritorioId     escritório que pediu
 * @param titulo           o que se pede
 * @param descricao        detalhes
 * @param observacoes      observações ao produtor
 * @param dataLimite       prazo
 * @param tiposDocumento   tipos aceitos
 * @param status           PENDENTE, ENVIADA, CONCLUIDA ou CANCELADA
 * @param motivo           motivo da última rejeição ou do cancelamento
 * @param quantidadeAnexos quantos arquivos foram enviados
 * @param anexos           metadados dos arquivos (sem o conteúdo)
 * @param createdAt        criação
 * @param updatedAt        última mudança
 */
public record PendenciaResponse(
    UUID id,
    UUID produtorId,
    UUID escritorioId,
    String titulo,
    String descricao,
    String observacoes,
    LocalDate dataLimite,
    List<TipoDocumentoPendencia> tiposDocumento,
    StatusPendencia status,
    String motivo,
    long quantidadeAnexos,
    List<AnexoResumo> anexos,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) implements ResultadoAuditavel {

  @JsonIgnore
  @Override
  public UUID recursoAuditado() {
    return id;
  }

  @JsonIgnore
  @Override
  public UUID produtorAuditado() {
    return produtorId;
  }
}
