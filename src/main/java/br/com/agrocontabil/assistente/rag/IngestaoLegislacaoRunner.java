package br.com.agrocontabil.assistente.rag;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Comando de ingestão da legislação (issue #156). Só existe no profile {@code ingestao} — nunca
 * como endpoint: indexar lei é operação de manutenção, não ação de usuário.
 *
 * <pre>
 * ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,ingestao -Dspring-boot.run.arguments="\
 *   --ingestao.arquivo=/caminho/lcp214.htm \
 *   --ingestao.norma='LC 214/2025' \
 *   --ingestao.url=https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214.htm \
 *   --ingestao.data-captura=2026-09-29 \
 *   --ingestao.vigencia-inicio=2025-01-16"
 * </pre>
 *
 * <p>O arquivo é o HTML do texto compilado, salvo do Planalto. O charset é detectado pelos bytes
 * ({@link LeitorHtmlLegislacao}): a LC 214/2025 não o declara. Ao terminar, fecha o contexto e a
 * aplicação encerra. Com {@code --ingestao.simular=true}, só mostra os dispositivos fatiados, sem
 * gravar: use antes de ingerir uma norma nova. Com {@code --ingestao.somente="art. 6º"} (vários
 * separados por {@code ;}), só os dispositivos pedidos: para leis que alteram outras.
 */
@Component
@Profile("ingestao")
public class IngestaoLegislacaoRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(IngestaoLegislacaoRunner.class);
  private static final int MAIORES_NA_SIMULACAO = 5;
  private static final int PREVIA = 160;

  private final FatiadorDispositivos fatiador;
  private final IngestaoLegislacaoService ingestao;
  private final ConfigurableApplicationContext contexto;
  private final String arquivo;
  private final String norma;
  private final String url;
  private final String dataCaptura;
  private final String vigenciaInicio;
  private final boolean simular;
  private final List<String> somente;

  /** Constrói o comando com os parâmetros {@code ingestao.*}. */
  public IngestaoLegislacaoRunner(
      FatiadorDispositivos fatiador,
      IngestaoLegislacaoService ingestao,
      ConfigurableApplicationContext contexto,
      @Value("${ingestao.arquivo}") String arquivo,
      @Value("${ingestao.norma}") String norma,
      @Value("${ingestao.url}") String url,
      @Value("${ingestao.data-captura}") String dataCaptura,
      @Value("${ingestao.vigencia-inicio}") String vigenciaInicio,
      @Value("${ingestao.simular:false}") boolean simular,
      @Value("${ingestao.somente:}") String somente) {
    this.fatiador = fatiador;
    this.ingestao = ingestao;
    this.contexto = contexto;
    this.arquivo = arquivo;
    this.norma = norma;
    this.url = url;
    this.dataCaptura = dataCaptura;
    this.vigenciaInicio = vigenciaInicio;
    this.simular = simular;
    this.somente = Arrays.stream(somente.split(";"))
        .map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  /**
   * Só os dispositivos pedidos (e o que eles contêm: {@code art. 6º} leva o parágrafo único).
   * Existe para leis de alteração, como a Lei 9.528/1997: o art. 1º dela traz entre aspas
   * redações de 1997 da Lei 8.212 já substituídas, que entrariam na base como se fossem vigentes;
   * dela só o art. 6º (SENAR) é norma própria em vigor.
   *
   * @param dispositivos dispositivos fatiados
   * @param somente      dispositivos pedidos; vazio = todos
   * @return os selecionados, na ordem do texto
   * @throws IllegalArgumentException se um dispositivo pedido não existe na norma (erro de
   *                                  digitação não pode virar ingestão vazia em silêncio)
   */
  static List<DispositivoLegal> selecionar(
      List<DispositivoLegal> dispositivos, List<String> somente) {
    if (somente.isEmpty()) {
      return dispositivos;
    }
    for (String pedido : somente) {
      if (dispositivos.stream().noneMatch(d -> pertence(d, pedido))) {
        throw new IllegalArgumentException("Dispositivo pedido nao existe na norma: " + pedido);
      }
    }
    return dispositivos.stream().filter(d -> somente.stream().anyMatch(p -> pertence(d, p)))
        .toList();
  }

  private static boolean pertence(DispositivoLegal dispositivo, String pedido) {
    return dispositivo.dispositivo().equals(pedido)
        || dispositivo.dispositivo().startsWith(pedido + ",");
  }

  /**
   * Ingere e encerra a aplicação. O código de saída é 0 só com sucesso: um script de deploy precisa
   * distinguir "base carregada" de "arquivo errado".
   */
  @Override
  public void run(ApplicationArguments args) throws IOException {
    int codigo = 1;
    try {
      FonteLegislacao fonte = new FonteLegislacao(
          norma, url, LocalDate.parse(dataCaptura), LocalDate.parse(vigenciaInicio));
      List<DispositivoLegal> dispositivos = fatiador.fatiar(LeitorHtmlLegislacao.ler(Path.of(arquivo)));
      if (dispositivos.isEmpty()) {
        throw new IllegalStateException(
            "Nenhum dispositivo encontrado em " + arquivo + ": confira se e o texto compilado");
      }
      dispositivos = selecionar(dispositivos, somente);
      if (simular) {
        relatarSimulacao(dispositivos);
        codigo = 0;
        return;
      }
      ResumoIngestao resumo = ingestao.ingerir(fonte, dispositivos);
      log.info("Ingestao concluida: {}", resumo);
      codigo = 0;
    } catch (RuntimeException e) {
      log.error("Ingestao de legislacao falhou: {}", e.getMessage(), e);
    } finally {
      int saida = codigo;
      System.exit(SpringApplication.exit(contexto, () -> saida));
    }
  }

  /**
   * Simulação: mostra o que seria ingerido, sem gravar nem gerar embedding. Serve para conferir o
   * fatiamento de uma norma nova antes de pagar a ingestão (uma marcação de revogado não prevista
   * faria redação antiga entrar como vigente).
   */
  private void relatarSimulacao(List<DispositivoLegal> dispositivos) {
    log.info("Simulacao: {} dispositivos de {} (nada foi gravado)", dispositivos.size(), norma);
    dispositivos.stream()
        .sorted((a, b) -> Integer.compare(b.texto().length(), a.texto().length()))
        .limit(MAIORES_NA_SIMULACAO)
        .forEach(d -> log.info("Maior trecho: {} ({} caracteres)", d.dispositivo(),
            d.texto().length()));
    for (DispositivoLegal d : dispositivos) {
      log.info("{} | {}", d.dispositivo(),
          d.texto().length() > PREVIA ? d.texto().substring(0, PREVIA) + "..." : d.texto());
    }
  }
}
