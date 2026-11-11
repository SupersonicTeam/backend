package br.com.agrocontabil.assistente;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Sinal de que um pedido já recusado merece a recusa firme (#494): pede dado de outra pessoa,
 * traz um documento ou identificador, ou tenta mexer no próprio sistema de um jeito que o
 * {@code FiltroInjecao} não pega.
 *
 * <p>Não decide rota: só escolhe o texto de uma pergunta que já foi recusada (fora de escopo, ou
 * sem função na rota de dados). Um falso positivo custa apenas um texto mais seco, então os sinais
 * são largos de propósito ("dele", "do produtor", "terceiro").
 *
 * <p>Fica no pacote {@code assistente}, e não em {@code orquestracao}, para a {@link RotaDados}
 * usá-lo sem criar ciclo entre os pacotes.
 */
public final class SinalRecusaFirme {

  /** Dado de outra pessoa ou de outra conta. */
  private static final Pattern OUTRA_PESSOA = Pattern.compile("\\b(outr[oa]s? (produtor\\w*"
      + "|pessoas?|usuari[oa]s?|clientes?|contas?|fazendas?|empresas?)|vizinh[oa]s?|terceir[oa]s?"
      + "|fulan[oa]|de alguem|d[oa] produtor|meu (pai|irmao|tio|primo|socio)"
      + "|minha (mae|irma|tia|prima|socia)|deles?|delas?)\\b");

  /** Imperativo dirigido ao sistema, junto do que ele guarda. */
  private static final Pattern ORDEM_AO_SISTEMA = Pattern.compile("\\b(repita|repete|revele"
      + "|revela|mostre|mostra|imprima)\\b");
  private static final Pattern O_QUE_O_SISTEMA_GUARDA = Pattern.compile("\\b(texto|regras"
      + "|instrucoes|prompt|configuracao|mensagem anterior)\\b");

  /** Troca de papel ou credencial: ataque que o filtro de injeção não pega. */
  private static final Pattern PAPEL_OU_CREDENCIAL = Pattern.compile("\\b(finja|aja como"
      + "|se passe por|faca de conta|administrador|senha|token)\\b");

  /** Identificador interno (o {@code X-Usuario-Id}, o id de uma nota). */
  private static final Pattern UUID = Pattern.compile(
      "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");

  /** Marcadores do {@link DataSanitizer} que são de pessoa (a chave de acesso e o CEP não). */
  private static final List<String> MARCADORES_DE_PESSOA =
      List.of("[CPF]", "[CNPJ]", "[EMAIL]", "[TELEFONE]");

  private SinalRecusaFirme() {}

  /**
   * {@code true} se a pergunta pede dado de outra pessoa, traz CPF, CNPJ, e-mail, telefone ou
   * UUID, ou dá ordem ao sistema.
   *
   * @param pergunta pergunta do usuário
   * @return se o pedido recusado merece a recusa firme
   */
  public static boolean presente(String pergunta) {
    if (pergunta == null || pergunta.isBlank()) {
      return false;
    }
    String texto = PeriodoDaPergunta.normalizar(pergunta);
    return OUTRA_PESSOA.matcher(texto).find()
        || (ORDEM_AO_SISTEMA.matcher(texto).find()
            && O_QUE_O_SISTEMA_GUARDA.matcher(texto).find())
        || PAPEL_OU_CREDENCIAL.matcher(texto).find()
        || UUID.matcher(pergunta).find()
        || temDadoDePessoa(pergunta);
  }

  /** O {@link DataSanitizer} achou CPF, CNPJ, e-mail ou telefone no texto cru. */
  private static boolean temDadoDePessoa(String pergunta) {
    String mascarada = DataSanitizer.mascarar(pergunta);
    if (mascarada.equals(pergunta)) {
      return false;
    }
    return MARCADORES_DE_PESSOA.stream()
        .anyMatch(m -> ocorrencias(mascarada, m) > ocorrencias(pergunta, m));
  }

  private static int ocorrencias(String texto, String trecho) {
    int n = 0;
    for (int i = texto.indexOf(trecho); i >= 0; i = texto.indexOf(trecho, i + trecho.length())) {
      n++;
    }
    return n;
  }
}
