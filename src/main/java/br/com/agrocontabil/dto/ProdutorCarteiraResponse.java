package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.SituacaoVinculo;
import br.com.agrocontabil.model.TipoProdutor;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Cliente na lista da carteira do escritório (issue #177).
 *
 * <p>{@code atencao} só existe para vínculo {@code ATIVO}: suspenso ou encerrado, o escritório não
 * alcança os dados do produtor e nada deles aparece aqui. No {@code ENCERRADO} vem só o nome (o
 * histórico do escritório): documento, tipo, regime e UF são dados atuais de quem não é mais
 * cliente.
 *
 * @param vinculoId       vínculo (um produtor pode ter vários encerrados)
 * @param id              id do produtor (o {@code produtorId} das rotas do contador)
 * @param nomeCompleto    nome
 * @param cpfCnpj         documento completo (é o cliente do próprio escritório)
 * @param tipoProdutor    PF ou PJ
 * @param regime          regime tributário
 * @param uf              UF
 * @param situacaoVinculo situação do vínculo
 * @param inicioVinculo   início do vínculo
 * @param fimVinculo      fim (só encerrado)
 * @param responsavelId   membro do escritório responsável pelo cliente (#228; nulo = nenhum)
 * @param responsavelNome nome do responsável
 * @param etiquetas       etiquetas do escritório, em ordem alfabética
 * @param atencao         indicadores de atenção (nulo fora de {@code ATIVO})
 */
@Schema(description = "Cliente na lista da carteira do escritório")
public record ProdutorCarteiraResponse(
    @Schema(description = "Id do vínculo (um produtor pode ter vários encerrados)", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID vinculoId,
    @Schema(description = "Id do produtor, usado nas rotas do contador", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID id,
    @Schema(description = "Nome completo ou razão social", example = "Fazenda Boa Vista")
    String nomeCompleto,
    @Schema(description = "Documento completo do cliente, só com dígitos")
    String cpfCnpj,
    @Schema(description = "Pessoa física ou jurídica", example = "PESSOA_FISICA")
    TipoProdutor tipoProdutor,
    @Schema(description = "Regime tributário")
    RegimeTributario regime,
    @Schema(description = "UF do produtor", example = "MT")
    String uf,
    @Schema(description = "Situação do vínculo", example = "ATIVO")
    SituacaoVinculo situacaoVinculo,
    @Schema(description = "Início do vínculo", example = "2026-09-01T10:00:00-03:00")
    OffsetDateTime inicioVinculo,
    @Schema(description = "Fim do vínculo; só quando encerrado")
    OffsetDateTime fimVinculo,
    @Schema(description = "Membro responsável; nulo para nenhum", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID responsavelId,
    @Schema(description = "Nome do responsável", example = "Ana Souza")
    String responsavelNome,
    @Schema(description = "Etiquetas do escritório, em ordem alfabética")
    List<String> etiquetas,
    @Schema(description = "Indicadores de atenção; nulo fora do vínculo ativo")
    AtencaoCarteiraResponse atencao) {
}
