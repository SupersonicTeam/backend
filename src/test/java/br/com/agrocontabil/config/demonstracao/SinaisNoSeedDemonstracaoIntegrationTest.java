package br.com.agrocontabil.config.demonstracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.model.NotaFiscal;
import br.com.agrocontabil.model.OrigemNotaFiscal;
import br.com.agrocontabil.repository.NotaFiscalRepository;
import br.com.agrocontabil.service.SinaisNotaConferirService;
import br.com.agrocontabil.service.SinaisNotaConferirService.NotaParaSinal;
import br.com.agrocontabil.service.SinalNota;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Falso positivo dos sinais de nota a conferir (issue #276) medido no seed real da banca (#315):
 * nenhuma nota do cenário foi feita para estar errada, então todo sinal aqui é falso positivo. O
 * resultado vai ao log (para o PR) e o teste exige zero.
 *
 * <p>Banco H2 próprio, como o {@code SeedDemonstracaoIntegrationTest}: a semeadura contaminaria as
 * contagens dos outros testes.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:demonstracao276;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
        + "NON_KEYWORDS=VALUE",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:schema-nota-fiscal-test.sql,"
        + "classpath:schema-shedlock-test.sql",
    "app.demonstracao.habilitada=true",
    "app.assistente.cota.habilitada=true",
    "app.seed.senha=Banca-AgroConIA-demonstracao-2026"})
@ActiveProfiles("test")
@DisplayName("Sinais de nota a conferir no seed da banca (#276)")
class SinaisNoSeedDemonstracaoIntegrationTest {

  @Autowired private NotaFiscalRepository notaRepository;
  @Autowired private SinaisNotaConferirService sinaisService;

  @Test
  @DisplayName("nenhum sinal nas notas proprias do cenario (falso positivo zero)")
  void semFalsoPositivo() {
    List<NotaFiscal> proprias = notaRepository.findAll().stream()
        .filter(n -> n.getOrigem() == OrigemNotaFiscal.PROPRIA)
        .toList();
    List<SinalNota> sinais = new ArrayList<>();
    for (NotaFiscal n : proprias) {
      sinais.addAll(sinaisService.sinais(n.getProdutor().getId(),
          new NotaParaSinal(n.getId(), n.getOrigem(), n.getStatus(), n.getDataEmissao())));
    }
    Map<SinalNota.CodigoSinalNota, Long> porSinal = sinais.stream()
        .collect(Collectors.groupingBy(SinalNota::codigo, Collectors.counting()));
    System.out.println("#276 falso positivo no seed: " + proprias.size() + " notas proprias, "
        + "sinais por codigo = " + porSinal);

    assertThat(proprias).hasSizeGreaterThan(80);
    assertThat(sinais).isEmpty();
  }
}
