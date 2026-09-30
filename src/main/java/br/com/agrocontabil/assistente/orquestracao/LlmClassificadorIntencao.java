package br.com.agrocontabil.assistente.orquestracao;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.DataSanitizer;
import br.com.agrocontabil.assistente.PromptsCeres;
import br.com.agrocontabil.assistente.llm.ModeloLinguagem;
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

  public LlmClassificadorIntencao(ModeloLinguagem modelo, ObjectMapper objectMapper) {
    this.modelo = modelo;
    this.objectMapper = objectMapper;
  }

  @Override
  public ClassificacaoIntencao classificar(String pergunta, ContextoAssistente contexto) {
    Prompt prompt = new Prompt(List.<Message>of(
        new SystemMessage(PromptsCeres.CLASSIFICACAO),
        new UserMessage(DataSanitizer.mascarar(pergunta))));
    contexto.verificarAtiva();
    ChatResponse resposta = modelo.chamar(prompt);
    contexto.registrarUso(resposta);
    return interpretar(resposta.getResult().getOutput().getText());
  }

  /** Lê {@code {"intencao": "...", "confianca": 0.9}} com tolerância. */
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
      return new ClassificacaoIntencao(intencao, json.path("confianca").asDouble(0));
    } catch (Exception e) {
      log.info("Classificador de intencao devolveu JSON invalido: {}", e.getClass().getSimpleName());
      return ClassificacaoIntencao.indeterminada();
    }
  }
}
