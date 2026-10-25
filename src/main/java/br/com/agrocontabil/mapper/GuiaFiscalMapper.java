package br.com.agrocontabil.mapper;

import br.com.agrocontabil.dto.GuiaFiscalRequest;
import br.com.agrocontabil.dto.GuiaFiscalResponse;
import br.com.agrocontabil.dto.VencimentoFiscalResponse;
import br.com.agrocontabil.model.GuiaFiscal;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusGuiaFiscal;

/**
 * Converte entre DTOs de guia fiscal / vencimento do calendário e a entidade {@link GuiaFiscal}
 * (issue #74).
 *
 * <p>Esta classe não é um Spring bean — todos os métodos são estáticos, mesmo padrão do
 * {@link NotaFiscalMapper}.
 */
public final class GuiaFiscalMapper {

  private GuiaFiscalMapper() {}

  /**
   * Converte um {@link GuiaFiscalRequest} em uma entidade {@link GuiaFiscal} pronta para
   * persistência, com status inicial {@link StatusGuiaFiscal#PENDENTE}.
   *
   * @param request  dados de entrada validados
   * @param produtor produtor destinatário já resolvido (existência validada pelo service)
   * @return entidade montada
   */
  public static GuiaFiscal toEntity(GuiaFiscalRequest request, Produtor produtor) {
    return GuiaFiscal.builder()
        .produtor(produtor)
        .tipoGuia(request.tipoGuia())
        .dataVencimento(request.dataVencimento())
        .valor(request.valor())
        .observacoes(request.observacoes())
        .status(StatusGuiaFiscal.PENDENTE)
        .build();
  }

  /** Converte a entidade para o DTO de resposta, sem expor o {@link Produtor} completo. */
  public static GuiaFiscalResponse toResponse(GuiaFiscal guia) {
    return new GuiaFiscalResponse(
        guia.getId(),
        guia.getProdutor().getId(),
        guia.getTipoGuia().name(),
        guia.getDataVencimento(),
        guia.getValor(),
        guia.getObservacoes(),
        guia.getStatus().name(),
        guia.getDataPagamento(),
        guia.getCreatedAt());
  }

  /**
   * Converte a entidade para o item do calendário fiscal do produtor.
   *
   * <p>{@code tipo} é fixado como {@code "imposto"} — única fonte de vencimento modelada nesta
   * entrega. {@code status} é o valor real em minúsculas (pendente/paga→pago/vencida→vencido).
   */
  public static VencimentoFiscalResponse toVencimento(GuiaFiscal guia) {
    String titulo = "Guia " + guia.getTipoGuia().name();
    return new VencimentoFiscalResponse(
        guia.getId().toString(),
        titulo,
        guia.getObservacoes(),
        guia.getDataVencimento(),
        "imposto",
        toStatusVencimento(guia.getStatus()),
        guia.getValor(),
        titulo,
        null,
        null);
  }

  private static String toStatusVencimento(StatusGuiaFiscal status) {
    return switch (status) {
      case PAGA -> "pago";
      case VENCIDA -> "vencido";
      case PENDENTE -> "pendente";
    };
  }
}
