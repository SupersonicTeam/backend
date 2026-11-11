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
 *       do limiar ({@code app.assistente.classificacao.limiar-confianca});</li>
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

  /** Constrói sobre as três camadas. */
  public ClassificadorEmCamadas(LlmClassificadorIntencao llm,
      EmbeddingClassificadorIntencao embeddings, DisponibilidadeModelo disponibilidade,
      AssistenteProperties propriedades) {
    this.llm = llm;
    this.embeddings = embeddings;
    this.disponibilidade = disponibilidade;
    this.propriedades = propriedades.getClassificacao();
  }

  @Override
  public ClassificacaoIntencao classificar(String pergunta, ContextoAssistente contexto) {
    if (propriedades.isCamadas() && !desempateDireto(pergunta)) {
      Optional<Intencao> regra = RegrasClassificacao.decidir(pergunta);
      if (regra.isPresent()) {
        return new ClassificacaoIntencao(regra.get(), 1, null, CamadaClassificacao.REGRA);
      }
      contexto.verificarAtiva();
      Optional<ClassificacaoIntencao> vizinhos = embeddings.classificar(pergunta)
          .filter(c -> c.confianca() >= propriedades.getLimiarConfianca());
      if (vizinhos.isPresent()) {
        return vizinhos.get().intencao() == Intencao.MISTO
            ? comPerguntaLei(vizinhos.get(), pergunta, contexto)
            : vizinhos.get();
      }
    }
    return desempate(pergunta, contexto);
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

  /** MISTO decidido pelas embeddings: só a pergunta de lei vem do modelo, se ele estiver no ar. */
  private ClassificacaoIntencao comPerguntaLei(ClassificacaoIntencao misto, String pergunta,
      ContextoAssistente contexto) {
    if (!disponibilidade.disponivel()) {
      return misto;
    }
    try {
      ClassificacaoIntencao doModelo = llm.classificar(pergunta, contexto);
      String perguntaLei = doModelo.intencao() == Intencao.MISTO ? doModelo.perguntaLei() : null;
      return new ClassificacaoIntencao(Intencao.MISTO, misto.confianca(), perguntaLei,
          CamadaClassificacao.EMBEDDING);
    } catch (AssistenteIndisponivelException e) {
      contexto.verificarAtiva();
      return misto;
    }
  }

  private ClassificacaoIntencao desempate(String pergunta, ContextoAssistente contexto) {
    if (!disponibilidade.disponivel()) {
      throw new AssistenteIndisponivelException(
          "Classificacao sem decisao das regras e das embeddings, com o modelo em pausa", null);
    }
    return llm.classificar(pergunta, contexto);
  }
}
