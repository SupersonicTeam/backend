# Conferência independente do gabarito do conjunto v3 (#269)

O gabarito de fidelidade das 36 perguntas do `perguntas-fidelidade-lei-v3.json` (versão
`2026-11-13.v3`) foi escrito do texto de `legislacao_trecho` (8.304 trechos vigentes, base local de
2026-11-13) e conferido por subagentes `opus` independentes, que só tinham o leitor da base de lei: sem
o repositório, sem os outros conjuntos e sem o raciocínio de quem escreveu.

## Método

1. **Candidatas.** 57 candidatas (9 por classe e 12 na AMPLA, depois 54 e as reservas da AUSENTE),
   escritas em zonas separadas da lei, longe dos assuntos já cobertos pelos conjuntos anteriores. Um
   portão de proximidade, feito por outra sessão contra as perguntas existentes, escolheu as 36.
2. **Primeira conferência.** Dois conferentes leram, para cada pergunta com base, o texto de cada
   dispositivo citado e dos vizinhos: a afirmação de cada fato, o dispositivo que de fato o traz, as
   armadilhas (têm de ser falsas pelo texto), os permitidos (têm de ser verdadeiros) e se a pergunta é
   respondível só com o texto citado. Para as de lei ausente, buscaram em todas as cinco normas, com
   vários termos, algum dispositivo que responda ao que a pergunta quer saber.
3. **Segunda conferência** nos 15 itens que a primeira mandou ajustar ou trocar, lidos de novo como se
   fossem novos.

Resultado: nenhuma pergunta com base legal foi derrubada; uma de lei ausente foi trocada.

## Divergências e como foram resolvidas

| Id | Achado | Resolução |
|----|--------|-----------|
| F3-02, F3-09, F3-14, F3-26, F3-29, F3-28, F3-23 | A LC 227/2026, art. 174, dá redação a artigos da LC 214/2025 e a base tem os dois rótulos; faltava o paralelo em alguns fatos | Rótulos paralelos acrescentados (e, em F3-23, o do inciso IV na LC 214) |
| F3-06 | O limite de 1% do art. 129 tem exceção escalonada nos §§ 1º a 3º; a armadilha de 2% podia ser verdadeira na transição | Fato F2 passou a "como regra geral"; fato F4 novo (não essencial) com a exceção; armadilha trocada por um valor sem relação com a transição |
| F3-13 | Uma armadilha era quase verdadeira (a base não é o valor total, é o valor menos os repasses) | Armadilha reescrita para a forma errada (só a comissão que o hotel paga) |
| F3-15 | A pergunta não dizia o tributo, e as regras de nulidade são só do processo do IBS | O texto da pergunta ganhou "do IBS" |
| F3-23 | A pergunta omitia uma das quatro condições do dispositivo (a discriminação) | O texto da pergunta ganhou a condição |
| F3-26 | O caput trazia parte do fato; a vedação para dívida em moeda estrangeira tem ressalva para tarifas e comissões | Dispositivo do caput acrescentado; permitido novo para a ressalva |
| F3-27 | O fato era fiel, mas o crédito tem restrições que pesam para insumos agropecuários | Permitidos acrescentados e completados |
| F3-28 | A multa de 150% estava descrita sem a condição do parágrafo (sonegação, fraude, simulação ou conluio) | Fato F5 reescrito, dispositivo do parágrafo acrescentado, permitido sobre as reduções da multa |
| F3-31 | A ordem de presunção do momento do fato gerador está também nos incisos II e III do § 3º | Dispositivos acrescentados |
| F3-41 (ausente) | Nada responde ao percentual de presunção, mas há percentuais de outras contribuições que poderiam ser tomados por ele | `comportamentoCorreto` proíbe apresentá-los como presunção |
| F3-45 (ausente) | A base traz regra geral que responde em parte (operação onerosa com bem imaterial ou direito) | Pergunta trocada pela reserva da classe (F3-55, de assunto que a base não toca) |
| F3-46 (ausente) | Nenhum dispositivo traz o ISS sobre o serviço, mas a base trata do ISS na transição e do serviço no Anexo IX | Permitidos verdadeiros acrescentados |

Observações opcionais da segunda conferência que não foram adotadas: o § 1º, inciso I, do art. 408 em
F3-14 (mantido como apoio, com o § 3º, inciso III, como quem traz o conteúdo), um segundo percentual
a proibir em F3-41 e permitidos extras em F3-06 e F3-13.

## O que o gabarito garante

- Cada fato cita o dispositivo que traz o conteúdo, conferido contra o texto, e os rótulos existem
  exatamente como estão em `legislacao_trecho`.
- As armadilhas são falsas e os permitidos são verdadeiros pelo texto da base.
- As perguntas de lei ausente não têm dispositivo que as responda; o que a base trata de perto está
  nos permitidos, para o juiz não punir quem o disser sem afirmar a regra pedida.

Limite: a conferência é por leitura do texto da base. Não houve revisão de contador.
