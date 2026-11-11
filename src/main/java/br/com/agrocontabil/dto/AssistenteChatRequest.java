package br.com.agrocontabil.dto;

import br.com.agrocontabil.common.Sensivel;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Pergunta à Ceres, a assistente fiscal (issue #142).
 *
 * <p>O tamanho é limitado: além de custo e latência, pergunta longa é o veículo típico de prompt
 * injection elaborado (issue #144).
 *
 * <p>Continuidade (#499): {@code historico} traz até três trocas anteriores, cada uma igual ao
 * {@code turno} que o servidor devolveu, da mais antiga para a mais recente. O servidor só as usa
 * com {@code app.assistente.continuidade.habilitada}; a conferência é silenciosa (par adulterado,
 * de outro produtor ou vencido é ignorado, nunca 400).
 *
 * @param pergunta               texto da pergunta (1 a {@value #TAMANHO_MAXIMO} caracteres)
 * @param historico              trocas anteriores assinadas (opcional, até três)
 * @param ultimaRespostaSemTurno {@code true} quando a última mensagem da Ceres na tela não trouxe
 *                               {@code turno} (recusa, conversa leve, fallback, erro)
 */
@Schema(description = "Pergunta a Ceres, a assistente fiscal")
public record AssistenteChatRequest(

    @Schema(description = "Pergunta em linguagem natural",
        example = "Quanto comprei de fertilizante no primeiro semestre?")
    @NotBlank(message = "A pergunta é obrigatória.")
    @Size(max = AssistenteChatRequest.TAMANHO_MAXIMO,
        message = "A pergunta deve ter no máximo " + AssistenteChatRequest.TAMANHO_MAXIMO
            + " caracteres.")
    String pergunta,

    @Schema(description = "Até 3 trocas anteriores, cada uma igual ao turno devolvido pela Ceres, "
        + "da mais antiga para a mais recente (opcional)")
    @Sensivel
    @JsonDeserialize(using = HistoricoConversaDeserializer.class)
    List<@NotNull(message = "Item do histórico vazio.") @Valid TurnoConversa> historico,

    @Schema(description = "true quando a última mensagem da Ceres na tela não trouxe turno "
        + "(padrão false)")
    Boolean ultimaRespostaSemTurno) {

  /** Tamanho máximo da pergunta, em caracteres. */
  public static final int TAMANHO_MAXIMO = 1000;

  /**
   * Pergunta sem histórico, como antes da #499.
   *
   * @param pergunta texto da pergunta
   */
  public AssistenteChatRequest(String pergunta) {
    this(pergunta, null, null);
  }

  /** O histórico recebido, nunca nulo. */
  public List<TurnoConversa> historicoOuVazio() {
    return historico == null ? List.of() : historico;
  }

  /** {@code true} se a última mensagem da Ceres na tela não trouxe {@code turno}. */
  public boolean semTurnoNaUltima() {
    return Boolean.TRUE.equals(ultimaRespostaSemTurno);
  }

  /** Sem o texto: a requisição pode acabar em log por engano. */
  @Override
  public String toString() {
    return "AssistenteChatRequest[pergunta=" + (pergunta == null ? 0 : pergunta.length())
        + " caracteres, historico=" + historicoOuVazio().size() + "]";
  }
}
