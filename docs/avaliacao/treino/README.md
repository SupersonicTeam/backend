# Treino da Ceres

Exemplos usados para **ajustar** a Ceres: exemplos de classificação de rota (#265), exemplos de
prompt, frases de template. Ficam aqui, e só aqui.

**Regra:** o gabarito (`../gabarito-ceres.json`) é **teste**, nunca treino. Nenhuma pergunta do
gabarito entra neste diretório, **nem por paráfrase**: um exemplo igual à pergunta medida mede
memória, não acerto.

- Cópia literal (ignorando acento, caixa e pontuação) quebra o build:
  `SeparacaoTreinoTesteTest.gabaritoVersionadoForaDoTreino`, e o comando de avaliação se recusa a
  rodar (`AvaliacaoCeresRunner.exigirTreinoSeparado`).
- Paráfrase não dá para detectar por texto: quem revisa o PR confere.
- Precisa de um exemplo parecido com uma pergunta do gabarito? Escreva outro, com outro produto,
  período e forma de perguntar.

Este README não tem pergunta do gabarito; mantenha assim.
