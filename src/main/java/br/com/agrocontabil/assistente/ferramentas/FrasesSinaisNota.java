package br.com.agrocontabil.assistente.ferramentas;

import br.com.agrocontabil.assistente.porta.DadosProdutorPorta.Sinal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Frase fixa de cada sinal de nota a conferir (issue #276). Sempre "confira", nunca "está
 * errada": todo sinal é suspeita. Montada pelo backend e posta na resposta depois da validação
 * ({@code orquestracao/TextosDoBackend}): a descrição do item é texto livre do produtor e não vai
 * ao modelo (padrão da #305). A frase explica pelo {@code motivo} e pelo {@code momento} que o
 * domínio mandou — a regra (quais CFOPs são genéricos, o prazo de 30 dias) fica no service.
 */
public final class FrasesSinaisNota {

  private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
  private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
  private static final int DESCRICAO_MAXIMA = 60;

  private FrasesSinaisNota() {}

  /**
   * Texto dos sinais de uma nota; vazio sem sinal.
   *
   * @param numero número da nota
   * @param serie  série, para distinguir notas de mesmo número; nula quando o número é único
   * @param sinais sinais da nota
   * @return parágrafo pronto para o usuário, ou vazio
   */
  public static String texto(long numero, Integer serie, List<Sinal> sinais) {
    if (sinais.isEmpty()) {
      return "";
    }
    String nota = serie == null ? "Na nota " + numero : "Na nota " + numero + " (serie " + serie
        + ")";
    String frases = sinais.stream().map(s -> frase(nota, s)).collect(Collectors.joining(" "));
    Sinal primeiro = sinais.get(0);
    return switch (primeiro.momento()) {
      case "AUTORIZADA_NO_PRAZO" -> frases + " A nota ja foi autorizada: se for preciso, da para"
          + " cancela-la ate " + FormatoBr.data(primeiro.prazoCancelamento()) + ", as "
          + HORA.format(primeiro.prazoCancelamento().atZoneSameInstant(BRASILIA))
          + " (horario de Brasilia).";
      case "AUTORIZADA_FORA_DO_PRAZO" -> frases + " A nota ja foi autorizada e o prazo de 30 dias"
          + " para cancelar passou: veja com seu contador como corrigir.";
      default -> frases;
    };
  }

  static String frase(String nota, Sinal s) {
    return switch (s.codigo()) {
      case "PRODUTO_PARECE_SERVICO" -> nota + ", o item '" + curta(s.descricaoItem()) + "' "
          + motivoServico(s) + " Servico normalmente nao sai em nota de produto: confira com seu"
          + " contador" + ("ANTES_DE_EMITIR".equals(s.momento()) ? " antes de emitir." : ".");
      default -> nota + ", ha um ponto para conferir com seu contador.";
    };
  }

  private static String motivoServico(Sinal s) {
    if ("CFOP_GENERICO".equals(s.motivo()) && s.cfop() != null && s.cfop().strip().length() == 4) {
      String cfop = s.cfop().strip();
      return "saiu com CFOP " + cfop.charAt(0) + "." + cfop.substring(1)
          + " (outra saida nao especificada) e parece um servico.";
    }
    if ("CFOP_GENERICO".equals(s.motivo())) {
      return "saiu com CFOP de outra saida nao especificada e parece um servico.";
    }
    return "saiu com um NCM que nao esta na tabela de produtos agropecuarios e parece um servico.";
  }

  /** Até 60 caracteres (por code point), sem quebra de linha nem caractere de controle. */
  static String curta(String descricao) {
    if (descricao == null || descricao.isBlank()) {
      return "sem descricao";
    }
    String d = descricao.replaceAll("[\\p{Cntrl}\\s]+", " ").strip().replace('\'', '"');
    if (d.codePointCount(0, d.length()) <= DESCRICAO_MAXIMA) {
      return d;
    }
    return d.substring(0, d.offsetByCodePoints(0, DESCRICAO_MAXIMA)).strip() + "...";
  }
}
