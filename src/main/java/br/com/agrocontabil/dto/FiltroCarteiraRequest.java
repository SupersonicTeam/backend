package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.SituacaoVinculo;
import br.com.agrocontabil.model.TipoProdutor;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Filtros, ordenação e página de {@code GET /api/contador/produtores} (issue #177).
 *
 * <p>{@code busca} casa o nome por trecho (sem acento nem caixa) ou o CPF/CNPJ completo, com ou sem
 * máscara: o documento é cifrado de forma determinística (#147), então só por igualdade.
 */
@Schema(description = "Filtros, ordenação e página da carteira de clientes do escritório")
public record FiltroCarteiraRequest(
    @Schema(description = "Trecho do nome (3+ caracteres) ou CPF/CNPJ completo",
        example = "silva")
    @Size(max = 100)
    String busca,

    @Schema(description = "Regime tributario")
    RegimeTributario regime,

    @Schema(description = "PESSOA_FISICA ou PESSOA_JURIDICA")
    TipoProdutor tipoProdutor,

    @Schema(description = "UF do produtor", example = "MT")
    @Pattern(regexp = "[A-Za-z]{2}")
    String uf,

    @Schema(description = "Etiqueta (repetivel; OU entre elas)")
    List<@Size(max = 30) String> etiqueta,

    @Schema(description = "Situacao do vinculo (padrao ATIVO)")
    SituacaoVinculo situacao,

    @Schema(description = "So clientes com algum indicador de atencao")
    Boolean precisaAtencao,

    @Schema(description = "eu, nenhum ou o id de um membro do escritorio (issue #228)")
    @Size(max = 36)
    String responsavel,

    @Schema(description = "nome (padrao) ou urgencia")
    String sort,

    @Schema(description = "Pagina (padrao 0)")
    @Min(0) @Max(10_000)
    Integer page,

    @Schema(description = "Tamanho da pagina (padrao 50, maximo 200)")
    @Min(1) @Max(200)
    Integer size) {
}
