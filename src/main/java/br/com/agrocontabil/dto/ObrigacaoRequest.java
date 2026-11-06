package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.AjusteFimDeSemana;
import br.com.agrocontabil.model.Periodicidade;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.TipoProdutor;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Obrigação do catálogo do escritório (issue #181).
 *
 * @param titulo            título no calendário
 * @param descricao         descrição
 * @param periodicidade     MENSAL ou ANUAL
 * @param dia               dia do mês (1–31; inexistente no mês cai no último)
 * @param mes               mês (1–12), só na ANUAL
 * @param ajusteFimDeSemana NENHUM (padrão), ANTECIPA ou POSTERGA
 * @param tipoProdutor      PF, PJ ou nulo (todos)
 * @param regimes           regimes a que vale; vazio ou nulo = todos
 * @param origem            fundamento legal (texto livre)
 */
@Schema(description = "Obrigação do catálogo do escritório")
public record ObrigacaoRequest(
    @Schema(description = "Título no calendário (até 120 caracteres)", example = "DARF mensal")
    @NotBlank @Size(max = 120) String titulo,
    @Schema(description = "Descrição da obrigação (até 500 caracteres)")
    @Size(max = 500) String descricao,
    @Schema(description = "MENSAL ou ANUAL", example = "MENSAL")
    @NotNull Periodicidade periodicidade,
    @Schema(description = "Dia do mês (1 a 31; em mês sem esse dia cai no último)", example = "20")
    @NotNull @Min(1) @Max(31) Integer dia,
    @Schema(description = "Mês (1 a 12), só para obrigação anual")
    @Min(1) @Max(12) Integer mes,
    @Schema(description = "NENHUM (padrão), ANTECIPA ou POSTERGA", example = "NENHUM")
    AjusteFimDeSemana ajusteFimDeSemana,
    @Schema(description = "PF, PJ ou nulo para todos")
    TipoProdutor tipoProdutor,
    @Schema(description = "Regimes a que vale; vazio ou nulo para todos")
    @Size(max = 4) List<RegimeTributario> regimes,
    @Schema(description = "Fundamento legal, em texto livre", example = "Lei 8.212/1991")
    @Size(max = 200) String origem) {
}
