package br.com.agrocontabil.assistente;

import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.assistente.rag.BuscaLegislacaoService;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.assistente.rag.ValidadorCitacao;
import br.com.agrocontabil.model.DesfechoInteracao;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * Rota de legislação da Ceres — RAG com citação obrigatória (issue #156).
 *
 * <ol>
 *   <li>recupera os trechos vigentes na data da pergunta ({@link BuscaLegislacaoService}); sem
 *       trecho acima do limiar, responde "não encontrei fundamento legal" <strong>sem chamar o
 *       modelo</strong>;</li>
 *   <li>o modelo responde só com base nos trechos, marcados {@code [T1]}…{@code [Tn]}, sem
 *       funções e sem escrever referência legal;</li>
 *   <li>o {@link ValidadorCitacao} exige marca existente e troca cada marca pela referência montada
 *       dos metadados — citação inventada derruba a resposta;</li>
 *   <li>o {@link ValidadorNumeros} confere que percentuais, prazos e números de artigo da resposta
 *       estão nos trechos (ou na pergunta): um "redução de 70%" que a lei não diz não passa.</li>
 * </ol>
 */
@Component
public class RotaLegislacao {

  private static final Logger log = LoggerFactory.getLogger(RotaLegislacao.class);

  /** Raciocínio que alguns modelos (qwen3) escrevem no texto quando o thinking não é separado. */
  private static final Pattern RACIOCINIO = Pattern.compile("(?s)<think>.*?</think>");

  private final ModeloLinguagem modelo;
  private final BuscaLegislacaoService busca;
  private final ValidadorCitacao validadorCitacao;
  private final ValidadorNumeros validadorNumeros;

  /** Constrói a rota. */
  public RotaLegislacao(ModeloLinguagem modelo, BuscaLegislacaoService busca,
      ValidadorCitacao validadorCitacao, ValidadorNumeros validadorNumeros) {
    this.modelo = modelo;
    this.busca = busca;
    this.validadorCitacao = validadorCitacao;
    this.validadorNumeros = validadorNumeros;
  }

  /**
   * Responde uma pergunta sobre a legislação.
   *
   * @param pergunta pergunta do usuário
   * @param contexto contexto da pergunta (data de referência, auditoria)
   * @return resposta com citações validadas, ou mensagem fixa de "sem fundamento legal"
   * @throws br.com.agrocontabil.exception.AssistenteIndisponivelException se a busca ou o modelo
   *         falharem
   */
  public ResultadoRota responder(String pergunta, ContextoAssistente contexto) {
    List<TrechoRecuperado> trechos = busca.buscar(pergunta, contexto.hoje());
    if (trechos.isEmpty()) {
      return ResultadoRota.descartada(
          ValidadorCitacao.SEM_FUNDAMENTO, DesfechoInteracao.SEM_FUNDAMENTO_LEGAL);
    }

    String perguntaAoModelo = DataSanitizer.mascarar(pergunta);
    Prompt prompt = new Prompt(List.<Message>of(
        new SystemMessage(PromptsCeres.sistemaLegislacao(contexto.hoje(), formatar(trechos))),
        new UserMessage(perguntaAoModelo)));
    ChatResponse resposta = modelo.chamar(prompt);
    contexto.registrarUso(resposta);
    String texto = limpar(resposta.getResult().getOutput().getText());

    ValidadorCitacao.Resultado citacao = validadorCitacao.validar(texto, trechos);
    if (!citacao.valida()) {
      // Sem marca (inclusive o NAO_ENCONTREI) ou com marca inventada.
      log.info("Ceres: resposta de legislacao sem citacao valida descartada");
      return ResultadoRota.descartada(
          ValidadorCitacao.SEM_FUNDAMENTO, DesfechoInteracao.CITACAO_INVALIDA);
    }

    List<String> fontes = new ArrayList<>();
    trechos.forEach(t -> fontes.add(t.texto()));
    fontes.add(perguntaAoModelo);
    // A resposta original (com as marcas) e a que o modelo escreveu; as referencias montadas pelo
    // backend nao entram na conferencia.
    ValidadorNumeros.Resultado numeros =
        validadorNumeros.verificar(semMarcas(texto), fontes, List.of(contexto.hoje()));
    if (!numeros.rastreavel()) {
      log.warn("Ceres: resposta de legislacao descartada com {} numero(s) fora dos trechos",
          numeros.quantidadeSemFonte());
      return ResultadoRota.descartada(
          ValidadorCitacao.SEM_FUNDAMENTO, DesfechoInteracao.NUMERO_SEM_FONTE);
    }

    contexto.registrarTrechosCitados(citacao.idsCitados());
    return ResultadoRota.aceita(citacao.texto(), citacao.citacoes());
  }

  /** Trechos como o modelo os vê: só a marca e o texto, sem a referência legal. */
  static String formatar(List<TrechoRecuperado> trechos) {
    StringBuilder sb = new StringBuilder();
    for (TrechoRecuperado t : trechos) {
      sb.append('[').append(t.marca()).append("] ").append(t.texto()).append("\n\n");
    }
    return sb.toString().strip();
  }

  private static String semMarcas(String texto) {
    return texto.replaceAll("\\[\\s*T\\d+(?:\\s*[,;]\\s*T\\d+)*\\s*\\]", " ");
  }

  private static String limpar(String texto) {
    return texto == null ? "" : RACIOCINIO.matcher(texto).replaceAll("").strip();
  }
}
