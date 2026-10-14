package br.com.agrocontabil.assistente;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Confere que a parte de legislação do MISTO não fala dos dados do produtor (issue #170).
 *
 * <p>No MISTO, a rota de legislação vê só trechos de lei, nunca as notas. Quando ela escreve "os
 * fertilizantes que você comprou têm direito à redução", afirma algo que não viu — e o
 * {@link ValidadorNumeros} não pega, porque não há número inventado. O prompt pede a regra em
 * terceira pessoa; este componente <strong>verifica</strong>: qualquer referência ao usuário ou ao
 * que ele tem, comprou ou vendeu derruba o texto do modelo, e a parte de lei fica só com os
 * trechos citados.
 *
 * <p>É de propósito mais estrito que o necessário: uma regra legal se escreve sem "você", e o
 * custo de um falso positivo é mostrar os trechos em vez de um resumo — nunca uma afirmação falsa.
 */
@Component
public class VerificadorAfirmacaoDados {

  private static final List<Pattern> REFERENCIAS_AO_USUARIO = List.of(
      // Segunda pessoa, inclusive a de tratamento.
      Pattern.compile("\\b(voce|voces|vc|o senhor|a senhora)\\b"),
      // Posse do que só os dados mostram ("seus insumos", "no seu caso"). "Seu/sua" sozinho não:
      // o texto legal usa a terceira pessoa ("a pessoa juridica e suas filiais").
      Pattern.compile("\\b(seu|sua|seus|suas|teu|tua|teus|tuas)\\s+(compras?|vendas?|notas?|"
          + "insumos?|fertilizantes?|defensivos?|sementes?|produtos?|operacao|operacoes|"
          + "creditos?|guias?|aquisicao|aquisicoes|mercadorias?|dados|apuracao|fazenda|"
          + "propriedade|empresa|impostos?|tributos?|saldo|debitos?|caso|situacao)\\b"),
      // Ações do usuário no passado, que só os dados poderiam confirmar.
      Pattern.compile("\\b(comprou|vendeu|adquiriu|pagou|emitiu|recebeu|comprados por|vendidos por)"
          + "\\b"));

  private static final Pattern DIACRITICOS = Pattern.compile("\\p{M}+");

  /**
   * {@code true} se o texto se refere ao usuário ou aos dados dele.
   *
   * @param texto resposta da rota de legislação (com as referências já montadas)
   */
  public boolean afirmaSobreDados(String texto) {
    String normalizado = DIACRITICOS.matcher(
        Normalizer.normalize(texto == null ? "" : texto, Normalizer.Form.NFD)).replaceAll("")
        .toLowerCase(Locale.ROOT);
    return REFERENCIAS_AO_USUARIO.stream().anyMatch(p -> p.matcher(normalizado).find());
  }
}
