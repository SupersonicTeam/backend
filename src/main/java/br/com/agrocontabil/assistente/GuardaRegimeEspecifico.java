package br.com.agrocontabil.assistente;

import br.com.agrocontabil.assistente.rag.CasoEspecial;
import br.com.agrocontabil.assistente.rag.CitacaoLegal;
import br.com.agrocontabil.common.NormalizacaoBusca;
import java.util.List;
import java.util.Optional;

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
 *
 * <p>Desde a #436 a tabela dos capítulos é a {@link CasoEspecial#REGIMES_ESPECIFICOS}, a mesma da
 * partição da busca. A guarda continua olhando só as citações da própria LC 214/2025: a citação da
 * redação dada pela LC 227/2026 a um artigo da faixa não conta como regime específico aqui (na
 * partição, conta).
 */
final class GuardaRegimeEspecifico {

  /** Capítulos do título, com as palavras de cada setor (tabela compartilhada, #436). */
  static final List<CasoEspecial.Faixa> CAPITULOS = CasoEspecial.REGIMES_ESPECIFICOS;

  private GuardaRegimeEspecifico() {}

  /** O capítulo do regime específico a que o dispositivo pertence, se pertence. */
  static Optional<CasoEspecial.Faixa> capituloDe(String norma, String dispositivo) {
    return CasoEspecial.artigoDaLc214(norma, dispositivo)
        .flatMap(a -> CasoEspecial.faixaDe(CAPITULOS, a));
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
      Optional<CasoEspecial.Faixa> capitulo = capituloDe(citacao.norma(), citacao.dispositivo());
      if (capitulo.isEmpty() || capitulo.get().setorCitado(simples)) {
        return false;
      }
    }
    return true;
  }
}
