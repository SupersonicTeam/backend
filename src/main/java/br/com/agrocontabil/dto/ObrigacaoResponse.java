package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.AjusteFimDeSemana;
import br.com.agrocontabil.model.Periodicidade;
import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.TipoProdutor;
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
public record ObrigacaoResponse(
    UUID id,
    UUID modeloId,
    String origemCatalogo,
    String titulo,
    String descricao,
    Periodicidade periodicidade,
    int dia,
    Integer mes,
    AjusteFimDeSemana ajusteFimDeSemana,
    TipoProdutor tipoProdutor,
    List<RegimeTributario> regimes,
    String origem,
    boolean ativa) {
}
