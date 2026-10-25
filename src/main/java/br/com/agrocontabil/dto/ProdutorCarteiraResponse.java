package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.SituacaoVinculo;
import br.com.agrocontabil.model.TipoProdutor;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Cliente na lista da carteira do escritório (issue #177).
 *
 * <p>{@code atencao} só existe para vínculo {@code ATIVO}: suspenso ou encerrado, o escritório não
 * alcança os dados do produtor e nada deles aparece aqui.
 *
 * @param id              id do produtor (o {@code produtorId} das rotas do contador)
 * @param nomeCompleto    nome
 * @param cpfCnpj         documento completo (é o cliente do próprio escritório)
 * @param tipoProdutor    PF ou PJ
 * @param regime          regime tributário
 * @param uf              UF
 * @param situacaoVinculo situação do vínculo
 * @param inicioVinculo   início do vínculo
 * @param fimVinculo      fim (só encerrado)
 * @param etiquetas       etiquetas do escritório, em ordem alfabética
 * @param atencao         indicadores de atenção (nulo fora de {@code ATIVO})
 */
public record ProdutorCarteiraResponse(
    UUID id,
    String nomeCompleto,
    String cpfCnpj,
    TipoProdutor tipoProdutor,
    RegimeTributario regime,
    String uf,
    SituacaoVinculo situacaoVinculo,
    OffsetDateTime inicioVinculo,
    OffsetDateTime fimVinculo,
    List<String> etiquetas,
    AtencaoCarteiraResponse atencao) {
}
