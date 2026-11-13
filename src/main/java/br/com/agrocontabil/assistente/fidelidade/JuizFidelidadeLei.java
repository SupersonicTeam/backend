package br.com.agrocontabil.assistente.fidelidade;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.DataSanitizer;
import br.com.agrocontabil.assistente.PerguntaInterrompidaException;
import br.com.agrocontabil.assistente.PromptsCeres;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.assistente.llm.RaciocinioPorChamada;
import br.com.agrocontabil.config.AssistenteProperties;
import br.com.agrocontabil.model.DesfechoFidelidadeLei;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import br.com.agrocontabil.common.NormalizacaoBusca;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * Camada 2 da fidelidade da lei (#269), o juiz: depois que a {@link ConferenciaFidelidadeLei}
 * aprova, <strong>uma</strong> chamada ao modelo com as frases e as cópias de cada uma; o modelo
 * responde, por frase, {@code SUSTENTA}, {@code NAO_SUSTENTA} ou {@code CONTRADIZ}.
 *
 * <p><strong>Só reprova, nunca reescreve:</strong> qualquer frase que não seja {@code SUSTENTA}
 * reprova a resposta inteira (tirar só a frase reprovada pode mudar o sentido do resto, como uma
 * exceção que some). <strong>Falha fecha:</strong> modelo indisponível, JSON ilegível, veredito
 * desconhecido, resposta cortada pelo teto ou número de vereditos diferente do de frases reprovam
 * ({@code REPROVADA_JUIZ_FALHA}); a pergunta interrompida continua sendo interrupção.
 *
 * <p>O juiz não vê os trechos inteiros nem a pergunta: decide se as cópias sustentam a frase, não
 * se a frase responde à pergunta. Instrução fixa ({@link PromptsCeres#JUIZ_FIDELIDADE}), conteúdo
 * na mensagem do usuário, tudo pelo {@link DataSanitizer}. Na configuração {@code justificativa}
 * ({@code app.assistente.fidelidade-lei.juiz.configuracao}) cada veredito vem depois de uma justificativa
 * curta ({@link PromptsCeres#JUIZ_FIDELIDADE_COM_JUSTIFICATIVA}); a decisão é a mesma. O teto de
 * saída é o da configuração ({@code AssistenteProperties.tetoSaidaDoJuiz}), e o custo entra na reserva da pergunta como parcela própria
 * quando o juiz está ativo ({@code AssistenteProperties.tetoDaPergunta}). O tempo, os tokens e a
 * latência da chamada vão ao {@link ContextoAssistente#registrarJuizLei}, e daí à auditoria.
 */
@Component
public class JuizFidelidadeLei {

  private static final Logger log = LoggerFactory.getLogger(JuizFidelidadeLei.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final ModeloLinguagem modelo;
  private final AssistenteProperties propriedades;

  /**
   * Constrói o juiz sobre a porta do modelo; o teto de saída vem da configuração.
   *
   * @throws IllegalStateException com o juiz ativo numa configuração adaptativa e o provedor fora da
   *         API da Anthropic: a subida falha, em vez de cada resposta de lei falhar no juiz
   */
  public JuizFidelidadeLei(ModeloLinguagem modelo, AssistenteProperties propriedades) {
    this.modelo = modelo;
    this.propriedades = propriedades;
    AssistenteProperties.FidelidadeLei.Juiz.Configuracao configuracao =
        propriedades.getFidelidadeLei().getJuiz().getConfiguracao();
    if (propriedades.juizDaLeiAtivo() && configuracao.raciocina()
        && !modelo.aceitaRaciocinioPorChamada()) {
      throw new IllegalStateException("app.assistente.fidelidade-lei.juiz.configuracao="
          + configuracao.nome() + " exige AI_CHAT_PROVIDER=anthropic");
    }
  }

  /** Veredito de uma frase. */
  public enum Veredito {
    SUSTENTA, NAO_SUSTENTA, CONTRADIZ
  }

  /** Por que o juiz falhou (todas reprovam: falha fecha). */
  public enum Falha {
    /** A saída chegou ao teto da configuração. */
    CORTADA_PELO_TETO,
    /** Sem JSON legível, sem a lista ou com veredito desconhecido. */
    FORMATO,
    /** Número de vereditos diferente do de frases. */
    CONTAGEM_DIFERENTE,
    /** Exceção na chamada (modelo indisponível, tempo, provedor). */
    ERRO_MODELO
  }

  /**
   * O julgamento inteiro, para a bancada do juiz e o log; a rota só usa o {@code desfecho}.
   *
   * @param desfecho  nulo se todas as frases são {@code SUSTENTA}; senão o motivo da reprovação
   * @param falha     o motivo da falha, se o desfecho é {@code REPROVADA_JUIZ_FALHA}; senão nulo
   * @param vereditos os vereditos lidos, na ordem (vazio se ilegíveis ou sem chamada)
   * @param cru       o texto do modelo (nulo sem resposta)
   */
  public record Julgamento(DesfechoFidelidadeLei desfecho, Falha falha, List<Veredito> vereditos,
      String cru) {

    /** Cópia defensiva. */
    public Julgamento {
      vereditos = vereditos == null ? List.of() : List.copyOf(vereditos);
    }

    /** {@code true} se a saída foi cortada pelo teto. */
    public boolean cortadaPeloTeto() {
      return falha == Falha.CORTADA_PELO_TETO;
    }
  }

  /**
   * Julga as frases aprovadas pela camada 1.
   *
   * @param frases   as frases entregues, com as cópias de cada uma (não vazia)
   * @param contexto contexto da pergunta (chamada ao modelo, métricas do juiz)
   * @return nulo se todas são {@code SUSTENTA}; senão o motivo da reprovação
   * @throws PerguntaInterrompidaException se a pergunta foi interrompida
   */
  public DesfechoFidelidadeLei julgar(List<ConferenciaFidelidadeLei.FraseConferida> frases,
      ContextoAssistente contexto) {
    return avaliar(frases, contexto, propriedades.getFidelidadeLei().getJuiz().getConfiguracao())
        .desfecho();
  }

  /**
   * Julga as frases numa configuração dada. Só a bancada do juiz escolhe a configuração; a rota usa
   * a das propriedades, que é a que a reserva da pergunta cobre ({@code tetoSaidaDoJuiz}): outra
   * configuração aqui, numa pergunta de verdade, gastaria além do reservado.
   * Mesmo efeito do {@link #julgar} sobre o contexto: a chamada pelo {@link ContextoAssistente} e
   * as métricas do juiz.
   *
   * @param frases   as frases entregues, com as passagens de cada uma (não vazia)
   * @param contexto contexto da pergunta
   * @param configuracao a configuração do juiz
   * @return o julgamento; nunca nulo
   * @throws PerguntaInterrompidaException se a pergunta foi interrompida
   */
  public Julgamento avaliar(List<ConferenciaFidelidadeLei.FraseConferida> frases,
      ContextoAssistente contexto,
      AssistenteProperties.FidelidadeLei.Juiz.Configuracao configuracao) {
    int tetoSaida = propriedades.tetoSaidaDoJuiz(configuracao);
    long inicio = System.nanoTime();
    int entradaAntes = valor(contexto.tokensEntrada());
    int saidaAntes = valor(contexto.tokensSaida());
    boolean chamou = false;
    boolean registrar = true;
    Integer raciocinio = null;
    try {
      if (configuracao.raciocina()) {
        RaciocinioPorChamada.limpar();
      }
      ChatResponse resposta = chamar(frases, contexto, configuracao, tetoSaida);
      chamou = true;
      String texto = textoDaResposta(resposta);
      if (ModeloLinguagem.cortadaPeloTeto(resposta, tetoSaida)) {
        log.warn("Ceres: juiz da fidelidade da lei cortado pelo teto de {} tokens", tetoSaida);
        return new Julgamento(DesfechoFidelidadeLei.REPROVADA_JUIZ_FALHA,
            Falha.CORTADA_PELO_TETO, List.of(), texto);
      }
      List<Veredito> lidos = vereditos(texto);
      DesfechoFidelidadeLei desfecho = decidir(lidos, frases.size());
      Falha falha = lidos == null ? Falha.FORMATO
          : lidos.size() != frases.size() ? Falha.CONTAGEM_DIFERENTE : null;
      return new Julgamento(desfecho, falha, lidos, texto);
    } catch (PerguntaInterrompidaException e) {
      // Interrompida: a pergunta e que terminou, nao o juiz; as colunas dele ficam nulas.
      registrar = false;
      throw e;
    } catch (RuntimeException e) {
      // Falha fecha: sem o veredito, a resposta nao chega ao produtor.
      log.warn("Ceres: juiz da fidelidade da lei falhou: {}", e.getClass().getName());
      return new Julgamento(DesfechoFidelidadeLei.REPROVADA_JUIZ_FALHA, Falha.ERRO_MODELO,
          List.of(), null);
    } finally {
      // Consumido sempre, inclusive na falha: o valor da thread local nao passa para outra pergunta.
      if (configuracao.raciocina()) {
        raciocinio = RaciocinioPorChamada.consumir();
      }
      if (registrar) {
        long latencia = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio);
        contexto.registrarJuizLei(latencia,
            chamou ? valor(contexto.tokensEntrada()) - entradaAntes : 0,
            chamou ? valor(contexto.tokensSaida()) - saidaAntes : 0, raciocinio);
      }
    }
  }

  private ChatResponse chamar(List<ConferenciaFidelidadeLei.FraseConferida> frases,
      ContextoAssistente contexto,
      AssistenteProperties.FidelidadeLei.Juiz.Configuracao configuracao, int tetoSaida) {
    List<ConferenciaFidelidadeLei.FraseConferida> conteudo = new ArrayList<>();
    for (ConferenciaFidelidadeLei.FraseConferida frase : frases) {
      conteudo.add(new ConferenciaFidelidadeLei.FraseConferida(
          DataSanitizer.mascarar(frase.texto()),
          frase.copias().stream().map(DataSanitizer::mascarar).toList()));
    }
    Prompt prompt = new Prompt(List.<Message>of(
        new SystemMessage(configuracao == AssistenteProperties.FidelidadeLei.Juiz.Configuracao.JUSTIFICATIVA
            ? PromptsCeres.JUIZ_FIDELIDADE_COM_JUSTIFICATIVA
            : PromptsCeres.JUIZ_FIDELIDADE),
        new UserMessage(PromptsCeres.mensagemJuiz(conteudo))),
        configuracao.raciocina()
            ? modelo.comRaciocinio(tetoSaida, configuracao.esforco())
            : ModeloLinguagem.comTeto(tetoSaida));
    return contexto.chamar(modelo, prompt);
  }

  /**
   * O texto da resposta: a última geração com texto. Com o raciocínio do provedor, o Spring AI
   * entrega cada bloco {@code thinking} como uma geração à parte, antes da do texto e com o texto
   * vazio (a API omite o raciocínio por padrão); ler só a primeira daria o vazio.
   */
  static String textoDaResposta(ChatResponse resposta) {
    if (resposta == null || resposta.getResults() == null) {
      return null;
    }
    String texto = null;
    for (Generation geracao : resposta.getResults()) {
      String deste = geracao.getOutput() == null ? null : geracao.getOutput().getText();
      if (deste != null && !deste.isBlank()) {
        texto = deste;
      }
    }
    return texto;
  }

  /**
   * Os vereditos lidos; nulo se o JSON não vem no formato ou traz veredito desconhecido. Cada item é
   * o veredito em texto ou, na configuração {@code justificativa}, um objeto com {@code veredito}.
   */
  static List<Veredito> vereditos(String texto) {
    if (texto == null) {
      return null;
    }
    int inicio = texto.indexOf('{');
    int fim = texto.lastIndexOf('}');
    if (inicio < 0 || fim <= inicio) {
      return null;
    }
    JsonNode lista;
    try {
      JsonNode raiz = JSON.readTree(texto.substring(inicio, fim + 1));
      lista = raiz == null ? null : raiz.get("vereditos");
    } catch (Exception e) {
      return null;
    }
    if (lista == null || !lista.isArray()) {
      return null;
    }
    List<Veredito> lidos = new ArrayList<>();
    for (JsonNode item : lista) {
      JsonNode veredito = item.isObject() ? item.get("veredito") : item;
      if (veredito == null || !veredito.isTextual()) {
        return null;
      }
      try {
        lidos.add(Veredito.valueOf(normalizar(veredito.asText())));
      } catch (IllegalArgumentException e) {
        return null;
      }
    }
    return lidos;
  }

  /** "Não sustenta", "NÃO_SUSTENTA" e "nao-sustenta" valem como {@code NAO_SUSTENTA}. */
  private static String normalizar(String veredito) {
    return NormalizacaoBusca.normalizar(veredito).replaceAll("[\\s-]+", "_")
        .toUpperCase(Locale.ROOT);
  }

  /** Nulo se todas sustentam; contradição pesa mais que falta de sustentação. */
  static DesfechoFidelidadeLei decidir(List<Veredito> vereditos, int frases) {
    if (vereditos == null || vereditos.size() != frases) {
      return DesfechoFidelidadeLei.REPROVADA_JUIZ_FALHA;
    }
    if (vereditos.contains(Veredito.CONTRADIZ)) {
      return DesfechoFidelidadeLei.REPROVADA_JUIZ_CONTRADIZ;
    }
    if (vereditos.contains(Veredito.NAO_SUSTENTA)) {
      return DesfechoFidelidadeLei.REPROVADA_JUIZ_NAO_SUSTENTA;
    }
    return null;
  }

  private static int valor(Integer tokens) {
    return tokens == null ? 0 : tokens;
  }
}
