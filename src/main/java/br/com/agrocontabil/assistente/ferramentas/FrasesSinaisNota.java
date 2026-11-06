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
 * ao modelo (padrão da #305). Os valores dos sinais (preço, mediana, número da outra nota) também
 * só aparecem aqui. A frase explica pelo {@code motivo} e pelo {@code momento} que o
 * domínio mandou — a regra (quais CFOPs são genéricos, o prazo de 30 dias) fica no service.
 */
public final class FrasesSinaisNota {

  private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
  private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
  private static final int DESCRICAO_MAXIMA = 60;
  /** A NFS-e de padrão nacional: os Municípios autorizam o contribuinte a emiti-la (#364). */
  private static final String ARTIGO_NFSE = "LC 214/2025, art. 62, § 1º, inciso I";

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
    String nota = serie == null ? "Na nota " + numero : "Na nota " + numero + " (série " + serie
        + ")";
    String frases = sinais.stream().map(s -> frase(nota, s)).collect(Collectors.joining(" "));
    Sinal primeiro = sinais.get(0);
    return switch (primeiro.momento()) {
      case "AUTORIZADA_NO_PRAZO" -> frases + " A nota já foi autorizada: se for preciso, dá para"
          + " cancelá-la até " + FormatoBr.data(primeiro.prazoCancelamento()) + ", às "
          + HORA.format(primeiro.prazoCancelamento().atZoneSameInstant(BRASILIA))
          + " (horário de Brasília).";
      case "AUTORIZADA_FORA_DO_PRAZO" -> frases + " A nota já foi autorizada e o prazo de 30 dias"
          + " para cancelar passou: veja com seu contador como corrigir.";
      default -> frases;
    };
  }

  static String frase(String nota, Sinal s) {
    return switch (s.codigo()) {
      case "PRODUTO_PARECE_SERVICO" -> nota + ", o item '" + curta(s.descricaoItem()) + "' "
          + motivoServico(s) + " Serviço normalmente não sai em nota de produto (NF-e), e sim em"
          + " NFS-e de padrão nacional (" + ARTIGO_NFSE + "): confira com seu contador"
          + ("ANTES_DE_EMITIR".equals(s.momento()) ? " antes de emitir." : ".");
      case "VALOR_UNITARIO_FORA_PADRAO" -> nota + ", o preço do item '" + curta(s.descricaoItem())
          + "' (" + FormatoBr.precoUnitario(s.valor()) + " por " + unidade(s.unidade())
          + ") está bem " + ("ABAIXO_DO_PADRAO".equals(s.motivo()) ? "abaixo" : "acima")
          + " do que você costuma cobrar (perto de " + FormatoBr.precoUnitario(s.referencia())
          + " por " + unidade(s.unidade()) + "). Confira o valor e a unidade"
          + ("ANTES_DE_EMITIR".equals(s.momento()) ? " antes de emitir." : ".");
      case "POSSIVEL_DUPLICIDADE" -> nota.replaceFirst("^Na nota", "A nota")
          + " tem o mesmo destinatário, valor e itens da nota " + s.outraNota()
          + (s.outraSerie() == null ? "" : " (série " + s.outraSerie() + ")")
          + ", do mesmo dia. Confira se não é a mesma venda emitida duas vezes.";
      case "ICMS_PADRAO_A_CONFIRMAR" -> nota + ", o item '" + curta(s.descricaoItem()) + "' vai"
          + " sair com o ICMS diferido (CST 51), que o sistema aplica por padrão na venda dentro do"
          + " estado da sua produção. Esse tratamento só foi confirmado para a soja em grão em MT:"
          + " confira com seu contador o ICMS deste produto no seu estado antes de emitir.";
      case "CCLASSTRIB_NAO_ADMITIDO" -> nota + ", o item '" + curta(s.descricaoItem()) + "' tem"
          + " um cClassTrib que não vale para o NCM dele, então o imposto mostrado não vale na"
          + " emissão: corrija o código (ou retire-o do item) antes de emitir, senão a emissão é"
          + " barrada.";
      case "ICMS_A_DEFINIR" -> nota + ", o item '" + curta(s.descricaoItem()) + "' está sem o"
          + " CST do ICMS. O sistema só preenche sozinho na venda dentro do estado da sua"
          + " produção (CFOP 5.101) a quem tem inscrição estadual: combine o CST com seu contador"
          + " antes de emitir, senão a emissão é barrada.";
      default -> nota + ", há um ponto para conferir com seu contador.";
    };
  }

  private static String motivoServico(Sinal s) {
    if ("UNIDADE_DE_SERVICO".equals(s.motivo())) {
      return "saiu com unidade de serviço (" + unidade(s.unidade()) + ") e parece um serviço.";
    }
    if ("CFOP_GENERICO".equals(s.motivo()) && s.cfop() != null && s.cfop().strip().length() == 4) {
      String cfop = s.cfop().strip();
      return "saiu com CFOP " + cfop.charAt(0) + "." + cfop.substring(1)
          + " (outra saída não especificada) e parece um serviço.";
    }
    if ("CFOP_GENERICO".equals(s.motivo())) {
      return "saiu com CFOP de outra saída não especificada e parece um serviço.";
    }
    return "saiu com um NCM que não está na tabela de produtos agropecuários e parece um serviço.";
  }

  /** Unidade digitada pelo produtor, saneada como a descrição (sem controle nem aspas). */
  private static String unidade(String unidade) {
    return unidade == null || unidade.isBlank() ? "unidade" : curta(unidade);
  }

  /** Até 60 caracteres (por code point), sem quebra de linha nem caractere de controle. */
  static String curta(String descricao) {
    if (descricao == null || descricao.isBlank()) {
      return "sem descrição";
    }
    String d = descricao.replaceAll("[\\p{Cntrl}\\s]+", " ").strip().replace('\'', '"');
    if (d.codePointCount(0, d.length()) <= DESCRICAO_MAXIMA) {
      return d;
    }
    return d.substring(0, d.offsetByCodePoints(0, DESCRICAO_MAXIMA)).strip() + "...";
  }
}
