package br.com.agrocontabil.assistente.fidelidade;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agrocontabil.assistente.GlossarioProdutor;
import br.com.agrocontabil.assistente.rag.TrechoRecuperado;
import br.com.agrocontabil.model.DesfechoFidelidadeLei;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Conferência de fidelidade da rota de lei (#269): cada frase do modelo vem com a passagem do trecho
 * que a sustenta, e o código confere a cópia, o sentido (polaridade, número, cobertura) e o nível do
 * dispositivo que a citação deve apontar. Textos do art. 408 da LC 214/2025 como o fatiador os grava
 * (caput + parágrafo + inciso + alínea).
 */
class ConferenciaFidelidadeLeiTest {

  private static final String URL = "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214.htm";
  private static final String CAPUT_408 = "Art. 408. Sem prejuízo das demais regras estabelecidas "
      + "nesta Lei Complementar, durante o período de transição para o IBS e a CBS, observar-se-á o "
      + "disposto neste artigo.";
  private static final String PARAGRAFO_4 = "§ 4º Durante o período de 2029 a 2032:";
  private static final String INCISO_II = "II - caso não tenha se aperfeiçoado, até 31 de dezembro "
      + "de 2032, o elemento temporal da hipótese de incidência do ICMS ou do ISS:";
  private static final String ALINEA_B = "b) será devido exclusivamente o IBS na operação.";
  private static final String INCISO_I = "I - caso a mesma operação configure, em anos-calendários "
      + "distintos, fatos geradores do ICMS ou do ISS e do IBS, prevalecerá a legislação vigente no "
      + "ano-calendário da primeira ocorrência em relação aos referidos impostos; e";

  private static final double COBERTURA = 0.7;

  private final ConferenciaFidelidadeLei conferencia =
      new ConferenciaFidelidadeLei(GlossarioProdutor.padrao());

  private static TrechoRecuperado trecho(String marca, String id, String dispositivo,
      String texto) {
    return new TrechoRecuperado(marca, id, "LC 214/2025", dispositivo, URL, texto, 0.8);
  }

  private static TrechoRecuperado alineaB(String marca) {
    return trecho(marca, "id-alinea-b", "art. 408, § 4º, inciso II, alínea b",
        CAPUT_408 + " " + PARAGRAFO_4 + " " + INCISO_II + " " + ALINEA_B);
  }

  private static TrechoRecuperado incisoI(String marca) {
    return trecho(marca, "id-inciso-i", "art. 408, § 4º, inciso I",
        CAPUT_408 + " " + PARAGRAFO_4 + " " + INCISO_I);
  }

  private static String json(String frase, String trecho, String copia) {
    return "{\"frases\": [{\"frase\": \"" + frase + "\", \"evidencias\": [{\"trecho\": \""
        + trecho + "\", \"copia\": \"" + copia + "\"}]}]}";
  }

  private ConferenciaFidelidadeLei.Resultado conferir(String resposta,
      TrechoRecuperado... trechos) {
    return conferencia.conferir(resposta, List.of(trechos), COBERTURA);
  }

  @Test
  @DisplayName("frase sustentada pela evidência literal passa, com a marca no fim da frase")
  void frasesSustentadasPassam() {
    ConferenciaFidelidadeLei.Resultado r = conferir(json(
        "Nesse caso, será devido só o IBS na operação.", "T1",
        "será devido exclusivamente o IBS na operação"), alineaB("T1"));

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.APROVADA);
    assertThat(r.aprovada()).isTrue();
    assertThat(r.texto()).isEqualTo("Nesse caso, será devido só o IBS na operação [T1].");
    assertThat(r.trechos()).extracting(TrechoRecuperado::marca).containsExactly("T1");
  }

  @Test
  @DisplayName("sentido invertido: a evidência nega e a frase não (alínea b do art. 408, § 4º, II)")
  void inversaoDeSentidoReprova() {
    // O exemplo da leitura cega de 10/11: a alinea diz "caso NAO tenha se aperfeicoado".
    ConferenciaFidelidadeLei.Resultado r = conferir(json(
        "Só o IBS é devido se o elemento temporal do ICMS ou do ISS tiver se aperfeiçoado até 31 "
            + "de dezembro de 2032.", "T1",
        "caso não tenha se aperfeiçoado, até 31 de dezembro de 2032, o elemento temporal da "
            + "hipótese de incidência do ICMS ou do ISS: b) será devido exclusivamente o IBS na "
            + "operação"), alineaB("T1"));

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_POLARIDADE);
    assertThat(r.aprovada()).isFalse();
    assertThat(r.texto()).isNull();
    assertThat(r.marcasCitadas()).containsExactly("T1");
  }

  @Test
  @DisplayName("falso descarte 1: número no mesmo dispositivo, fora da cópia, passa (data do caput)")
  void numeroNoMesmoDispositivoPassa() {
    TrechoRecuperado cbs = trecho("T1", "id-cbs", "art. 9º", "Art. 9º Em relação aos fatos "
        + "geradores ocorridos de 1º de janeiro a 31 de dezembro de 2026, a CBS será cobrada "
        + "mediante aplicação da alíquota de 0,9% (nove décimos por cento).");
    ConferenciaFidelidadeLei.Resultado r = conferir(json(
        "De 1º de janeiro a 31 de dezembro de 2026, a CBS será cobrada com alíquota de 0,9%.",
        "T1", "a CBS será cobrada mediante aplicação da alíquota de 0,9%"), cbs);

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.APROVADA);
    // O numero que nao esta no dispositivo segue reprovando.
    assertThat(conferir(json("Em 2027, a CBS será cobrada com alíquota de 0,9%.", "T1",
        "a CBS será cobrada mediante aplicação da alíquota de 0,9%"), cbs).desfecho())
        .isEqualTo(DesfechoFidelidadeLei.REPROVADA_NUMERO);
  }

  @Test
  @DisplayName("falso descarte 2: a frase resume o dispositivo da cópia, mesmo com a cópia curta")
  void coberturaPeloDispositivoDaCopia() {
    TrechoRecuperado lista = trecho("T1", "id-lista", "art. 138, § 4º", "Art. 138. Ficam reduzidas "
        + "em 60% as alíquotas dos insumos agropecuários do Anexo IX. § 4º A lista do Anexo IX "
        + "será revisada a cada 120 dias, tão somente para inclusão de insumos com as mesmas "
        + "finalidades dos já contemplados.");
    ConferenciaFidelidadeLei.Resultado r = conferir(json(
        "A lista do Anexo IX é revisada a cada 120 dias, só para incluir insumos com as mesmas "
            + "finalidades dos já contemplados.", "T1",
        "A lista do Anexo IX será revisada a cada 120 dias, tão somente para inclusão de insumos"),
        lista);

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.APROVADA);
    // O que nao esta nem no dispositivo da copia (o caput, aqui, fica de fora) segue reprovando.
    assertThat(conferir(json("A lista do Anexo IX é revisada a cada 120 dias por ato conjunto do "
            + "ministro da fazenda com estados e municípios.", "T1",
        "A lista do Anexo IX será revisada a cada 120 dias"), lista).desfecho())
        .isEqualTo(DesfechoFidelidadeLei.REPROVADA_COBERTURA);
  }

  @Test
  @DisplayName("falso descarte 3: a frase que não retoma a condição negada da cópia não a inverte")
  void condicaoNegadaOmitidaPassa() {
    ConferenciaFidelidadeLei.Resultado r = conferir(json(
        "Nesse caso, será devido só o IBS na operação.", "T1",
        "caso não tenha se aperfeiçoado, até 31 de dezembro de 2032, o elemento temporal da "
            + "hipótese de incidência do ICMS ou do ISS: b) será devido exclusivamente o IBS na "
            + "operação"), alineaB("T1"));

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.APROVADA);
  }

  @Test
  @DisplayName("falso descarte 4: 'ausência de vinculação' conta como negação de 'não tem'")
  void ausenciaContaComoNegacao() {
    TrechoRecuperado comite = trecho("T1", "id-comite", "art. 480, § 4º", "Art. 480. O Comitê "
        + "Gestor do IBS é entidade pública. § 4º O Comitê Gestor terá sua atuação caracterizada "
        + "pela ausência de vinculação, tutela ou subordinação hierárquica a qualquer órgão da "
        + "administração pública.");
    ConferenciaFidelidadeLei.Resultado r = conferir(json(
        "O Comitê Gestor não tem vinculação ou subordinação hierárquica a órgão da administração "
            + "pública.", "T1", "pela ausência de vinculação, tutela ou subordinação hierárquica a "
            + "qualquer órgão da administração pública"), comite);

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.APROVADA);
  }

  @Test
  @DisplayName("NAO_ENCONTREI numa frase ao lado de outras: só ela sai; sozinho, não há frase")
  void naoEncontreiParcial() {
    String parcial = "{\"frases\": ["
        + "{\"frase\": \"Nesse caso, será devido só o IBS na operação.\", \"evidencias\": ["
        + "{\"trecho\": \"T1\", \"copia\": \"será devido exclusivamente o IBS na operação\"}]},"
        + "{\"frase\": \"NAO_ENCONTREI\", \"evidencias\": []}]}";
    ConferenciaFidelidadeLei.Resultado r = conferir(parcial, alineaB("T1"));

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.APROVADA);
    assertThat(r.texto()).isEqualTo("Nesse caso, será devido só o IBS na operação [T1].");
    assertThat(ConferenciaFidelidadeLei.temFrases(parcial)).isTrue();
    assertThat(ConferenciaFidelidadeLei.temFrases(
        "{\"frases\": [{\"frase\": \"NAO_ENCONTREI\"}]}")).isFalse();
    assertThat(ConferenciaFidelidadeLei.temFrases("NAO_ENCONTREI")).isFalse();
  }

  @Test
  @DisplayName("cópia cortada logo depois do 'não' do dispositivo não sustenta a frase invertida")
  void copiaCortadaDepoisDoNaoReprova() {
    TrechoRecuperado t = trecho("T1", "id", "art. 26", "Art. 26. Não incide o IBS sobre as "
        + "exportações de bens.");
    assertThat(conferir(json("O IBS incide sobre as exportações de bens.", "T1",
        "incide o IBS sobre as exportações de bens"), t).desfecho())
        .isEqualTo(DesfechoFidelidadeLei.REPROVADA_POLARIDADE);
  }

  @Test
  @DisplayName("'sem' em outro ponto da frase não esconde a inversão da condição negada")
  void semNaoEscondeInversao() {
    assertThat(conferir(json("Sem prejuízo das regras, se o elemento temporal tiver se "
            + "aperfeiçoado até 31 de dezembro de 2032, será devido só o IBS.", "T1",
        "caso não tenha se aperfeiçoado, até 31 de dezembro de 2032, o elemento temporal da "
            + "hipótese de incidência do ICMS ou do ISS: b) será devido exclusivamente o IBS na "
            + "operação"), alineaB("T1")).desfecho())
        .isEqualTo(DesfechoFidelidadeLei.REPROVADA_POLARIDADE);
  }

  @Test
  @DisplayName("frase com negação que a evidência não tem reprova")
  void negacaoQueAEvidenciaNaoTemReprova() {
    ConferenciaFidelidadeLei.Resultado r = conferir(json(
        "O IBS não é devido na operação.", "T1",
        "será devido exclusivamente o IBS na operação"), alineaB("T1"));

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_POLARIDADE);
  }

  @Test
  @DisplayName("restrição (só, somente) que a evidência não tem reprova")
  void restricaoQueAEvidenciaNaoTemReprova() {
    ConferenciaFidelidadeLei.Resultado r = conferir(json(
        "Durante a transição para o IBS e a CBS vale somente o disposto no artigo.", "T1",
        "durante o período de transição para o IBS e a CBS, observar-se-á o disposto neste "
            + "artigo"), incisoI("T1"));

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_POLARIDADE);
  }

  @Test
  @DisplayName("afirma além do trecho: palavras de conteúdo fora da evidência reprovam")
  void afirmacaoAlemDoTrechoReprova() {
    // O exemplo de 10/11: o inciso so trata da ausencia de subordinacao.
    TrechoRecuperado comite = trecho("T1", "id-comite", "art. 480, § 4º, inciso II",
        "Art. 480. O Comitê Gestor do IBS é entidade pública sob regime especial. § 4º A atuação "
            + "do Comitê Gestor caracteriza-se: II - pela ausência de vinculação hierárquica a "
            + "qualquer órgão da administração pública.");
    ConferenciaFidelidadeLei.Resultado r = conferir(json(
        "O Comitê Gestor cuida da cobrança e da fiscalização junto com estados e municípios.",
        "T1", "pela ausência de vinculação hierárquica a qualquer órgão da administração pública"),
        comite);

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_COBERTURA);
  }

  @Test
  @DisplayName("evidência que não está escrita no trecho (paráfrase) reprova")
  void evidenciaNaoLiteralReprova() {
    ConferenciaFidelidadeLei.Resultado r = conferir(json(
        "Será devido só o IBS na operação.", "T1", "apenas o IBS será cobrado na operação"),
        alineaB("T1"));

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_EVIDENCIA_NAO_LITERAL);
  }

  @Test
  @DisplayName("número da frase fora da evidência e do dispositivo dela reprova")
  void numeroForaDaEvidenciaReprova() {
    ConferenciaFidelidadeLei.Resultado r = conferir(json(
        "Até 2033 será devido só o IBS na operação.", "T1",
        "será devido exclusivamente o IBS na operação"), alineaB("T1"));

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_NUMERO);
  }

  @Test
  @DisplayName("falso descarte 5: o período do parágrafo de cima vale como contexto da alínea")
  void numeroDoNivelDeCimaPassa() {
    ConferenciaFidelidadeLei.Resultado r = conferir(json(
        "De 2029 a 2032, nesse caso, será devido só o IBS na operação.", "T1",
        "será devido exclusivamente o IBS na operação"), alineaB("T1"));

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.APROVADA);
  }

  @Test
  @DisplayName("o 'não' de uma condição de cima não sustenta a negação da frase sobre a alínea")
  void negacaoDeCimaNaoSustentaAFrase() {
    // O inciso II tem "caso nao tenha se aperfeicoado"; a alinea b, citada, nao nega nada.
    assertThat(conferir(json("O IBS não é devido na operação.", "T1",
        "será devido exclusivamente o IBS na operação"), alineaB("T1")).desfecho())
        .isEqualTo(DesfechoFidelidadeLei.REPROVADA_POLARIDADE);
  }

  @Test
  @DisplayName("texto fora do formato, JSON sem frase ou frase sem texto reprova pelo formato")
  void formatoInvalidoReprova() {
    assertThat(conferir("Será devido só o IBS na operação [T1].", alineaB("T1")).desfecho())
        .isEqualTo(DesfechoFidelidadeLei.REPROVADA_FORMATO);
    assertThat(conferir("{\"frases\": []}", alineaB("T1")).desfecho())
        .isEqualTo(DesfechoFidelidadeLei.REPROVADA_FORMATO);
    assertThat(conferir("{\"frases\": [{\"frase\": \"  \", \"evidencias\": []}]}",
        alineaB("T1")).desfecho()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_FORMATO);
    assertThat(conferir("{\"frases\": \"texto\"}", alineaB("T1")).desfecho())
        .isEqualTo(DesfechoFidelidadeLei.REPROVADA_FORMATO);
    assertThat(conferir(null, alineaB("T1")).desfecho())
        .isEqualTo(DesfechoFidelidadeLei.REPROVADA_FORMATO);
  }

  @Test
  @DisplayName("frase sem evidência, ou com marca inexistente, reprova")
  void fraseSemEvidenciaReprova() {
    assertThat(conferir("{\"frases\": [{\"frase\": \"Será devido só o IBS.\"}]}",
        alineaB("T1")).desfecho()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_SEM_EVIDENCIA);
    assertThat(conferir(json("Será devido só o IBS na operação.", "T9",
        "será devido exclusivamente o IBS na operação"), alineaB("T1")).desfecho())
        .isEqualTo(DesfechoFidelidadeLei.REPROVADA_SEM_EVIDENCIA);
    assertThat(conferir(json("Será devido só o IBS na operação.", "T1", "   "),
        alineaB("T1")).desfecho()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_SEM_EVIDENCIA);
  }

  @Test
  @DisplayName("evidência só no caput: a citação sobe para o artigo, com o texto do caput")
  void evidenciaNoCaputCitaOArtigo() {
    ConferenciaFidelidadeLei.Resultado r = conferir(json(
        "Durante a transição para o IBS e a CBS vale o disposto no artigo.", "T1",
        "durante o período de transição para o IBS e a CBS, observar-se-á o disposto neste "
            + "artigo"), incisoI("T1"));

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.APROVADA_CITACAO_CORRIGIDA);
    assertThat(r.texto()).endsWith("[T2].");
    assertThat(r.trechos()).hasSize(2);
    TrechoRecuperado caput = r.trechos().get(1);
    assertThat(caput.marca()).isEqualTo("T2");
    assertThat(caput.dispositivo()).isEqualTo("art. 408");
    assertThat(caput.texto()).isEqualTo(CAPUT_408);
    assertThat(caput.norma()).isEqualTo("LC 214/2025");
    assertThat(caput.url()).isEqualTo(URL);
    // Sem o trecho do caput na busca, o id e o do trecho em que a evidencia foi achada.
    assertThat(caput.id()).isEqualTo("id-inciso-i");
  }

  @Test
  @DisplayName("evidência no caput com o trecho do caput na busca: a citação usa esse trecho")
  void evidenciaNoCaputUsaOTrechoDaBusca() {
    TrechoRecuperado caput = trecho("T2", "id-caput", "art. 408", CAPUT_408);
    ConferenciaFidelidadeLei.Resultado r = conferir(json(
        "Durante a transição para o IBS e a CBS vale o disposto no artigo.", "T1",
        "durante o período de transição para o IBS e a CBS, observar-se-á o disposto neste "
            + "artigo"), incisoI("T1"), caput);

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.APROVADA_CITACAO_CORRIGIDA);
    assertThat(r.texto()).endsWith("[T2].");
    assertThat(r.trechos()).extracting(TrechoRecuperado::id).containsExactly("id-inciso-i",
        "id-caput");
  }

  @Test
  @DisplayName("evidência no parágrafo, citação no inciso: sobe para o parágrafo")
  void evidenciaNoParagrafoCitaOParagrafo() {
    ConferenciaFidelidadeLei.Resultado r = conferir(json(
        "A regra vale durante o período de 2029 a 2032.", "T1",
        "Durante o período de 2029 a 2032"), incisoI("T1"));

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.APROVADA_CITACAO_CORRIGIDA);
    assertThat(r.trechos().get(1).dispositivo()).isEqualTo("art. 408, § 4º");
    assertThat(r.trechos().get(1).texto()).isEqualTo(CAPUT_408 + " " + PARAGRAFO_4);
  }

  @Test
  @DisplayName("evidência que toca o inciso fica no inciso, mesmo começando no parágrafo")
  void evidenciaAteOIncisoFicaNoInciso() {
    ConferenciaFidelidadeLei.Resultado r = conferir(json(
        "De 2029 a 2032, prevalece a legislação vigente no ano-calendário da primeira ocorrência.",
        "T1", "Durante o período de 2029 a 2032 ... prevalecerá a legislação vigente no "
            + "ano-calendário da primeira ocorrência"), incisoI("T1"));

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.APROVADA);
    assertThat(r.texto()).endsWith("[T1].");
  }

  @Test
  @DisplayName("fragmentos com reticências fora de ordem não valem como cópia")
  void fragmentosForaDeOrdemReprovam() {
    ConferenciaFidelidadeLei.Resultado r = conferir(json(
        "De 2029 a 2032, prevalece a legislação vigente no ano-calendário da primeira ocorrência.",
        "T1", "prevalecerá a legislação vigente no ano-calendário da primeira ocorrência ... "
            + "Durante o período de 2029 a 2032"), incisoI("T1"));

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_EVIDENCIA_NAO_LITERAL);
  }

  @Test
  @DisplayName("evidência escrita só em outro trecho: a marca vai para ele")
  void evidenciaEmOutroTrechoTrocaAMarca() {
    ConferenciaFidelidadeLei.Resultado r = conferir(json(
        "Nesse caso, será devido só o IBS na operação.", "T1",
        "será devido exclusivamente o IBS na operação"), incisoI("T1"), alineaB("T2"));

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.APROVADA_CITACAO_CORRIGIDA);
    assertThat(r.texto()).endsWith("[T2].");
  }

  @Test
  @DisplayName("sigla do glossário e o nome por extenso valem como a mesma palavra")
  void siglaEExtensoSaoAMesmaPalavra() {
    ConferenciaFidelidadeLei.Resultado r = conferir(json(
        "Nesse caso, será devido só o imposto sobre bens e serviços na operação.", "T1",
        "será devido exclusivamente o IBS na operação"), alineaB("T1"));

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.APROVADA);
  }

  @Test
  @DisplayName("palavras da mesma raiz casam ('redução' e 'reduzidas', 'cobrança' e 'cobrar')")
  void mesmaRaizCasa() {
    TrechoRecuperado t = trecho("T1", "id", "art. 138", "Art. 138. Ficam reduzidas em 60% as "
        + "alíquotas do IBS e da CBS, e o Comitê Gestor pode cobrar a diferença.");
    ConferenciaFidelidadeLei.Resultado r = conferir(json(
        "A redução das alíquotas é de 60%, com a cobrança da diferença pelo Comitê Gestor.", "T1",
        "Ficam reduzidas em 60% as alíquotas do IBS e da CBS, e o Comitê Gestor pode cobrar a "
            + "diferença"), t);

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.APROVADA);
  }

  @Test
  @DisplayName("JSON entre cercas de código e com texto em volta é lido; marcas na frase saem")
  void jsonComCercaELido() {
    ConferenciaFidelidadeLei.Resultado r = conferir("Aqui está:\n```json\n" + json(
        "Nesse caso, será devido só o IBS na operação [T1].", "T1",
        "será devido exclusivamente o IBS na operação") + "\n```", alineaB("T1"));

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.APROVADA);
    assertThat(r.texto()).isEqualTo("Nesse caso, será devido só o IBS na operação [T1].");
  }

  @Test
  @DisplayName("várias frases e várias evidências: cada frase com as próprias marcas")
  void variasFrases() {
    String resposta = "{\"frases\": ["
        + "{\"frase\": \"A regra vale durante o período de 2029 a 2032\", \"evidencias\": ["
        + "{\"trecho\": \"T1\", \"copia\": \"Durante o período de 2029 a 2032\"}]},"
        + "{\"frase\": \"Nesse caso, será devido só o IBS na operação!\", \"evidencias\": ["
        + "{\"trecho\": \"T2\", \"copia\": \"será devido exclusivamente o IBS na operação\"},"
        + "{\"trecho\": \"T2\", \"copia\": \"será devido exclusivamente o IBS\"}]}]}";

    ConferenciaFidelidadeLei.Resultado r = conferir(resposta, alineaB("T1"), alineaB("T2"));

    // T1 e a alinea, mas a evidencia esta no paragrafo: sobe; T2 fica.
    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.APROVADA_CITACAO_CORRIGIDA);
    assertThat(r.texto()).isEqualTo("A regra vale durante o período de 2029 a 2032 [T3]. Nesse "
        + "caso, será "
        + "devido só o IBS na operação [T2]!");
    assertThat(r.trechos().get(2).dispositivo()).isEqualTo("art. 408, § 4º");
  }

  @Test
  @DisplayName("frase sem palavra de conteúdo ('Sim.') sai do texto; sozinha, a resposta reprova")
  void fraseSemConteudoSaiDoTexto() {
    // Sozinha: "Sim." nao afirma nada que a copia sustente.
    assertThat(conferir(json("Sim.", "T1", "será devido exclusivamente o IBS na operação"),
        alineaB("T1")).desfecho()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_COBERTURA);

    String resposta = "{\"frases\": ["
        + "{\"frase\": \"Sim.\", \"evidencias\": [{\"trecho\": \"T1\", \"copia\": "
        + "\"será devido exclusivamente o IBS\"}]},"
        + "{\"frase\": \"Nesse caso, será devido só o IBS na operação.\", \"evidencias\": ["
        + "{\"trecho\": \"T1\", \"copia\": \"será devido exclusivamente o IBS na operação\"}]}]}";
    ConferenciaFidelidadeLei.Resultado r = conferir(resposta, alineaB("T1"));

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.APROVADA);
    assertThat(r.texto()).isEqualTo("Nesse caso, será devido só o IBS na operação [T1].");
  }

  @Test
  @DisplayName("frase sem conteúdo continua passando pela polaridade ('Não.' sem negação na cópia)")
  void fraseSemConteudoAindaConfereAPolaridade() {
    String resposta = "{\"frases\": ["
        + "{\"frase\": \"Não.\", \"evidencias\": [{\"trecho\": \"T1\", \"copia\": "
        + "\"será devido exclusivamente o IBS\"}]},"
        + "{\"frase\": \"Nesse caso, será devido só o IBS na operação.\", \"evidencias\": ["
        + "{\"trecho\": \"T1\", \"copia\": \"será devido exclusivamente o IBS na operação\"}]}]}";

    assertThat(conferir(resposta, alineaB("T1")).desfecho())
        .isEqualTo(DesfechoFidelidadeLei.REPROVADA_POLARIDADE);
  }

  @Test
  @DisplayName("ordinal ('1º') é número: conferido, e o '1°' copiado casa com o '1º' do trecho")
  void ordinalEhNumero() {
    TrechoRecuperado t = trecho("T1", "id", "art. 5º", "Art. 5º A CBS será exigida a partir de 1º "
        + "de janeiro de 2027.");
    assertThat(conferir(json("A CBS será exigida a partir de 2º de janeiro de 2027.", "T1",
        "A CBS será exigida a partir de 1º de janeiro de 2027"), t).desfecho())
        .isEqualTo(DesfechoFidelidadeLei.REPROVADA_NUMERO);
    assertThat(conferir(json("A CBS será exigida a partir de 1º de janeiro de 2027.", "T1",
        "A CBS será exigida a partir de 1° de janeiro de 2027"), t).desfecho())
        .isEqualTo(DesfechoFidelidadeLei.APROVADA);
  }

  @Test
  @DisplayName("marca da evidência em minúscula, como número ou em lista é lida")
  void marcaFlexivel() {
    String copia = "será devido exclusivamente o IBS na operação";
    String frase = "Nesse caso, será devido só o IBS na operação.";
    for (String marca : List.of("\"t1\"", "1", "\"[T1]\"", "\"T1, T2\"")) {
      String resposta = "{\"frases\": [{\"frase\": \"" + frase + "\", \"evidencias\": [{"
          + "\"trecho\": " + marca + ", \"copia\": \"" + copia + "\"}]}]}";
      assertThat(conferir(resposta, alineaB("T1"), incisoI("T2")).desfecho())
          .as(marca).isEqualTo(DesfechoFidelidadeLei.APROVADA);
    }
    assertThat(conferir("{\"frases\": [{\"frase\": \"" + frase + "\", \"evidencias\": [{"
        + "\"trecho\": \"X1\", \"copia\": \"" + copia + "\"}]}]}", alineaB("T1")).desfecho())
        .isEqualTo(DesfechoFidelidadeLei.REPROVADA_SEM_EVIDENCIA);
  }

  @Test
  @DisplayName("JSON malformado entre chaves, ou objeto sem 'frases', reprova pelo formato")
  void jsonMalformadoReprova() {
    for (String resposta : List.of("{frases: [", "{\"outra\": 1}", "texto {\"frases\": [}",
        "{\"frases\": [\"texto\"]}")) {
      assertThat(conferir(resposta, alineaB("T1")).desfecho()).as(resposta)
          .isEqualTo(DesfechoFidelidadeLei.REPROVADA_FORMATO);
    }
  }

  @Test
  @DisplayName("isenção na frase conta como negação da evidência ('não incide')")
  void isencaoEquivaleANegacao() {
    TrechoRecuperado t = trecho("T1", "id", "art. 26", "Art. 26. Não incide o IBS e a CBS sobre "
        + "as exportações de bens.");
    ConferenciaFidelidadeLei.Resultado r = conferir(json(
        "As exportações de bens são isentas do IBS e da CBS.", "T1",
        "Não incide o IBS e a CBS sobre as exportações de bens"), t);

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.APROVADA);
  }

  @Test
  @DisplayName("rótulo fora da forma simples (item de anexo, redação de emenda) não muda")
  void rotuloComplexoNaoMuda() {
    TrechoRecuperado anexo = trecho("T1", "id-anexo", "Anexo IX, item 5",
        "ANEXO IX - INSUMOS AGROPECUÁRIOS. Item 5: Fertilizantes - 31.01");
    TrechoRecuperado emenda = trecho("T2", "id-emenda", "art. 1º (redação do art. 156-A, § 1º)",
        "Art. 1º A Constituição passa a vigorar com as seguintes alterações: Art. 156-A. Lei "
            + "complementar instituirá imposto sobre bens e serviços. § 1º O imposto será "
            + "informado pelo princípio da neutralidade.");
    ConferenciaFidelidadeLei.Resultado r = conferencia.conferir("{\"frases\": ["
        + "{\"frase\": \"Os insumos agropecuários estão no anexo.\", \"evidencias\": ["
        + "{\"trecho\": \"T1\", \"copia\": \"ANEXO IX - INSUMOS AGROPECUÁRIOS\"}]},"
        + "{\"frase\": \"Lei complementar instituirá o imposto.\", \"evidencias\": ["
        + "{\"trecho\": \"T2\", \"copia\": \"Lei complementar instituirá imposto\"}]}]}",
        List.of(anexo, emenda), COBERTURA);

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.APROVADA);
    assertThat(r.trechos()).hasSize(2);
  }
  private static final Set<DesfechoFidelidadeLei> NUMERO_E_POLARIDADE = Set.of(
      DesfechoFidelidadeLei.REPROVADA_NUMERO, DesfechoFidelidadeLei.REPROVADA_POLARIDADE);

  private static TrechoRecuperado cbs() {
    return trecho("T1", "id-cbs", "art. 9º", "Art. 9º Em relação aos fatos geradores ocorridos de "
        + "1º de janeiro a 31 de dezembro de 2026, a CBS será cobrada mediante aplicação da alíquota "
        + "de 0,9% (nove décimos por cento).");
  }

  @Test
  @DisplayName("resgate: a frase reprovada por número segue, com o texto, para o juiz decidir")
  void resgateDeNumero() {
    String resposta = "{\"frases\": [{\"frase\": \"Em 2027, a CBS será cobrada com alíquota de "
        + "0,9%.\", \"evidencias\": [{\"trecho\": \"T1\", \"copia\": \"a CBS será cobrada mediante "
        + "aplicação da alíquota de 0,9%\"}]}, {\"frase\": \"A CBS é cobrada pela alíquota de 0,9%."
        + "\", \"evidencias\": [{\"trecho\": \"T1\", \"copia\": \"a CBS será cobrada mediante "
        + "aplicação da alíquota de 0,9%\"}]}]}";

    ConferenciaFidelidadeLei.Resultado r =
        conferencia.conferir(resposta, List.of(cbs()), COBERTURA, NUMERO_E_POLARIDADE);

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_NUMERO);
    assertThat(r.aprovada()).isFalse();
    assertThat(r.resgatavel()).isTrue();
    assertThat(r.texto()).isEqualTo("Em 2027, a CBS será cobrada com alíquota de 0,9% [T1]. "
        + "A CBS é cobrada pela alíquota de 0,9% [T1].");
    // O juiz recebe as duas frases, com o dispositivo inteiro.
    assertThat(r.frases()).hasSize(2);
    assertThat(r.frases().get(0).copias()).singleElement().asString().startsWith("Art. 9º");
    // Sem a lista, o comportamento de antes.
    ConferenciaFidelidadeLei.Resultado sem = conferencia.conferir(resposta, List.of(cbs()),
        COBERTURA);
    assertThat(sem.resgatavel()).isFalse();
    assertThat(sem.texto()).isNull();
    assertThat(sem.frases()).isEmpty();
  }

  @Test
  @DisplayName("resgate: polaridade resgatável; o desfecho é o primeiro motivo da camada 1")
  void resgateDePolaridade() {
    ConferenciaFidelidadeLei.Resultado r = conferencia.conferir(json(
        "Só o IBS é devido se o elemento temporal do ICMS ou do ISS tiver se aperfeiçoado até 31 "
            + "de dezembro de 2032.", "T1",
        "caso não tenha se aperfeiçoado, até 31 de dezembro de 2032, o elemento temporal da "
            + "hipótese de incidência do ICMS ou do ISS: b) será devido exclusivamente o IBS na "
            + "operação"), List.of(alineaB("T1")), COBERTURA, NUMERO_E_POLARIDADE);

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_POLARIDADE);
    assertThat(r.resgatavel()).isTrue();
    assertThat(r.frases()).hasSize(1);
  }

  @Test
  @DisplayName("resgate: cobertura só entra se estiver na lista")
  void resgateDeCoberturaSoNaLista() {
    TrechoRecuperado lista = trecho("T1", "id-lista", "art. 138, § 4º", "Art. 138. Ficam reduzidas "
        + "em 60% as alíquotas dos insumos agropecuários do Anexo IX. § 4º A lista do Anexo IX "
        + "será revisada a cada 120 dias.");
    String resposta = json("A lista do Anexo IX é revisada a cada 120 dias por ato conjunto do "
        + "ministro da fazenda com estados e municípios.", "T1",
        "A lista do Anexo IX será revisada a cada 120 dias");

    assertThat(conferencia.conferir(resposta, List.of(lista), COBERTURA, NUMERO_E_POLARIDADE)
        .resgatavel()).isFalse();
    ConferenciaFidelidadeLei.Resultado r = conferencia.conferir(resposta, List.of(lista), COBERTURA,
        Set.of(DesfechoFidelidadeLei.REPROVADA_COBERTURA));
    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_COBERTURA);
    assertThat(r.resgatavel()).isTrue();
  }

  @Test
  @DisplayName("resgate: formato, evidência ausente e cópia não literal seguem eliminatórios")
  void resgateNaoAlcancaOsEstruturais() {
    Set<DesfechoFidelidadeLei> todos = Set.of(DesfechoFidelidadeLei.values());
    assertThat(conferencia.conferir("texto fora do formato", List.of(cbs()), COBERTURA, todos)
        .desfecho()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_FORMATO);
    ConferenciaFidelidadeLei.Resultado naoLiteral = conferencia.conferir(json(
        "A CBS é cobrada pela alíquota de 0,9%.", "T1", "a CBS será cobrada pela alíquota de 1%"),
        List.of(cbs()), COBERTURA, todos);
    assertThat(naoLiteral.desfecho())
        .isEqualTo(DesfechoFidelidadeLei.REPROVADA_EVIDENCIA_NAO_LITERAL);
    assertThat(naoLiteral.resgatavel()).isFalse();

    // Uma frase a resgatar antes de uma estrutural: reprova sem texto, com o primeiro motivo.
    String resposta = "{\"frases\": [{\"frase\": \"Em 2027, a CBS será cobrada com alíquota de "
        + "0,9%.\", \"evidencias\": [{\"trecho\": \"T1\", \"copia\": \"a CBS será cobrada mediante "
        + "aplicação da alíquota de 0,9%\"}]}, {\"frase\": \"A CBS é cobrada.\", \"evidencias\": []}]}";
    ConferenciaFidelidadeLei.Resultado r =
        conferencia.conferir(resposta, List.of(cbs()), COBERTURA, todos);
    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_NUMERO);
    assertThat(r.resgatavel()).isFalse();
    assertThat(r.texto()).isNull();
  }

  @Test
  @DisplayName("resgate: frase sem palavra de conteúdo reprovada não tem o que o juiz julgar")
  void resgateSemConteudoReprova() {
    ConferenciaFidelidadeLei.Resultado r = conferencia.conferir(json("Não.", "T1",
        "a CBS será cobrada mediante aplicação da alíquota de 0,9%"), List.of(cbs()), COBERTURA,
        NUMERO_E_POLARIDADE);

    assertThat(r.desfecho()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_POLARIDADE);
    assertThat(r.resgatavel()).isFalse();
  }
  @Test
  @DisplayName("resgate: reprovação posterior sem resgate grava o primeiro motivo, como a camada 1")
  void resgateMantemOPrimeiroMotivo() {
    // Frase 1 reprova por numero (resgatavel); frase 2 por copia nao literal (sem resgate).
    String resposta = "{\"frases\": [{\"frase\": \"Em 2027, a CBS será cobrada com alíquota de "
        + "0,9%.\", \"evidencias\": [{\"trecho\": \"T1\", \"copia\": \"a CBS será cobrada mediante "
        + "aplicação da alíquota de 0,9%\"}]}, {\"frase\": \"A CBS é cobrada.\", \"evidencias\": "
        + "[{\"trecho\": \"T1\", \"copia\": \"a CBS será cobrada pela alíquota de 1%\"}]}]}";

    ConferenciaFidelidadeLei.Resultado comResgate =
        conferencia.conferir(resposta, List.of(cbs()), COBERTURA, NUMERO_E_POLARIDADE);
    ConferenciaFidelidadeLei.Resultado semResgate =
        conferencia.conferir(resposta, List.of(cbs()), COBERTURA);

    assertThat(semResgate.desfecho()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_NUMERO);
    assertThat(comResgate.desfecho()).isEqualTo(DesfechoFidelidadeLei.REPROVADA_NUMERO);
    assertThat(comResgate.resgatavel()).isFalse();
  }
}
