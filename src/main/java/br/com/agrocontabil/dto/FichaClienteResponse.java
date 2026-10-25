package br.com.agrocontabil.dto;

import br.com.agrocontabil.model.RegimeTributario;
import br.com.agrocontabil.model.SituacaoVinculo;
import br.com.agrocontabil.model.TipoProdutor;
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
public record FichaClienteResponse(
    UUID produtorId,
    String nomeCompleto,
    String cpfCnpj,
    TipoProdutor tipoProdutor,
    RegimeTributario regime,
    String uf,
    String inscricaoEstadual,
    SituacaoVinculo situacaoVinculo,
    OffsetDateTime inicioVinculo,
    String observacoes,
    List<String> etiquetas) {

  @Override
  public String toString() {
    return "FichaClienteResponse[produtorId=" + produtorId + ", situacaoVinculo="
        + situacaoVinculo + "]";
  }
}
