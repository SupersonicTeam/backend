package br.com.agrocontabil.assistente.rag;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.rag.CasoEspecial.Artigo;
import br.com.agrocontabil.assistente.rag.CasoEspecial.Faixa;
import br.com.agrocontabil.common.NormalizacaoBusca;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Faixas de caso especial da LC 214/2025 (issue #436). */
class CasoEspecialTest {

  /** sha256 da tabela ampliada entregue com a issue (conferido antes de copiar). */
  private static final String SHA256_AMPLIADA =
      "7e427f3e19880a9d8e97f0b4f6fba88b8c5eff18a2765b0036ba213ce0a70c79";

  private static Optional<Faixa> faixa(List<Faixa> faixas, String norma, String dispositivo) {
    return CasoEspecial.artigoParaParticao(norma, dispositivo)
        .flatMap(a -> CasoEspecial.faixaDe(faixas, a));
  }

  @Test
  @DisplayName("Regimes específicos: dez capítulos, sem buraco, do art. 172 ao 307")
  void regimesEspecificosCobremOTitulo() {
    List<Faixa> faixas = CasoEspecial.REGIMES_ESPECIFICOS;

    assertThat(faixas).hasSize(10);
    assertThat(faixas.get(0).primeiro()).isEqualTo(172);
    assertThat(faixas.get(faixas.size() - 1).ultimo()).isEqualTo(307);
    for (int i = 1; i < faixas.size(); i++) {
      assertThat(faixas.get(i).primeiro()).isEqualTo(faixas.get(i - 1).ultimo() + 1);
    }
  }

  @Test
  @DisplayName("Ampliada: os regimes específicos mais as 16 faixas da tabela, na ordem dela")
  void ampliadaSomaATabela() {
    assertThat(CasoEspecial.ampliada()).hasSize(26);
    assertThat(CasoEspecial.ampliada().subList(0, 10)).isEqualTo(CasoEspecial.REGIMES_ESPECIFICOS);
    assertThat(CasoEspecial.ampliada().get(10).primeiro()).isEqualTo(63);
    assertThat(CasoEspecial.ampliada().get(10).ultimo()).isEqualTo(78);
    assertThat(CasoEspecial.ampliada().get(25).primeiro()).isEqualTo(485);
  }

  @Test
  @DisplayName("A tabela ampliada do recurso é a entregue, byte a byte")
  void tabelaAmpliadaSemMudanca() throws Exception {
    try (InputStream entrada = getClass().getResourceAsStream(CasoEspecial.RECURSO_AMPLIADA)) {
      assertThat(entrada).isNotNull();
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(entrada.readAllBytes());
      assertThat(HexFormat.of().formatHex(hash)).isEqualTo(SHA256_AMPLIADA);
    }
  }

  @Test
  @DisplayName("Artigo da LC 214: número e sufixo; anexo e outra norma não são artigo")
  void artigoDaLc214() {
    assertThat(CasoEspecial.artigoDaLc214("LC 214/2025", "art. 98-A, § 1º"))
        .contains(new Artigo(98, "A"));
    assertThat(CasoEspecial.artigoDaLc214("LC 214/2025", "art. 7º-A"))
        .contains(new Artigo(7, "A"));
    assertThat(CasoEspecial.artigoDaLc214("LC 214/2025", "art. 180, § 1º"))
        .contains(new Artigo(180, ""));
    assertThat(CasoEspecial.artigoDaLc214("LC 214/2025", "Anexo IX, item 2")).isEmpty();
    assertThat(CasoEspecial.artigoDaLc214("Lei 8.212/1991", "art. 25")).isEmpty();
    assertThat(CasoEspecial.artigoDaLc214("LC 214/2025", null)).isEmpty();
  }

  @Test
  @DisplayName("Redação dada pela LC 227/2026 (art. 174) conta como o artigo M da LC 214")
  void redacaoDaLc227ContaComoOArtigoDaLc214() {
    assertThat(CasoEspecial.artigoParaParticao("LC 227/2026", "art. 174 (redação do art. 180, § 1º)"))
        .contains(new Artigo(180, ""));
    assertThat(CasoEspecial.artigoParaParticao("LC 227/2026", "art. 174 (redação do art. 6º)"))
        .contains(new Artigo(6, ""));
    assertThat(CasoEspecial.artigoParaParticao("LC 227/2026", "art. 174 (redação do art. 471-A)"))
        .contains(new Artigo(471, "A"));
    assertThat(faixa(CasoEspecial.REGIMES_ESPECIFICOS, "LC 227/2026",
        "art. 174 (redação do art. 252, § 5º-A)").map(Faixa::nome)).contains("bens imóveis");
  }

  @Test
  @DisplayName("Redação de outra lei pela LC 227 e o texto próprio dela não entram")
  void redacaoDeOutraLeiNaoEntra() {
    // Art. 165 da LC 227/2026 altera o CTN: o "art. 180" ali não é o da LC 214.
    assertThat(CasoEspecial.artigoParaParticao("LC 227/2026", "art. 165 (redação do art. 180)"))
        .isEmpty();
    assertThat(CasoEspecial.artigoParaParticao("LC 227/2026", "art. 180, inciso I")).isEmpty();
    assertThat(CasoEspecial.artigoParaParticao("EC 132/2023",
        "art. 1º (redação do art. 156-A, § 1º, inciso V)")).isEmpty();
  }

  @Test
  @DisplayName("Sufixo: entra pelo número inteiro, exceto em faixa de um artigo só")
  void sufixo() {
    assertThat(faixa(CasoEspecial.ampliada(), "LC 214/2025", "art. 98-B").map(Faixa::primeiro))
        .contains(84);
    assertThat(faixa(CasoEspecial.ampliada(), "LC 214/2025", "art. 471, § 1º").map(Faixa::primeiro))
        .contains(471);
    assertThat(faixa(CasoEspecial.ampliada(), "LC 214/2025", "art. 471-A")).isEmpty();
    assertThat(faixa(CasoEspecial.ampliada(), "LC 214/2025", "art. 471-F, inciso II")).isEmpty();
    assertThat(faixa(CasoEspecial.REGIMES_ESPECIFICOS, "LC 214/2025", "art. 218-A")
        .map(Faixa::nome)).contains("serviços financeiros");
  }

  @Test
  @DisplayName("Limites: o 171 e o 308 ficam fora dos regimes específicos; 172 e 307 dentro")
  void limitesDosRegimes() {
    List<Faixa> regimes = CasoEspecial.REGIMES_ESPECIFICOS;

    assertThat(faixa(regimes, "LC 214/2025", "art. 171")).isEmpty();
    assertThat(faixa(regimes, "LC 214/2025", "art. 172, inciso III")).isPresent();
    assertThat(faixa(regimes, "LC 214/2025", "art. 307")).isPresent();
    assertThat(faixa(regimes, "LC 214/2025", "art. 308")).isEmpty();
  }

  @Test
  @DisplayName("Caso especial sem setor: só quando a pergunta não cita o setor da faixa do trecho")
  void casoEspecialSemSetor() {
    String semSetor = NormalizacaoBusca.normalizar("Quando o imposto da venda de feijão é devido?");
    String comSetor = NormalizacaoBusca.normalizar("Como fica o ALUGUEL de um galpão?");
    List<Faixa> regimes = CasoEspecial.REGIMES_ESPECIFICOS;

    assertThat(CasoEspecial.casoEspecialSemSetor(regimes, "LC 214/2025", "art. 252", semSetor))
        .isTrue();
    assertThat(CasoEspecial.casoEspecialSemSetor(regimes, "LC 214/2025", "art. 252", comSetor))
        .isFalse();
    assertThat(CasoEspecial.casoEspecialSemSetor(regimes, "LC 214/2025", "art. 173", comSetor))
        .isTrue();
    assertThat(CasoEspecial.casoEspecialSemSetor(regimes, "LC 214/2025", "art. 12", semSetor))
        .isFalse();
    assertThat(CasoEspecial.casoEspecialSemSetor(regimes, "Lei 8.212/1991", "art. 25", semSetor))
        .isFalse();
  }

  @Test
  @DisplayName("Regex da ampliada (importação) casa a pergunta normalizada, sem acento nem caixa")
  void regexDaAmpliada() {
    Faixa importacao = CasoEspecial.ampliada().get(10);

    assertThat(importacao.setorCitado(NormalizacaoBusca.normalizar("Uma IMPORTAÇÃO de peças")))
        .isTrue();
    assertThat(importacao.setorCitado(NormalizacaoBusca.normalizar("Venda de feijão na cidade")))
        .isFalse();
  }
}
