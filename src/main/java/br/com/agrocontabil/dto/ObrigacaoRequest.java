package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.AjusteFimDeSemana;
import br.com.agrocontabil.model.Periodicidade;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.TipoProdutor;
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
public record ObrigacaoRequest(
    @NotBlank @Size(max = 120) String titulo,
    @Size(max = 500) String descricao,
    @NotNull Periodicidade periodicidade,
    @NotNull @Min(1) @Max(31) Integer dia,
    @Min(1) @Max(12) Integer mes,
    AjusteFimDeSemana ajusteFimDeSemana,
    TipoProdutor tipoProdutor,
    @Size(max = 4) List<RegimeTributario> regimes,
    @Size(max = 200) String origem) {
}
