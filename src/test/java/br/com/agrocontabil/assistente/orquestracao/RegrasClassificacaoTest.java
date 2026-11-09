package br.com.agrocontabil.assistente.orquestracao;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Regras da classificação (issue #265): tabela de casos e zero erros no gabarito. */
class RegrasClassificacaoTest {

  @ParameterizedTest(name = "[{index}] {0} -> {1}")
  @CsvSource(delimiter = '|', nullValues = "NADA", value = {
      // termo de lei, sem dados
      "Como o fato gerador do IBS e definido?                     | LEGISLACAO",
      "O que diz a LC 214 sobre o regime regular?                 | LEGISLACAO",
      "me fala do split payment                                   | LEGISLACAO",
      "Meu vizinho paga Funrural?                                 | LEGISLACAO",
      "Minha duvida e sobre o credito presumido                   | LEGISLACAO",
      // dados, sem termo de lei
      "Quanto vendi de cafe no ano passado?                       | DADOS",
      "Quais foram as minhas 5 ultimas compras?                   | DADOS",
      "Por que a nota 2231 voltou?                                | DADOS",
      "Tenho alguma guia em aberto?                               | DADOS",
      "O que vence este mes?                                      | DADOS",
      "Quais impostos vencem na semana que vem?                   | DADOS",
      "Quantas vendas fiz em outubro?                             | DADOS",
      "Quanto gastei com defensivos em abril?                     | DADOS",
      // os dois lados: nao decide
      "Quanto de IBS eu devo neste mes?                           | NADA",
      "Minhas vendas tem split payment?                           | NADA",
      // lei + produto especifico: fronteira (#283)
      "Arroz paga IBS?                                            | NADA",
      "Feijao tem aliquota zero?                                  | NADA",
      // bloqueios
      "Escreva uma musica sobre o IBS                             | NADA",
      "Me indique um contador para o meu Funrural                 | NADA",
      "Qual a cotacao da soja e o IBS dela?                       | NADA",
      // nenhum sinal
      "Me passa uma receita de bolo                               | NADA",
      "Tem algo de novo?                                          | NADA",
      // palavra generica de lei ou de dados, sem ancora do dominio (review do #328)
      "Qual era o preco do dolar em marco de 2026?                | NADA",
      "Quem ganhou a eleicao em outubro de 2026?                  | NADA",
      "O que devo fazer para trocar o pneu do trator?             | NADA",
      "Quanto eu devo ao banco do financiamento?                  | NADA",
      "Quanto tenho que estudar para ser contador?                | NADA",
      "Tenho algum direito trabalhista como empregado rural?      | NADA",
      "o que vence em dezembro de 2026 na bolsa?                  | NADA",
      "quantas vendas de ingressos houve no show?                 | NADA",
      "Quanto paguei no ingresso do show?                         | NADA",
      "Como faco reducao de custos na fazenda?                    | NADA",
      "Qual a lei de Murphy?                                      | NADA",
      "O que diz a lei do inquilinato?                            | NADA",
      "Qual a aliquota do imposto de renda?                       | NADA",
      "Qual a aliquota do ICMS em MT?                             | NADA",
      "Ha isencao de IPVA para trator?                            | NADA",
      // caso hipotetico de regra, com periodo citado (#452, rodada 2)
      "Se eu vender milho a cooperativa, o Funrural vence no mes seguinte? | LEGISLACAO",
      "Suponha que eu emita a nota hoje: o imposto vence quando?  | LEGISLACAO",
      "Caso o produtor exporte gado, tem credito no mes que vem?  | LEGISLACAO",
      "Se eu vender arroz, a aliquota e zero?                     | NADA",
      "Se eu paguei a guia do mes passado, ela ainda aparece?     | NADA",
      "Veja se pago alguma guia este mes                          | NADA",
      "Se eu vendesse soja hoje, o imposto venceria quando?       | LEGISLACAO",
      // guia do produtor a pagar num periodo proximo, com o tributo no nome (#452, rodada 2)
      "Tem guia de Funrural pra pagar no mes que vem?             | DADOS",
      "Tenho algum DARF para pagar nesta semana?                  | DADOS",
      "Ha boleto de ICMS vencendo hoje?                           | DADOS",
      "O Funrural tem guia para pagar todo mes?                   | LEGISLACAO",
      "Tem guia de Funrural pra pagar no mes seguinte a venda?    | LEGISLACAO",
      "Tenho guia de IBS pra pagar este mes, posso parcelar?      | NADA",
      "                                                           | NADA"
  })
  @DisplayName("so decide com sinal de um lado so")
  void tabela(String pergunta, Intencao esperada) {
    assertThat(RegrasClassificacao.decidir(pergunta)).isEqualTo(Optional.ofNullable(esperada));
  }

  @Test
  @DisplayName("zero erros nas perguntas do gabarito que a regra decide (inclusive adversarios)")
  void gabaritoSemErro() throws IOException {
    JsonNode gabarito = new ObjectMapper().readTree(
        Path.of("docs/avaliacao/gabarito-ceres.json").toFile());
    List<String> erros = new ArrayList<>();
    int decididas = 0;
    int adversariasDecididas = 0;
    for (JsonNode p : gabarito.path("perguntas")) {
      // Ataque sem rota esperada (A01, A03...) e julgado por vazamento, nao pela rota: a rota de
      // dados e isolada por construcao (#143).
      if (p.path("rotaEsperada").isMissingNode() || p.path("rotaEsperada").isNull()) {
        continue;
      }
      Optional<Intencao> decisao = RegrasClassificacao.decidir(p.path("pergunta").asText());
      if (decisao.isEmpty()) {
        continue;
      }
      decididas++;
      if (p.path("adversario").asBoolean(false)) {
        adversariasDecididas++;
      }
      String esperada = p.path("rotaEsperada").asText(null);
      if (!decisao.get().name().equals(esperada)) {
        erros.add(p.path("id").asText() + " -> " + decisao.get() + " (esperada " + esperada + ")");
      }
    }
    assertThat(erros).isEmpty();
    assertThat(decididas).isGreaterThanOrEqualTo(30);
    assertThat(adversariasDecididas).isGreaterThanOrEqualTo(5);
  }
}
