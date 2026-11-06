package br.com.agrocontabil.mapper;

import br.com.agrocontabil.dto.NotaFiscalItemRequest;
import br.com.agrocontabil.dto.AvisoNotaResponse;
import br.com.agrocontabil.dto.NotaCarteiraResponse;
import br.com.agrocontabil.repository.NotaDaCarteira;
import br.com.agrocontabil.dto.NotaFiscalItemResponse;
import br.com.agrocontabil.dto.NotaFiscalRequest;
import br.com.agrocontabil.dto.NotaFiscalResponse;
import br.com.agrocontabil.dto.NotaFiscalResponse.EnderecoDestinatarioResponse;
import br.com.agrocontabil.dto.NotaFiscalValidacaoResponse;
import br.com.agrocontabil.model.EnderecoDestinatario;
import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.NotaFiscalItem;
import br.com.agrocontabil.model.NotaFiscalValidacao;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.model.TipoDocumentoFiscal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Converte entre DTOs de nota fiscal e entidades JPA.
 *
 * <p>Regras de negócio do mapper:
 * <ul>
 *   <li>O modelo é fixado como {@code "55"} (NF-e) — nunca aceito do cliente.</li>
 *   <li>O status inicial é sempre {@link StatusNotaFiscal#RASCUNHO}.</li>
 *   <li>A série padrão é {@code 1} quando não informada.</li>
 *   <li>{@code valorTotal} de cada item = {@code quantidade * valorUnitario},
 *       arredondado HALF_UP em 2 casas decimais.</li>
 *   <li>{@code valorTotalProdutos} = soma dos valorTotal dos itens.</li>
 *   <li>{@code valorTotalNota} = {@code valorTotalProdutos} (sem impostos na Sprint 2).</li>
 *   <li>Campos IBS/CBS dos itens ({@code cstIbsCbs}, {@code cClassTrib},
 *       {@code percentualReducao}) são copiados do request quando presentes;
 *       os valores calculados ({@code valorIbs}, {@code valorCbs} etc.) são preenchidos
 *       pelo {@link br.com.agrocontabil.service.TributacaoService} — nunca aceitos
 *       do cliente (evita mass-assignment).</li>
 * </ul>
 *
 * <p>Esta classe não é um Spring bean — todos os métodos são estáticos.
 * Isso mantém o mapper testável sem contexto Spring.
 */
public final class NotaFiscalMapper {

  private NotaFiscalMapper() {}

  /**
   * Converte um {@link NotaFiscalRequest} em uma entidade {@link NotaFiscal} pronta para
   * persistência.
   *
   * <p>O produtor passado por parâmetro é o produtor autenticado; nunca vem do corpo da
   * requisição.
   *
   * @param request  dados de entrada validados
   * @param produtor produtor autenticado que será o emissor da nota
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
        .tipoDocumento(request.tipoDocumento() != null
            ? request.tipoDocumento() : TipoDocumentoFiscal.NFE)
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
   * @return DTO de saída com todos os campos, incluindo itens e campos IBS/CBS mapeados
   */
  public static NotaFiscalResponse toResponse(NotaFiscal nota) {
    return toResponse(nota, List.of());
  }

  /**
   * Converte a nota com os avisos não bloqueantes da gravação (issue #389).
   *
   * @param nota   entidade
   * @param avisos avisos de create/update (vazio nas demais respostas)
   * @return DTO de saída
   */
  public static NotaFiscalResponse toResponse(NotaFiscal nota, List<AvisoNotaResponse> avisos) {
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
        nota.getTipoDocumento(),
        nota.getOrigem(),
        nota.getEmitenteNome(),
        nota.getEmitenteCpfCnpj(),
        nota.getDataEmissao(),
        nota.getDataSaida(),
        nota.getProdutor().getId(),
        nota.getDestinatarioNome(),
        nota.getDestinatarioCpfCnpj(),
        nota.getDestinatarioIe(),
        toEnderecoResponse(nota.getDestinatarioEndereco()),
        nota.getValorTotalProdutos(),
        nota.getValorTotalNota(),
        nota.getValorTotalIbs(),
        nota.getValorTotalCbs(),
        itensResponse,
        nota.getCreatedAt(),
        nota.getUpdatedAt(),
        avisos);
  }

  /**
   * Converte a linha da listagem da carteira do escritório (issue #341).
   *
   * @param nota projeção lida do banco
   * @return resumo para a API
   */
  public static NotaCarteiraResponse toCarteiraResponse(NotaDaCarteira nota) {
    return new NotaCarteiraResponse(nota.id(), nota.produtorId(), nota.produtorNome(),
        nota.numero(), nota.serie(), nota.naturezaOperacao(), nota.status(),
        nota.tipoDocumento(), nota.dataEmissao(), nota.destinatarioNome(),
        nota.valorTotalProdutos(), nota.valorTotalNota(), nota.valorTotalIbs(),
        nota.valorTotalCbs(), nota.chaveAcesso(), nota.createdAt(), nota.updatedAt());
  }

  /**
   * Converte uma {@link NotaFiscalValidacao} em {@link NotaFiscalValidacaoResponse} (issue #22).
   *
   * @param validacao inconsistência de validação persistida
   * @return DTO de saída para o histórico de validações
   */
  public static NotaFiscalValidacaoResponse toValidacaoResponse(NotaFiscalValidacao validacao) {
    return new NotaFiscalValidacaoResponse(
        validacao.getLoteValidacao(),
        validacao.getCode(),
        validacao.getField(),
        validacao.getMessage(),
        validacao.getSuggestion(),
        validacao.getRejectedValue(),
        validacao.getExpectedValue(),
        validacao.getValidadoEm());
  }

  /**
   * Converte uma lista de {@link NotaFiscalValidacao} em DTOs de resposta, preservando a ordem
   * fornecida pela consulta (issue #22).
   *
   * @param validacoes inconsistências persistidas, já ordenadas pelo repositório
   * @return lista de DTOs de saída
   */
  public static List<NotaFiscalValidacaoResponse> toValidacaoResponseList(
      List<NotaFiscalValidacao> validacoes) {
    return validacoes.stream()
        .map(NotaFiscalMapper::toValidacaoResponse)
        .toList();
  }

  // -------------------------------------------------------------------------
  // Helpers internos e de uso restrito ao pacote mapper
  // -------------------------------------------------------------------------

  /**
   * Converte uma lista de {@link NotaFiscalItemRequest} em entidades {@link NotaFiscalItem}.
   *
   * <p>Destinado ao uso pela camada de serviço quando é necessário substituir itens
   * de uma nota existente sem recriar a nota inteira (ex.: operação de update).
   *
   * @param itens lista de DTOs de entrada dos itens, não nula
   * @return lista de entidades de itens sem associação com nota fiscal (sem ID)
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
   * em 2 casas decimais. Copia os campos classificatórios opcionais de IBS/CBS
   * ({@code cstIbsCbs}, {@code cClassTrib}, {@code percentualReducao}) e do ICMS
   * ({@code cstIcms}, {@code origemIcms}).
   * Os campos calculados (valorIbs, valorCbs etc.) são preenchidos posteriormente
   * pelo {@link br.com.agrocontabil.service.TributacaoService}.
   *
   * @param req DTO de entrada do item
   * @return entidade do item sem associação com nota fiscal (sem ID)
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
        .cstIbsCbs(req.cstIbsCbs())
        .cClassTrib(req.cClassTrib())
        .percentualReducao(req.percentualReducao())
        // Vazio (select sem opcao) = nao informado: o padrao ou o ICMS_A_DEFINIR decidem.
        .cstIcms(semVazio(req.cstIcms()))
        .origemIcms(semVazio(req.origemIcms()))
        .build();
  }

  private static String semVazio(String valor) {
    return valor == null || valor.isBlank() ? null : valor.strip();
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
        item.getCstIbsCbs(),
        item.getCClassTrib(),
        item.getPercentualReducao(),
        item.getPercentualReducaoAplicado(),
        item.getCClassTribAplicado(),
        item.getBaseCalculoIbsCbs(),
        item.getAliquotaIbs(),
        item.getValorIbs(),
        item.getAliquotaCbs(),
        item.getValorCbs(),
        item.getCstIcms(),
        item.getOrigemIcms(),
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
