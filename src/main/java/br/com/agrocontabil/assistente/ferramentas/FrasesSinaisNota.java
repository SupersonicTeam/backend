package br.com.agrocontabil.assistente.ferramentas;

import br.com.agrocontabil.assistente.porta.DadosProdutorPorta.Sinal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Frase fixa de cada sinal de nota a conferir (issue #276). Sempre "confira", nunca "está
 * errada": todo sinal é suspeita. Montada pelo backend e posta na resposta depois da validação
 * ({@code orquestracao/TextosDoBackend}): a descrição do item é texto livre do produtor e não vai
 * ao modelo (padrão da #305).
 */
public final class FrasesSinaisNota {

  private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
  private static final int DESCRICAO_MAXIMA = 60;

  private FrasesSinaisNota() {}

  /**
   * Texto dos sinais de uma nota; vazio sem sinal.
   *
   * @param numero número da nota
   * @param sinais sinais da nota
   * @return parágrafo pronto para o usuário, ou vazio
   */
  public static String texto(long numero, List<Sinal> sinais) {
    if (sinais.isEmpty()) {
      return "";
    }
    String frases = sinais.stream().map(s -> frase(numero, s))
        .collect(Collectors.joining(" "));
    LocalDate prazo = sinais.get(0).prazoCancelamento();
    if (prazo != null) {
      return frases + " A nota ja foi autorizada: se for preciso, da para cancela-la ate "
          + DATA.format(prazo) + ".";
    }
    return frases;
  }

  static String frase(long numero, Sinal s) {
    return switch (s.codigo()) {
      case "PRODUTO_PARECE_SERVICO" -> "Na nota " + numero + ", o item '"
          + curta(s.descricaoItem()) + "' " + motivoServico(s)
          + " Servico normalmente nao sai em nota de produto: confira com seu contador"
          + (s.prazoCancelamento() == null ? " antes de emitir." : ".");
      default -> "Na nota " + numero + ", ha um ponto para conferir com seu contador.";
    };
  }

  private static String motivoServico(Sinal s) {
    if (s.cfop() != null && (s.cfop().equals("5949") || s.cfop().equals("6949"))) {
      return "saiu com CFOP " + s.cfop().charAt(0) + "." + s.cfop().substring(1)
          + " (outra saida nao especificada) e parece um servico.";
    }
    return "saiu com um NCM fora da tabela de produtos agropecuarios e parece um servico.";
  }

  private static String curta(String descricao) {
    if (descricao == null || descricao.isBlank()) {
      return "sem descricao";
    }
    String d = descricao.strip().replace('\'', '"');
    return d.length() <= DESCRICAO_MAXIMA ? d : d.substring(0, DESCRICAO_MAXIMA).strip() + "...";
  }
}
