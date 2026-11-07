package br.com.agrocontabil.assistente;

import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.common.NormalizacaoBusca;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guarda da rota de lei contra resposta de regime específico para pergunta geral (issue #269).
 *
 * <p>O Título dos <em>regimes específicos do IBS e da CBS</em> da LC 214/2025 (arts. 172 a 307:
 * combustíveis, serviços financeiros, planos de saúde, prognósticos, bens imóveis, cooperativas,
 * bares e hotelaria, SAF, missões diplomáticas e as disposições comuns) tem regra própria de base,
 * fato gerador e crédito. Numa pergunta geral ("sobre qual valor se calcula o IBS e a CBS?") o modelo
 * pequeno cita esse regime como se fosse a regra geral, e a resposta sai afirmativa e errada
 * (diagnóstico das citações: D15, D16 e D25, em 4B e 30B).
 *
 * <p>A guarda barra a resposta quando <strong>todas</strong> as citações são da LC 214/2025 dentro
 * dessa faixa e a pergunta <strong>não cita o setor</strong> de nenhum dos capítulos citados. Basta
 * um artigo fora da faixa (a regra geral, o Imposto Seletivo, uma outra lei) para a resposta passar.
 * Os limites e os capítulos vêm do texto da lei (o título abre logo depois do art. 171 e fecha no
 * art. 307; o art. 308 abre os regimes diferenciados da CBS); as palavras do setor vêm do nome de
 * cada capítulo, e não de pergunta de avaliação. Função pura, sem Spring.
 */
final class GuardaRegimeEspecifico {

  /** Norma a que a faixa se aplica. */
  static final String NORMA = "LC 214/2025";

  /** Primeiro e último artigo do Título dos regimes específicos. */
  static final int PRIMEIRO_ARTIGO = 172;
  static final int ULTIMO_ARTIGO = 307;

  private static final Pattern ARTIGO = Pattern.compile("^art\\. (\\d+)");

  /** Capítulo do título, com as palavras que mostram que a pergunta é daquele setor. */
  record Capitulo(int primeiro, int ultimo, String nome, Pattern setor) {

    boolean contem(int artigo) {
      return artigo >= primeiro && artigo <= ultimo;
    }
  }

  /**
   * Capítulos do título e o vocabulário de cada setor: o nome do capítulo, os objetos que a lei
   * lista nele e as palavras do produtor rural para o mesmo assunto (propriedade, fazenda, terra e
   * arrendamento para bens imóveis; CPR, cédula, financiamento e empréstimo para serviços
   * financeiros). Palavra a mais só deixa passar uma resposta, nunca barra uma.
   */
  static final List<Capitulo> CAPITULOS = List.of(
      capitulo(172, 180, "combustíveis",
          "combust|gasolina|diesel|etanol|biodiesel|petroleo|gas natural|lubrificante|querosene|abastec"),
      capitulo(181, 233, "serviços financeiros",
          "financ|banc|segur|consorcio|previdencia|capitaliz|cambio|fundo|emprest|juro|cedula"
              + "|\\bcpr\\b|cartao|\\bpix\\b|credito rural|custeio|debenture"),
      capitulo(234, 243, "planos de assistência à saúde", "saude|medic|hospital|assistencia"),
      capitulo(244, 250, "concursos de prognósticos",
          "prognost|loteria|aposta|sorteio|bingo|rifa|premio"),
      capitulo(251, 270, "bens imóveis",
          "imovel|imoveis|imobiliar|terreno|\\blote|loteamento|\\bsolo\\b|incorporac|locac|alug"
              + "|arrend|cessao|constru|propriedade|fazenda|sitio|gleba|\\bterras?\\b|apartamento"
              + "|\\bcasa\\b"),
      capitulo(271, 272, "sociedades cooperativas", "cooperativ"),
      capitulo(273, 291, "bares, restaurantes, hotelaria, transporte e turismo",
          "\\bbar\\b|\\bbares\\b|restaurante|lanchonete|hotel|hospedagem|pousada|turism|viagem"
              + "|parque|transporte|passageiro"),
      capitulo(292, 296, "sociedade anônima do futebol", "futebol|\\bsaf\\b|clube"),
      capitulo(297, 299, "missões diplomáticas", "diplomat|consular|embaixada|tratado"),
      capitulo(300, 307, "disposições comuns aos regimes específicos",
          "regime especifico|regimes especificos"));

  private GuardaRegimeEspecifico() {}

  private static Capitulo capitulo(int primeiro, int ultimo, String nome, String setor) {
    return new Capitulo(primeiro, ultimo, nome, Pattern.compile(setor));
  }

  /** O capítulo do regime específico a que o dispositivo pertence, se pertence. */
  static Optional<Capitulo> capituloDe(String norma, String dispositivo) {
    if (!NORMA.equals(norma) || dispositivo == null) {
      return Optional.empty();
    }
    Matcher m = ARTIGO.matcher(dispositivo);
    if (!m.find()) {
      return Optional.empty();
    }
    int artigo = Integer.parseInt(m.group(1));
    return CAPITULOS.stream().filter(c -> c.contem(artigo)).findFirst();
  }

  /** {@code true} se o dispositivo é de regime específico (LC 214/2025, arts. 172 a 307). */
  static boolean emRegimeEspecifico(String norma, String dispositivo) {
    return capituloDe(norma, dispositivo).isPresent();
  }

  /**
   * Se a resposta deve ser barrada: todas as citações são de regime específico e a pergunta não cita
   * o setor de nenhum dos capítulos citados.
   *
   * @param pergunta  pergunta que a rota recebeu
   * @param citacoes  citações da resposta aceita
   * @return {@code true} para descartar
   */
  static boolean barra(String pergunta, List<CitacaoLegal> citacoes) {
    if (pergunta == null || citacoes == null || citacoes.isEmpty()) {
      return false;
    }
    String simples = NormalizacaoBusca.normalizar(pergunta);
    for (CitacaoLegal citacao : citacoes) {
      Optional<Capitulo> capitulo = capituloDe(citacao.norma(), citacao.dispositivo());
      if (capitulo.isEmpty() || capitulo.get().setor().matcher(simples).find()) {
        return false;
      }
    }
    return true;
  }
}
