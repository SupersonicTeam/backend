package br.com.agrocontabil.dto;

import br.com.agrocontabil.common.Sensivel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Uma troca com a Ceres, assinada pelo servidor (#499): a pergunta considerada, a resposta
 * entregue e a assinatura que prende as duas ao produtor e ao que a Ceres consultou.
 *
 * <p>O servidor devolve o {@code turno} na resposta e o recebe de volta no {@code historico} da
 * pergunta seguinte, sem mudar nada. Nada é gravado no servidor: a assinatura é a única memória, e
 * par adulterado, de outro produtor ou vencido é ignorado em silêncio.
 *
 * @param pergunta   pergunta considerada (1 a {@value #TAMANHO_PERGUNTA} caracteres)
 * @param resposta   resposta entregue, inteira (1 a {@value #TAMANHO_RESPOSTA} caracteres)
 * @param assinatura assinatura do servidor ({@code v1.<carga>.<mac>}, até
 *                   {@value #TAMANHO_ASSINATURA} caracteres)
 */
@Schema(description = "Troca anterior com a Ceres, assinada pelo servidor (devolva sem mudar)")
public record TurnoConversa(

    @Schema(description = "Pergunta considerada pela Ceres")
    @Sensivel
    @NotBlank(message = "Pergunta anterior vazia.")
    @Size(max = TurnoConversa.TAMANHO_PERGUNTA,
        message = "Cada pergunta anterior deve ter no máximo " + TurnoConversa.TAMANHO_PERGUNTA
            + " caracteres.")
    String pergunta,

    @Schema(description = "Resposta entregue pela Ceres, inteira")
    @Sensivel
    @NotBlank(message = "Resposta anterior vazia.")
    @Size(max = TurnoConversa.TAMANHO_RESPOSTA,
        message = "Cada resposta anterior deve ter no máximo " + TurnoConversa.TAMANHO_RESPOSTA
            + " caracteres.")
    String resposta,

    @Schema(description = "Assinatura do servidor")
    @Sensivel
    @NotBlank(message = "Assinatura do histórico ausente ou longa demais.")
    @Size(max = TurnoConversa.TAMANHO_ASSINATURA,
        message = "Assinatura do histórico ausente ou longa demais.")
    String assinatura) {

  /** Tamanho máximo da pergunta de uma troca anterior. */
  public static final int TAMANHO_PERGUNTA = 1000;

  /** Tamanho máximo da resposta de uma troca anterior (a entregue é cortada acima disso). */
  public static final int TAMANHO_RESPOSTA = 6000;

  /** Tamanho máximo da assinatura. */
  public static final int TAMANHO_ASSINATURA = 2048;

  /** Sem o texto nem a assinatura: o turno pode acabar em log por engano. */
  @Override
  public String toString() {
    return "TurnoConversa[pergunta=" + tamanho(pergunta) + " caracteres, resposta="
        + tamanho(resposta) + " caracteres]";
  }

  private static int tamanho(String texto) {
    return texto == null ? 0 : texto.length();
  }
}
