package br.com.agrocontabil.assistente.rag;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import org.jsoup.Jsoup;
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
 * <p>O arquivo é o HTML do texto compilado, salvo do Planalto; o charset vem do próprio documento
 * (o Planalto usa windows-1252). Ao terminar, fecha o contexto e a aplicação encerra.
 */
@Component
@Profile("ingestao")
public class IngestaoLegislacaoRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(IngestaoLegislacaoRunner.class);

  private final FatiadorDispositivos fatiador;
  private final IngestaoLegislacaoService ingestao;
  private final ConfigurableApplicationContext contexto;
  private final String arquivo;
  private final String norma;
  private final String url;
  private final String dataCaptura;
  private final String vigenciaInicio;

  /** Constrói o comando com os parâmetros {@code ingestao.*}. */
  public IngestaoLegislacaoRunner(
      FatiadorDispositivos fatiador,
      IngestaoLegislacaoService ingestao,
      ConfigurableApplicationContext contexto,
      @Value("${ingestao.arquivo}") String arquivo,
      @Value("${ingestao.norma}") String norma,
      @Value("${ingestao.url}") String url,
      @Value("${ingestao.data-captura}") String dataCaptura,
      @Value("${ingestao.vigencia-inicio}") String vigenciaInicio) {
    this.fatiador = fatiador;
    this.ingestao = ingestao;
    this.contexto = contexto;
    this.arquivo = arquivo;
    this.norma = norma;
    this.url = url;
    this.dataCaptura = dataCaptura;
    this.vigenciaInicio = vigenciaInicio;
  }

  @Override
  public void run(ApplicationArguments args) throws IOException {
    try {
      FonteLegislacao fonte = new FonteLegislacao(
          norma, url, LocalDate.parse(dataCaptura), LocalDate.parse(vigenciaInicio));
      List<DispositivoLegal> dispositivos = fatiador.fatiar(Jsoup.parse(new File(arquivo)));
      if (dispositivos.isEmpty()) {
        throw new IllegalStateException(
            "Nenhum dispositivo encontrado em " + arquivo + ": confira se e o texto compilado");
      }
      ResumoIngestao resumo = ingestao.ingerir(fonte, dispositivos);
      log.info("Ingestao concluida: {}", resumo);
    } finally {
      SpringApplication.exit(contexto);
    }
  }
}
