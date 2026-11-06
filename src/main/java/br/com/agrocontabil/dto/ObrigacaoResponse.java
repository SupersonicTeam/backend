package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.AjusteFimDeSemana;
import br.com.agrocontabil.model.Periodicidade;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.TipoProdutor;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * Obrigação no catálogo efetivo do escritório (issue #181).
 *
 * @param id                id da linha (a do modelo, se o escritório não mexeu nele)
 * @param modeloId          modelo de origem (nulo na obrigação própria)
 * @param origemCatalogo    {@code MODELO} (sem personalização), {@code PERSONALIZADA} (versão
 *                          do escritório de um modelo) ou {@code PROPRIA}
 * @param titulo            título
 * @param descricao         descrição
 * @param periodicidade     periodicidade
 * @param dia               dia do mês
 * @param mes               mês (anual)
 * @param ajusteFimDeSemana ajuste do fim de semana
 * @param tipoProdutor      tipo de produtor (nulo = todos)
 * @param regimes           regimes (vazio = todos)
 * @param origem            fundamento legal
 * @param ativa             entra no calendário
 */
@Schema(description = "Obrigação no catálogo efetivo do escritório")
public record ObrigacaoResponse(
    @Schema(description = "Id da linha (a do modelo, se o escritório não mexeu nele)", example = "3f2b8c1e-5d4a-4b7e-9a1c-2e8f0d6b7a10")
    UUID id,
    @Schema(description = "Modelo de origem; nulo na obrigação própria")
    UUID modeloId,
    @Schema(description = "MODELO, PERSONALIZADA ou PROPRIA", example = "MODELO")
    String origemCatalogo,
    @Schema(description = "Título no calendário", example = "DARF mensal")
    String titulo,
    @Schema(description = "Descrição da obrigação")
    String descricao,
    @Schema(description = "Periodicidade da obrigação", example = "MENSAL")
    Periodicidade periodicidade,
    @Schema(description = "Dia do mês", example = "20")
    int dia,
    @Schema(description = "Mês, na obrigação anual")
    Integer mes,
    @Schema(description = "Ajuste quando o dia cai no fim de semana", example = "NENHUM")
    AjusteFimDeSemana ajusteFimDeSemana,
    @Schema(description = "Tipo de produtor a que vale; nulo para todos")
    TipoProdutor tipoProdutor,
    @Schema(description = "Regimes a que vale; vazio para todos")
    List<RegimeTributario> regimes,
    @Schema(description = "Fundamento legal", example = "Lei 8.212/1991")
    String origem,
    @Schema(description = "Se a obrigação entra no calendário", example = "true")
    boolean ativa) {
}
