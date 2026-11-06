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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Falso positivo dos sinais de nota a conferir (issue #276) medido no seed real da banca (#315):
 * fora das notas feitas para mostrar o {@code ICMS_A_DEFINIR} (vendas sem CST de ICMS defensável,
 * em rascunho, e a nota com o CFOP errado de propósito), nenhuma nota do cenário foi feita para
 * estar errada, então todo outro sinal aqui é falso positivo. O resultado vai ao log (para o PR) e
 * o teste exige zero.
 *
 * <p>Mesmas propriedades do {@code SeedDemonstracaoIntegrationTest}, de propósito: o Spring reusa o
 * contexto (e o banco H2 próprio) já semeado, sem semear de novo. Este teste só lê.
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
@DisplayName("Seed da demonstracao da banca (#315)")
class SinaisNoSeedDemonstracaoIntegrationTest {

  private static final Logger log =
      LoggerFactory.getLogger(SinaisNoSeedDemonstracaoIntegrationTest.class);

  @Autowired private NotaFiscalRepository notaRepository;
  @Autowired private SinaisNotaConferirService sinaisService;

  @Test
  @DisplayName("so o ICMS_A_DEFINIR das notas feitas para ele; nenhum outro sinal (falso positivo zero)")
  void semFalsoPositivo() {
    List<NotaFiscal> proprias = notaRepository.findAll().stream()
        .filter(n -> n.getOrigem() == OrigemNotaFiscal.PROPRIA)
        .toList();
    List<SinalNota> sinais = new ArrayList<>();
    for (NotaFiscal n : proprias) {
      sinais.addAll(sinaisService.sinais(n.getProdutor().getId(),
          new NotaParaSinal(n.getId(), n.getOrigem(), n.getStatus(), n.getDataEmissao(),
              n.getDestinatarioCpfCnpj(), n.getValorTotalNota())));
    }
    Map<SinalNota.CodigoSinalNota, Long> porSinal = sinais.stream()
        .collect(Collectors.groupingBy(SinalNota::codigo, Collectors.counting()));
    log.info("#276 falso positivo no seed: {} notas proprias, sinais por codigo = {}",
        proprias.size(), porSinal);

    assertThat(proprias).hasSizeGreaterThan(80);
    // ICMS_A_DEFINIR so nas notas feitas para mostra-lo: as vendas sem CST de ICMS defensavel
    // (milho ao pecuarista sem IE e touro reprodutor interestadual), que ficam rascunho, e a nota
    // com o CFOP 6101 interno de proposito (a com inconsistencias). Nenhum outro sinal.
    assertThat(sinais).filteredOn(s -> s.codigo() != SinalNota.CodigoSinalNota.ICMS_A_DEFINIR)
        .isEmpty();
    assertThat(sinais.stream().map(SinalNota::descricaoItem).distinct().toList())
        .containsExactlyInAnyOrder("Milho em grao a granel, segunda safra 2026",
            "Touro Nelore PO reprodutor, com registro genealogico",
            "Tomate italiano extra AA, caixa 20 kg");
    assertThat(proprias).filteredOn(n -> !sinaisService.sinais(n.getProdutor().getId(),
            new NotaParaSinal(n.getId(), n.getOrigem(), n.getStatus(), n.getDataEmissao(),
                n.getDestinatarioCpfCnpj(), n.getValorTotalNota())).isEmpty())
        .extracting(NotaFiscal::getStatus)
        .containsOnly(br.com.agrocontabil.model.StatusNotaFiscal.RASCUNHO,
            br.com.agrocontabil.model.StatusNotaFiscal.COM_INCONSISTENCIAS);
  }
}
