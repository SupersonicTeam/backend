package br.com.agrocontabil.mapper;

import br.com.agrocontabil.dto.ModeloPedidoRequest;
import br.com.agrocontabil.dto.ModeloPedidoResponse;
import br.com.agrocontabil.model.ModeloPedido;
import br.com.agrocontabil.model.TipoDocumentoPendencia;
import java.util.EnumSet;
import java.util.List;

/**
 * Converte entre os DTOs de modelo de pedido e a entidade {@link ModeloPedido} (issue #517).
 *
 * <p>Não é um Spring bean — métodos estáticos, mesmo padrão do {@link GuiaFiscalMapper}.
 */
public final class ModeloPedidoMapper {

  private ModeloPedidoMapper() {}

  /**
   * Copia o pedido para o modelo (criação ou edição). O título vai sem os espaços das pontas, e
   * texto em branco vira nulo; o título normalizado é calculado pela entidade ao gravar.
   *
   * @param modelo  modelo a preencher
   * @param request dados validados
   */
  public static void aplicar(ModeloPedido modelo, ModeloPedidoRequest request) {
    modelo.setTitulo(request.titulo().strip());
    modelo.setTituloNormalizado(ModeloPedido.normalizarTitulo(request.titulo()));
    modelo.setDescricao(textoOuNulo(request.descricao()));
    modelo.setTiposDocumento(EnumSet.copyOf(request.tiposDocumento()));
    modelo.setPrazoEmDias(request.prazoEmDias());
    modelo.setObservacoes(textoOuNulo(request.observacoes()));
  }

  /**
   * Converte o modelo para a resposta.
   *
   * @param modelo modelo gravado
   * @return o DTO, com os tipos na ordem do enum
   */
  public static ModeloPedidoResponse toResponse(ModeloPedido modelo) {
    List<TipoDocumentoPendencia> tipos = modelo.getTiposDocumento() == null
        ? List.of() : List.copyOf(modelo.getTiposDocumento());
    return new ModeloPedidoResponse(modelo.getId(), modelo.getTitulo(), modelo.getDescricao(),
        tipos, modelo.getPrazoEmDias(), modelo.getObservacoes());
  }

  private static String textoOuNulo(String texto) {
    return texto == null || texto.isBlank() ? null : texto;
  }
}
