package br.com.agrocontabil.dto;

import br.com.agrocontabil.common.Sensivel;
import br.com.agrocontabil.model.TipoDocumentoPendencia;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Modelo de pedido de documento do escritório (issue #517): as regras do {@link PendenciaRequest}
 * nos campos que ele preenche, mais o prazo em dias no lugar da data.
 *
 * <p>Os textos são do escritório e podem citar o cliente: {@link Sensivel} para o erro de tamanho
 * nunca os ecoar no {@code rejectedValue}, e fora do {@code toString}.
 *
 * @param titulo         o que se pede
 * @param descricao      detalhes
 * @param tiposDocumento tipos aceitos — lista, não string JSON
 * @param prazoEmDias    prazo sugerido, em dias a partir do pedido (opcional)
 * @param observacoes    observações ao produtor
 */
@Schema(description = "Modelo de pedido de documento do escritório")
public record ModeloPedidoRequest(
    @Schema(description = "O que se pede (até 120 caracteres); único no escritório, sem acento"
        + " nem caixa", example = "Extrato bancário do mês")
    @Sensivel @NotBlank @Size(max = 120) String titulo,
    @Schema(description = "Detalhes do pedido (até 1000 caracteres)",
        example = "Extrato de todas as contas do mês anterior")
    @Sensivel @Size(max = 1000) String descricao,
    @Schema(description = "Tipos de documento aceitos (de 1 a 5)", example = "[\"EXTRATO\"]")
    @NotNull @NotEmpty @Size(max = 5) List<@NotNull TipoDocumentoPendencia> tiposDocumento,
    @Schema(description = "Prazo sugerido, em dias a partir do pedido (de 1 a 365); vazio = sem"
        + " prazo", example = "10")
    @Min(1) @Max(365) Integer prazoEmDias,
    @Schema(description = "Observações ao produtor (até 1000 caracteres)",
        example = "Pode mandar em PDF")
    @Sensivel @Size(max = 1000) String observacoes) {

  @Override
  public String toString() {
    return "ModeloPedidoRequest[tiposDocumento=" + tiposDocumento + ", prazoEmDias=" + prazoEmDias
        + "]";
  }
}
