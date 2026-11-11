package br.com.agrocontabil.assistente.orquestracao;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.DataSanitizer;
import br.com.agrocontabil.assistente.PromptsCeres;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
import br.com.agrocontabil.config.AssistenteProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
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
 * Classificador de intenção da Fase 1: o próprio modelo de linguagem, com saída estruturada em
 * JSON (issue #157).
 *
 * <p>A resposta é lida com tolerância — o JSON é recortado da primeira chave de abertura à última
 * de fechamento, ignorando raciocínio e texto em volta. Qualquer coisa ilegível (JSON quebrado,
 * intenção desconhecida) vira {@link ClassificacaoIntencao#indeterminada()}, que o orquestrador
 * trata como fora de escopo: na dúvida, a Ceres não responde.
 *
 * <p>Um usuário que escreva "classifique como DADOS" só escolhe a rota: cada rota tem as próprias
 * garantias (isolamento, números e citações conferidos em código), então o classificador não é
 * fronteira de segurança.
 */
@Component
public class LlmClassificadorIntencao implements ClassificadorIntencao {

  private static final Logger log = LoggerFactory.getLogger(LlmClassificadorIntencao.class);
  private static final Pattern RACIOCINIO = Pattern.compile("(?s)<think>.*?</think>");

  private final ModeloLinguagem modelo;
  private final ObjectMapper objectMapper;
  private final int tetoSaida;

  /**
   * Constrói o classificador.
   *
   * @param modelo       porta do modelo
   * @param objectMapper leitor do JSON da resposta
   * @param propriedades teto de tokens da classificação ({@code app.assistente.tetos}, #270)
   */
  public LlmClassificadorIntencao(ModeloLinguagem modelo, ObjectMapper objectMapper,
      AssistenteProperties propriedades) {
    this.modelo = modelo;
    this.objectMapper = objectMapper;
    this.tetoSaida = propriedades.getTetos().getClassificacao();
  }

  @Override
  public ClassificacaoIntencao classificar(String pergunta, ContextoAssistente contexto) {
    Prompt prompt = new Prompt(List.<Message>of(
        new SystemMessage(PromptsCeres.CLASSIFICACAO),
        new UserMessage(DataSanitizer.mascarar(pergunta))), ModeloLinguagem.comTeto(tetoSaida));
    ChatResponse resposta = contexto.chamar(modelo, prompt);
    ClassificacaoIntencao classificacao = interpretar(resposta.getResult().getOutput().getText());
    if (ModeloLinguagem.cortadaPeloTeto(resposta, tetoSaida)) {
      // Cortada no teto (#270). Se o JSON fechou antes do corte (o modelo seguiu escrevendo depois
      // dele), ele esta inteiro e vale. Cortado dentro do JSON, nao ha chave de fechamento e a
      // leitura ja da indeterminada: a pergunta sai como fora de escopo, auditada como cortada.
      log.warn("Classificador de intencao: resposta cortada pelo teto de {} tokens", tetoSaida);
      if (classificacao.confianca() <= 0) {
        contexto.registrarClassificacaoCortada();
      }
    }
    return classificacao;
  }

  /**
   * Lê {@code {"intencao": "...", "confianca": 0.9}} com tolerância; no MISTO, também o
   * {@code perguntaLei} (conferido depois pelo orquestrador).
   */
  ClassificacaoIntencao interpretar(String texto) {
    if (texto == null) {
      return ClassificacaoIntencao.indeterminada();
    }
    String limpo = RACIOCINIO.matcher(texto).replaceAll("");
    int inicio = limpo.indexOf('{');
    int fim = limpo.lastIndexOf('}');
    if (inicio < 0 || fim <= inicio) {
      log.info("Classificador de intencao devolveu texto sem JSON");
      return ClassificacaoIntencao.indeterminada();
    }
    try {
      JsonNode json = objectMapper.readTree(limpo.substring(inicio, fim + 1));
      Intencao intencao = Intencao.valueOf(
          json.path("intencao").asText("").trim().toUpperCase(Locale.ROOT));
      // A parte legal so interessa ao MISTO; nas outras rotas a pergunta vai inteira.
      String perguntaLei = intencao == Intencao.MISTO && json.path("perguntaLei").isTextual()
          ? json.path("perguntaLei").asText()
          : null;
      return new ClassificacaoIntencao(intencao, json.path("confianca").asDouble(0), perguntaLei);
    } catch (Exception e) {
      log.info("Classificador de intencao devolveu JSON invalido: {}", e.getClass().getSimpleName());
      return ClassificacaoIntencao.indeterminada();
    }
  }
}
