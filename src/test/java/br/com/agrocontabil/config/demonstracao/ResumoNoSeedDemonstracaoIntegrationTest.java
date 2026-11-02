package br.com.agrocontabil.config.demonstracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.ComposicaoRespostaDados;
import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.SecaoResposta;
import br.com.agrocontabil.assistente.ferramentas.FerramentasDadosProdutor;
import br.com.agrocontabil.assistente.ferramentas.RespostasFerramentas.ResumoProdutor;
import br.com.agrocontabil.model.Produtor;
import br.com.agrocontabil.repository.ProdutorRepository;
import java.time.LocalDate;
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

  @Test
  @DisplayName("produtor principal: atraso, nota parada e pedido, na ordem, com o resumo")
  void principal() {
    Produtor principal = produtor(CenarioDemonstracao.CHAVE_PRINCIPAL);
    ContextoAssistente contexto =
        new ContextoAssistente(principal.getId(), LocalDate.now(BRASILIA));

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
    assertThat(texto).startsWith("Encontrei " + pontos + " pontos de atencao: 1 guia em atraso");
    int atraso = texto.indexOf("Voce tem 1 guia em atraso");
    int parada = texto.indexOf(resumo.notasParadas() == 1 ? "1 nota esta parada"
        : resumo.notasParadas() + " notas estao paradas");
    int pedido = texto.indexOf("O escritorio espera 1 documento seu");
    assertThat(atraso).isPositive();
    assertThat(parada).isGreaterThan(atraso);
    assertThat(pedido).isGreaterThan(parada);
    assertThat(texto).contains("devolvida pelo contador", "Extrato bancario de ");
    // Sem nota a conferir no seed (#276: falso positivo zero), ela fica so no resumo.
    assertThat(resumo.notasAConferir()).isZero();
    assertThat(texto).contains("Sem pendencias em:").contains("notas a conferir");
  }

  private Produtor produtor(String chave) {
    return produtorRepository.findAll().stream()
        .filter(p -> CenarioDemonstracao.cliente(chave).documento().equals(p.getCpfCnpj()))
        .findFirst().orElseThrow();
  }
}
