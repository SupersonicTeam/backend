package br.com.agrocontabil.assistente.orquestracao;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.SinalRecusaFirme;
import br.com.agrocontabil.assistente.llm.DisponibilidadeModelo;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.exception.AssistenteIndisponivelException;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Classificador de intenção da Fase 2 (issue #265), em camadas, da mais barata para a mais cara:
 * <ol>
 *   <li>{@link RegrasClassificacao}: termo inequívoco de um lado só;</li>
 *   <li>{@link EmbeddingClassificadorIntencao}: kNN no conjunto de treino, se a confiança passar
 *       do limiar ({@code app.assistente.classificacao.limiar-confianca}, 0,9: só o voto quase
 *       unânime; com o modelo em pausa, {@code limiar-confianca-sem-modelo}, 0,6). Com o modelo
 *       no ar, o voto MISTO segue a rota do modelo, que já é chamado para a pergunta de lei, e o
 *       voto LEGISLACAO numa pergunta com dado ou fato do produtor vai ao desempate (#497);</li>
 *   <li>{@link LlmClassificadorIntencao}: desempate, só quando as anteriores não decidiram. Assim a
 *       qualidade nunca fica abaixo da Fase 1.</li>
 * </ol>
 * O {@link FiltroInjecao} e o pedido inequívoco de outra conta ou ao sistema
 * ({@link SinalRecusaFirme#inequivoco}) continuam antes de tudo, no orquestrador. Com marca de
 * outra pessoa ou de ordem ao sistema ({@link SinalRecusaFirme#pedeDesempate}, #497), as camadas 1
 * e 2 não decidem: o modelo desempata, se estiver no ar.
 *
 * <p><strong>MISTO:</strong> as camadas 1 e 2 decidem só a rota; a separação da pergunta de lei
 * ({@code perguntaLei}) continua com o modelo, que passa a ser chamado só aí. Se ele não separar
 * (ou estiver fora), a parte de lei recebe a pergunta inteira, como já acontecia.
 *
 * <p><strong>Sem o modelo</strong> (disjuntor aberto): regras e embeddings seguem decidindo; sem
 * decisão delas, {@link AssistenteIndisponivelException}, e o orquestrador cai nas palavras-chave do
 * modo fallback — como antes. Com {@code app.assistente.classificacao.camadas=false}, toda
 * pergunta vai ao modelo (Fase 1).
 */
@Primary
@Component
public class ClassificadorEmCamadas implements ClassificadorIntencao {

  private final LlmClassificadorIntencao llm;
  private final EmbeddingClassificadorIntencao embeddings;
  private final DisponibilidadeModelo disponibilidade;
  private final AssistenteProperties.Classificacao propriedades;
  private final double confiancaMinima;

  /** Constrói sobre as três camadas. */
  public ClassificadorEmCamadas(LlmClassificadorIntencao llm,
      EmbeddingClassificadorIntencao embeddings, DisponibilidadeModelo disponibilidade,
      AssistenteProperties propriedades) {
    this.llm = llm;
    this.embeddings = embeddings;
    this.disponibilidade = disponibilidade;
    this.propriedades = propriedades.getClassificacao();
    this.confiancaMinima = propriedades.getOrquestracao().getConfiancaMinima();
  }

  @Override
  public ClassificacaoIntencao classificar(String pergunta, ContextoAssistente contexto) {
    Optional<ClassificacaoIntencao> voto = Optional.empty();
    if (propriedades.isCamadas() && !desempateDireto(pergunta)) {
      Optional<Intencao> regra = RegrasClassificacao.decidir(pergunta);
      if (regra.isPresent()) {
        return new ClassificacaoIntencao(regra.get(), 1, null, CamadaClassificacao.REGRA);
      }
      contexto.verificarAtiva();
      // Com o modelo no ar, as embeddings so decidem o voto quase unanime e nunca lei com dado
      // do produtor; em pausa, o limiar de antes, para o modo sem modelo nao piorar (#497).
      boolean comModelo = disponibilidade.disponivel();
      double limiar = comModelo ? propriedades.getLimiarConfianca()
          : propriedades.getLimiarConfiancaSemModelo();
      voto = embeddings.classificar(pergunta);
      Optional<ClassificacaoIntencao> vizinhos = voto
          .filter(c -> c.confianca() >= limiar)
          .filter(c -> !comModelo || !leiComDadosDoProdutor(c, pergunta));
      if (vizinhos.isPresent()) {
        return vizinhos.get().intencao() == Intencao.MISTO
            ? comPerguntaLei(vizinhos.get(), pergunta, contexto)
            : vizinhos.get();
      }
    }
    return desempate(pergunta, contexto, voto);
  }

  /**
   * Marca de outra pessoa ou de ordem ao sistema (#497): com o modelo no ar, ele decide. A regra
   * lia "meu vizinho paga Funrural?" como lei e as embeddings punham "as notas do meu vizinho"
   * perto de "as minhas notas"; o modelo separa o dado do terceiro da operação do produtor com
   * ele ("vendi para o meu vizinho"). Com o modelo em pausa, as camadas seguem decidindo, como
   * antes.
   */
  private boolean desempateDireto(String pergunta) {
    return SinalRecusaFirme.pedeDesempate(pergunta) && disponibilidade.disponivel();
  }

  /**
   * Voto LEGISLACAO das embeddings numa pergunta com dado ou fato do produtor (#497): os vizinhos
   * de lei puxavam "as minhas compras de fertilizante dão crédito de IBS?" e "explica o split
   * payment com a minha venda de milho" para a regra geral, sem a parte dos dados. O modelo desempata.
   */
  private static boolean leiComDadosDoProdutor(ClassificacaoIntencao voto, String pergunta) {
    return voto.intencao() == Intencao.LEGISLACAO
        && RegrasClassificacao.falaDosDadosDoProdutor(pergunta);
  }

  /**
   * MISTO votado pelas embeddings: o modelo já é chamado para separar a pergunta de lei, então a
   * rota é a dele (#497), sem chamada a mais. As embeddings punham no MISTO pergunta de dados com
   * nome de tributo ("quanto eu devo de Funrural?") e de lei com "quanto" ("quanto é a alíquota
   * do Funrural?"), porque o MISTO do treino é quase todo "quanto ... e ... tem redução?". O
   * modelo concordando, a camada segue {@code EMBEDDING}, com a pergunta de lei dele; sem o
   * modelo, o MISTO das embeddings, como antes.
   */
  private ClassificacaoIntencao comPerguntaLei(ClassificacaoIntencao misto, String pergunta,
      ContextoAssistente contexto) {
    if (!disponibilidade.disponivel()) {
      return misto;
    }
    try {
      ClassificacaoIntencao doModelo = llm.classificar(pergunta, contexto);
      if (doModelo.intencao() != Intencao.MISTO && doModelo.confianca() >= confiancaMinima) {
        return doModelo;
      }
      // Modelo inseguro ou com a resposta ilegivel: fica o MISTO das embeddings, como antes.
      String perguntaLei = doModelo.intencao() == Intencao.MISTO ? doModelo.perguntaLei() : null;
      return new ClassificacaoIntencao(Intencao.MISTO, misto.confianca(), perguntaLei,
          CamadaClassificacao.EMBEDDING);
    } catch (AssistenteIndisponivelException e) {
      contexto.verificarAtiva();
      return misto;
    }
  }

  /**
   * O modelo desempata. Em pausa ou falhando na chamada, vale o voto das embeddings que passa do
   * limiar sem modelo (#497: o limiar 0,9 e o voto de lei com dado do produtor mandam ao desempate
   * votos que antes decidiam, e a falha do modelo não pode jogá-los nas palavras-chave do modo
   * fallback); sem esse voto, {@link AssistenteIndisponivelException}, como antes.
   */
  private ClassificacaoIntencao desempate(String pergunta, ContextoAssistente contexto,
      Optional<ClassificacaoIntencao> voto) {
    Optional<ClassificacaoIntencao> semModelo = voto
        .filter(c -> c.confianca() >= propriedades.getLimiarConfiancaSemModelo());
    if (!disponibilidade.disponivel()) {
      return semModelo.orElseThrow(() -> new AssistenteIndisponivelException(
          "Classificacao sem decisao das regras e das embeddings, com o modelo em pausa", null));
    }
    try {
      return llm.classificar(pergunta, contexto);
    } catch (AssistenteIndisponivelException e) {
      if (semModelo.isEmpty()) {
        throw e;
      }
      contexto.verificarAtiva();
      return semModelo.get();
    }
  }
}
