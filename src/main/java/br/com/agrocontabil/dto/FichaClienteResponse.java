package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.SituacaoVinculo;
import br.com.agrocontabil.model.TipoProdutor;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Ficha do cliente na carteira do escritório (issue #177). As observações são internas do
 * escritório: nenhuma rota do produtor as devolve.
 *
 * @param produtorId        produtor
 * @param nomeCompleto      nome
 * @param cpfCnpj           documento completo
 * @param tipoProdutor      PF ou PJ
 * @param regime            regime tributário
 * @param uf                UF
 * @param inscricaoEstadual inscrição estadual
 * @param situacaoVinculo   situação do vínculo
 * @param inicioVinculo     início do vínculo
 * @param observacoes       observações internas (até 1000 caracteres)
 * @param etiquetas         etiquetas, em ordem alfabética
 */
@Schema(description = "Ficha do cliente na carteira do escritório")
public record FichaClienteResponse(
    @Schema(description = "Id do produtor", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID produtorId,
    @Schema(description = "Nome completo ou razão social", example = "Fazenda Boa Vista")
    String nomeCompleto,
    @Schema(description = "Documento completo do cliente, só com dígitos")
    String cpfCnpj,
    @Schema(description = "Pessoa física ou jurídica", example = "PESSOA_FISICA")
    TipoProdutor tipoProdutor,
    @Schema(description = "Regime tributário do produtor")
    RegimeTributario regime,
    @Schema(description = "UF do produtor", example = "MT")
    String uf,
    @Schema(description = "Inscrição estadual, se houver")
    String inscricaoEstadual,
    @Schema(description = "Situação do vínculo com o escritório", example = "ATIVO")
    SituacaoVinculo situacaoVinculo,
    @Schema(description = "Início do vínculo com o escritório", example = "2026-09-01T10:00:00-03:00")
    OffsetDateTime inicioVinculo,
    @Schema(description = "Observações internas do escritório; nunca mostradas ao produtor")
    String observacoes,
    @Schema(description = "Etiquetas do cliente, em ordem alfabética")
    List<String> etiquetas) {

  @Override
  public String toString() {
    return "FichaClienteResponse[produtorId=" + produtorId + ", situacaoVinculo="
        + situacaoVinculo + "]";
  }
}
