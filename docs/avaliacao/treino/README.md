# Treino da Ceres

Exemplos usados para **ajustar** a Ceres: exemplos de classificação de rota (#265), exemplos de
prompt, frases de template. Ficam aqui ou, quando o backend precisa deles em execução, dentro do
JAR: o conjunto do classificador de rota (#265) está em
`src/main/resources/ceres/treino/classificacao-rota.json`, porque a imagem só copia `src`. A guarda
abaixo confere os dois lugares (`SeparacaoTreinoTeste.TREINO` e `TREINO_EMBUTIDO`).

**Regra:** o gabarito (`../gabarito-ceres.json`) é **teste**, nunca treino. Nenhuma pergunta do
gabarito entra neste diretório, **nem por paráfrase**: um exemplo igual à pergunta medida mede
memória, não acerto.

- Cópia literal (ignorando acento, caixa e pontuação) quebra o build:
  `SeparacaoTreinoTesteTest.gabaritoVersionadoForaDoTreino`, e o comando de avaliação se recusa a
  rodar (`AvaliacaoCeresRunner.exigirTreinoSeparado`).
- Paráfrase não dá para detectar por texto: quem revisa o PR confere.
- Precisa de um exemplo parecido com uma pergunta do gabarito? Escreva outro, com outro produto,
  período e forma de perguntar.

`perguntas-lei-busca-2026-11.json` é um conjunto de medição de uso único (#436): fica nesta pasta só para passar pela guarda contra o gabarito e não serve para ajustar a Ceres.

Este README não tem pergunta do gabarito; mantenha assim.
