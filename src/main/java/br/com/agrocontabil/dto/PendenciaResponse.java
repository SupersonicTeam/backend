package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.StatusPendencia;
import br.com.agrocontabil.model.TipoDocumentoPendencia;
import br.com.agrocontabil.repository.AnexoResumo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Pedido de documento (pendência) com o seu estado")
public record PendenciaResponse(
    @Schema(description = "Id da pendência", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID id,
    @Schema(description = "Id do produtor", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID produtorId,
    @Schema(description = "Id do escritório que pediu", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID escritorioId,
    @Schema(description = "O que se pede", example = "Extrato bancário de outubro")
    String titulo,
    @Schema(description = "Detalhes do pedido")
    String descricao,
    @Schema(description = "Observações ao produtor")
    String observacoes,
    @Schema(description = "Prazo do pedido", example = "2026-11-30")
    LocalDate dataLimite,
    @Schema(description = "Tipos de documento aceitos", example = "EXTRATO")
    List<TipoDocumentoPendencia> tiposDocumento,
    @Schema(description = "PENDENTE, ENVIADA, CONCLUIDA ou CANCELADA", example = "PENDENTE")
    StatusPendencia status,
    @Schema(description = "Motivo da última rejeição ou do cancelamento")
    String motivo,
    @Schema(description = "Quantos arquivos foram enviados", example = "0")
    long quantidadeAnexos,
    @Schema(description = "Metadados dos arquivos enviados (sem o conteúdo)")
    List<AnexoResumo> anexos,
    @Schema(description = "Quando foi criada", example = "2026-09-01T10:00:00-03:00")
    OffsetDateTime createdAt,
    @Schema(description = "Quando mudou pela última vez", example = "2026-09-01T10:00:00-03:00")
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
