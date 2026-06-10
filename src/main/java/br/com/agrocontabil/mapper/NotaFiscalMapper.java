package br.com.agrocontabil.mapper;

import br.com.agrocontabil.dto.NotaFiscalItemRequest;
import br.com.agrocontabil.dto.NotaFiscalItemResponse;
import br.com.agrocontabil.dto.NotaFiscalRequest;
import br.com.agrocontabil.dto.NotaFiscalResponse;
import br.com.agrocontabil.dto.NotaFiscalResponse.EnderecoDestinatarioResponse;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Converte entre DTOs de nota fiscal e entidades JPA.
 *
 * <p>Regras de negocio do mapper:
 * <ul>
 *   <li>O modelo e fixado como {@code "55"} (NF-e) — nunca aceito do cliente.</li>
 *   <li>O status inicial e sempre {@link StatusNotaFiscal#RASCUNHO}.</li>
 *   <li>A serie padrao e {@code 1} quando nao informada.</li>
 *   <li>{@code valorTotal} de cada item = {@code quantidade * valorUnitario},
 *       arredondado HALF_UP em 2 casas decimais.</li>
 *   <li>{@code valorTotalProdutos} = soma dos valorTotal dos itens.</li>
 *   <li>{@code valorTotalNota} = {@code valorTotalProdutos} (sem impostos na Sprint 2).</li>
 * </ul>
 *
 * <p>Esta classe nao e um Spring bean — todos os metodos sao estaticos.
 * Isso mantém o mapper testavel sem contexto Spring.
 */
public final class NotaFiscalMapper {

  private NotaFiscalMapper() {}

  /**
   * Converte um {@link NotaFiscalRequest} em uma entidade {@link NotaFiscal} pronta para persistencia.
   *
   * <p>O produtor passado por parametro e o produtor autenticado; nunca vem do corpo da requisicao.
   *
   * @param request  dados de entrada validados
   * @param produtor produtor autenticado que sera o emissor da nota
   * @return entidade montada com itens, totais calculados e status RASCUNHO
   */
  public static NotaFiscal toEntity(NotaFiscalRequest request, Produtor produtor) {
    NotaFiscal nota = NotaFiscal.builder()
        .numero(request.numero())
        .serie(request.serie() != null ? request.serie() : 1)
        .modelo("55")
        .naturezaOperacao(request.naturezaOperacao())
        .dataEmissao(request.dataEmissao())
        .dataSaida(request.dataSaida())
        .produtor(produtor)
        .destinatarioNome(request.destinatarioNome())
        .destinatarioCpfCnpj(request.destinatarioCpfCnpj())
        .destinatarioIe(request.destinatarioIe())
        .destinatarioEndereco(toEnderecoEntity(request.destinatarioEndereco()))
        .status(StatusNotaFiscal.RASCUNHO)
        .valorTotalProdutos(BigDecimal.ZERO)
        .valorTotalNota(BigDecimal.ZERO)
        .build();

    List<NotaFiscalItem> itens = request.itens().stream()
        .map(NotaFiscalMapper::toItemEntity)
        .toList();

    itens.forEach(nota::addItem);

    BigDecimal totalProdutos = calcularTotalProdutos(nota.getItens());
    nota.setValorTotalProdutos(totalProdutos);
    nota.setValorTotalNota(totalProdutos);

    return nota;
  }

  /**
   * Converte uma entidade {@link NotaFiscal} em {@link NotaFiscalResponse}.
   *
   * @param nota entidade persistida
   * @return DTO de saida com todos os campos, incluindo itens mapeados
   */
  public static NotaFiscalResponse toResponse(NotaFiscal nota) {
    List<NotaFiscalItemResponse> itensResponse = nota.getItens().stream()
        .map(NotaFiscalMapper::toItemResponse)
        .toList();

    return new NotaFiscalResponse(
        nota.getId(),
        nota.getChaveAcesso(),
        nota.getNumero(),
        nota.getSerie(),
        nota.getModelo(),
        nota.getNaturezaOperacao(),
        nota.getStatus(),
        nota.getDataEmissao(),
        nota.getDataSaida(),
        nota.getProdutor().getId(),
        nota.getDestinatarioNome(),
        nota.getDestinatarioCpfCnpj(),
        nota.getDestinatarioIe(),
        toEnderecoResponse(nota.getDestinatarioEndereco()),
        nota.getValorTotalProdutos(),
        nota.getValorTotalNota(),
        itensResponse,
        nota.getCreatedAt(),
        nota.getUpdatedAt());
  }

  // -------------------------------------------------------------------------
  // Helpers internos e de uso restrito ao pacote mapper
  // -------------------------------------------------------------------------

  /**
   * Converte uma lista de {@link NotaFiscalItemRequest} em entidades {@link NotaFiscalItem}.
   *
   * <p>Destinado ao uso pela camada de servico quando e necessario substituir itens
   * de uma nota existente sem recriar a nota inteira (ex.: operacao de update).
   *
   * @param itens lista de DTOs de entrada dos itens, nao nula
   * @return lista de entidades de itens sem associacao com nota fiscal (sem ID)
   */
  public static List<NotaFiscalItem> toItemEntityList(List<NotaFiscalItemRequest> itens) {
    return itens.stream()
        .map(NotaFiscalMapper::toItemEntity)
        .toList();
  }

  /**
   * Converte um {@link NotaFiscalItemRequest} em entidade {@link NotaFiscalItem}.
   *
   * <p>Calcula {@code valorTotal = quantidade * valorUnitario} com arredondamento HALF_UP
   * em 2 casas decimais.
   *
   * @param req DTO de entrada do item
   * @return entidade do item sem associacao com nota fiscal (sem ID)
   */
  private static NotaFiscalItem toItemEntity(NotaFiscalItemRequest req) {
    BigDecimal valorTotal = req.quantidade()
        .multiply(req.valorUnitario())
        .setScale(2, RoundingMode.HALF_UP);

    return NotaFiscalItem.builder()
        .numeroItem(req.numeroItem())
        .codigoProduto(req.codigoProduto())
        .descricao(req.descricao())
        .ncm(req.ncm())
        .cfop(req.cfop())
        .unidade(req.unidade())
        .quantidade(req.quantidade())
        .valorUnitario(req.valorUnitario())
        .valorTotal(valorTotal)
        .build();
  }

  private static NotaFiscalItemResponse toItemResponse(NotaFiscalItem item) {
    return new NotaFiscalItemResponse(
        item.getId(),
        item.getNumeroItem(),
        item.getCodigoProduto(),
        item.getDescricao(),
        item.getNcm(),
        item.getCfop(),
        item.getUnidade(),
        item.getQuantidade(),
        item.getValorUnitario(),
        item.getValorTotal(),
        item.getCreatedAt(),
        item.getUpdatedAt());
  }

  private static EnderecoDestinatario toEnderecoEntity(
      NotaFiscalRequest.EnderecoDestinatarioRequest req) {
    if (req == null) {
      return null;
    }
    return EnderecoDestinatario.builder()
        .logradouro(req.logradouro())
        .numero(req.numero())
        .municipio(req.municipio())
        .codigoMunicipioIbge(req.codigoMunicipioIbge())
        .uf(req.uf())
        .cep(req.cep())
        .build();
  }

  private static EnderecoDestinatarioResponse toEnderecoResponse(EnderecoDestinatario endereco) {
    if (endereco == null) {
      return null;
    }
    return new EnderecoDestinatarioResponse(
        endereco.getLogradouro(),
        endereco.getNumero(),
        endereco.getMunicipio(),
        endereco.getCodigoMunicipioIbge(),
        endereco.getUf(),
        endereco.getCep());
  }

  private static BigDecimal calcularTotalProdutos(List<NotaFiscalItem> itens) {
    return itens.stream()
        .map(NotaFiscalItem::getValorTotal)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
