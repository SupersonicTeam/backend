package br.com.agrocontabil.assistente.orquestracao;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.rag.BuscaTextualLegislacaoService;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Resposta curada do atalho "O que muda para mim com o IBS e o CBS?" (#343): texto do backend com
 * citações fixas, sem passar pelo modelo — mesmo padrão do {@link FundamentoRetencao} (#145).
 *
 * <p>A pergunta é ampla demais para a busca por similaridade: os trechos mais próximos de "o que
 * muda para mim" não respondem a nada específico, e o modelo, com razão, não achava resposta neles
 * ({@code CITACAO_INVALIDA} na VPS em 2026-11-04). Aqui a resposta é correta por construção: cada
 * afirmação tem um dispositivo, e cada número é o do dispositivo. Os dispositivos foram
 * conferidos um a um na base de legislação (8.304 trechos, a mesma da VPS) em 2026-11-04;
 * {@link CitacoesFixas} avisa no log se algum sumir. Texto a revisar pela equipe a cada mudança.
 *
 * <p>Responde com o modelo fora do ar: zero chamadas ao modelo. Fica fora da cota da Ceres (o
 * {@code AssistenteService} não reserva o teto para atalho), então não gasta crédito.
 */
@Component
public class AtalhoReformaTributaria {

  /** Texto curado, em linguagem do produtor (sem acento, como os demais textos da Ceres). */
  public static final String TEXTO = String.join("\n",
      "O que muda para o produtor rural com o IBS e a CBS:",
      "- 2026 e um ano de teste. A CBS (contribuicao federal) e cobrada a 0,9% e o IBS (imposto de"
          + " estados e municipios) a 0,1%, so na parcela estadual (LC 214/2025, arts. 346 e 343;"
          + " EC 132/2023, art. 125 do ADCT). Quem cumpre as obrigacoes acessorias previstas na"
          + " legislacao fica dispensado de recolher esses valores em 2026 (LC 214/2025, art. 348,"
          + " § 1º).",
      "- Produto agropecuario in natura tem reducao de 60% nas aliquotas do IBS e da CBS"
          + " (LC 214/2025, art. 137).",
      "- O produtor rural com receita abaixo de R$ 3,6 milhoes no ano nao e contribuinte do IBS e"
          + " da CBS, a nao ser que opte pelo regime regular (LC 214/2025, arts. 164 e 165).",
      "- A lei preve o split payment: nos pagamentos eletronicos, a instituicao de pagamento separa"
          + " e recolhe o IBS e a CBS no momento da liquidacao (LC 214/2025, arts. 31 e 32).");

  /** Dispositivos citados, na ordem do texto ({@code norma|dispositivo}, como na base). */
  static final List<String> DISPOSITIVOS = List.of(
      "LC 214/2025|art. 346",
      "LC 214/2025|art. 343",
      "EC 132/2023|art. 2º (redação do art. 125)",
      "LC 214/2025|art. 348, § 1º",
      "LC 214/2025|art. 137",
      "LC 214/2025|art. 164",
      "LC 214/2025|art. 165",
      "LC 214/2025|art. 31",
      "LC 214/2025|art. 32");

  private final BuscaTextualLegislacaoService busca;

  /** Constrói sobre a busca por dispositivo exato. */
  public AtalhoReformaTributaria(BuscaTextualLegislacaoService busca) {
    this.busca = busca;
  }

  /**
   * A resposta do atalho: o texto curado e as citações com o texto da base (sem a base, o texto
   * segue sozinho: ele já nomeia cada dispositivo).
   *
   * @param contexto contexto da pergunta ("hoje" da vigência e trechos citados da auditoria)
   * @return resposta de legislação aceita, sem fontes de dados
   */
  public ResultadoOrquestracao responder(ContextoAssistente contexto) {
    return new ResultadoOrquestracao(Intencao.LEGISLACAO, TEXTO, DesfechoInteracao.RESPONDIDA,
        CitacoesFixas.buscar(busca, DISPOSITIVOS, contexto, "Atalho IBS/CBS"), false);
  }
}
