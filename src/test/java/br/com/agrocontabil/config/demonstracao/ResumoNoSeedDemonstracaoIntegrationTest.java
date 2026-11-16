package br.com.agrocontabil.config.demonstracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.ComposicaoRespostaDados;
import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.SecaoResposta;
import br.com.agrocontabil.assistente.ferramentas.FerramentasDadosProdutor;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.ResumoProdutor;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.model.StatusNotaFiscal;
import br.com.agrocontabil.repository.ProdutorRepository;
import br.com.agrocontabil.service.ConsultaDadosProdutorService;
import br.com.agrocontabil.service.NotaPorNumeroResultado;
import br.com.agrocontabil.service.SinalNota.CodigoSinalNota;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * "O que eu preciso saber?" no seed da banca (#315), pela função de resumo e pela composição
 * (issue #266): as guias em atraso, a nota parada e o pedido do escritório, na ordem de
 * prioridade, com a linha de resumo certa.
 *
 * <p>Mesmas propriedades do {@code SeedDemonstracaoIntegrationTest}: o Spring reusa o contexto (e
 * o banco já semeado). Este teste só lê.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:demonstracao315;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
        + "NON_KEYWORDS=VALUE",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:schema-nota-fiscal-test.sql,"
        + "classpath:schema-shedlock-test.sql",
    "app.demonstracao.habilitada=true",
    "app.assistente.cota.habilitada=true",
    "app.seed.senha=Banca-AgroConIA-demonstracao-2026"})
@ActiveProfiles("test")
@DisplayName("Resumo do produtor no seed da demonstracao (#266)")
class ResumoNoSeedDemonstracaoIntegrationTest {

  private static final Logger log =
      LoggerFactory.getLogger(ResumoNoSeedDemonstracaoIntegrationTest.class);
  private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

  @Autowired private FerramentasDadosProdutor ferramentas;
  @Autowired private ProdutorRepository produtorRepository;
  @Autowired private ConsultaDadosProdutorService consultaService;

  @Test
  @DisplayName("produtor principal: atraso, nota parada e pedido, na ordem, com o resumo")
  void principal() {
    Produtor principal = produtor(CenarioDemonstracao.CHAVE_PRINCIPAL);
    LocalDate hoje = LocalDate.now(BRASILIA);
    ContextoAssistente contexto = new ContextoAssistente(principal.getId(), hoje);

    ResumoProdutor resumo = ferramentas.resumoDoProdutor(
        new ToolContext(contexto.comoToolContext()));
    List<SecaoResposta> secoes = contexto.secoes();
    String texto = ComposicaoRespostaDados.compor(secoes);
    log.info("#266 resumo do produtor principal no seed:\n{}", texto);

    assertThat(resumo.guiasEmAtraso()).isEqualTo(1);
    assertThat(resumo.notasParadas()).isPositive();
    assertThat(resumo.pedidosDoEscritorio()).isEqualTo(1);
    long pontos = resumo.guiasEmAtraso() + resumo.prazos() + resumo.notasParadas()
        + resumo.pedidosDoEscritorio() + resumo.notasAConferir();
    assertThat(texto).startsWith("Encontrei " + pontos + " pontos de atenção: 1 guia em atraso");
    int atraso = texto.indexOf("Você tem 1 guia em atraso");
    int parada = texto.indexOf(resumo.notasParadas() == 1 ? "1 nota está parada"
        : resumo.notasParadas() + " notas estão paradas");
    int pedido = texto.indexOf("O escritório espera 1 documento seu");
    assertThat(atraso).isPositive();
    assertThat(parada).isGreaterThan(atraso);
    assertThat(pedido).isGreaterThan(parada);
    assertThat(texto).contains("devolvida pelo contador", "Extrato bancário de ");
    // Nota a conferir no seed (#276, #377): a invariante do cenario e "nenhum falso positivo", nao
    // "nenhuma nota a conferir". Os rascunhos de milho a destinatario sem IE (CFOP 5101, sem CST) ficam
    // de proposito, para mostrar na banca o sinal ICMS_A_DEFINIR e o 422 da emissao (SeedDemonstracao,
    // "fica rascunho e mostra na banca o sinal"). O resumo da Ceres olha so as 10 notas mais recentes
    // entre as nao emitidas e as autorizadas dos ultimos 30 dias; conforme as autorizadas do seed saem
    // dessa janela, entram esses rascunhos, entao a contagem varia com o dia (0 ate 15/11/2026, 2 em
    // 16/11, 1 a 3 nas datas de reset ate a banca). A assercao antiga, isZero(), so passava porque
    // as 10 vagas estavam tomadas por notas recentes sem sinal (CI vermelha da main em 16/11/2026).
    // Por isso o teste trava as propriedades, que valem em qualquer data, e nunca a contagem.
    OffsetDateTime autorizadasDesde =
        hoje.minusDays(30).atStartOfDay(BRASILIA).toOffsetDateTime();
    List<NotaPorNumeroResultado> aConferir =
        consultaService.notasAConferir(principal.getId(), autorizadasDesde);
    assertThat(aConferir).allSatisfy(nota -> {
      assertThat(nota.status()).as("nota %s/%s", nota.numero(), nota.serie())
          .isEqualTo(StatusNotaFiscal.RASCUNHO);
      assertThat(nota.sinais()).as("sinais da nota %s/%s", nota.numero(), nota.serie())
          .isNotEmpty()
          .allSatisfy(sinal -> assertThat(sinal.codigo())
              .isEqualTo(CodigoSinalNota.ICMS_A_DEFINIR));
    });
    assertThat(resumo.notasAConferir()).isEqualTo(aConferir.size());
    if (aConferir.isEmpty()) {
      assertThat(texto).contains("Sem pendências em:").contains("notas a conferir");
    } else {
      assertThat(texto).contains("a conferir");
    }
  }

  private Produtor produtor(String chave) {
    return produtorRepository.findAll().stream()
        .filter(p -> CenarioDemonstracao.cliente(chave).documento().equals(p.getCpfCnpj()))
        .findFirst().orElseThrow();
  }
}
