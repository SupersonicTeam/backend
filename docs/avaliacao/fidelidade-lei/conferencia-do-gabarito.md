# Conferência independente do gabarito de fidelidade

Antes do congelamento, um subagente **independente** (contexto novo, sem acesso ao que a Ceres
responde nem ao código dela) leu o gabarito das 36 perguntas e o conferiu **só contra o texto da lei**
gravado em `legislacao_trecho` (8.304 trechos vigentes em 2026-11-12). Conferiu cada fato, cada
dispositivo, cada armadilha, cada permitido, a classe e, nas 6 perguntas de lei ausente, se a base
traz algo que responda. Os 121 rótulos de dispositivo do conjunto foram conferidos contra a base por
consulta (todos existem com o rótulo exato).

(Primeira rodada de conferência, sobre a versão 2026-11-12.1: os ids FL01–FL36 abaixo são históricos; várias dessas perguntas foram trocadas depois, ver a segunda rodada no fim.)

Resultado: 20 perguntas sem nenhuma observação; 8 divergências que mudavam o gabarito e 7 ajustes
menores. Nenhuma pergunta foi trocada. Tudo abaixo foi aplicado ao conjunto antes do sha256.

## Divergências que mudavam o gabarito (resolvidas)

| Pergunta | Divergência apontada | Como foi resolvida |
|----------|----------------------|--------------------|
| FL01 | O F2 dizia que o limite de R$ 3,6 milhões vale também para o integrado; o art. 164 põe o integrado ao lado, sem a condição de receita | F2 reescrito: o limite é do produtor pessoa física ou jurídica; o integrado também não é contribuinte, sem depender dele |
| FL03 | A armadilha 3 punia citar produtos específicos (arroz, feijão, carnes), mas a base tem os itens do Anexo I como trechos próprios | Armadilha reescrita (só é falha afirmar produto sem citar o item, ou listar o que o item não traz); permitidos novos: Anexo I, itens 1, 7 e 19, e o art. 137 (produto in natura da cesta também tem os 60%) |
| FL06 | Mesma questão com o Anexo IX (adubo, defensivo) | Armadilha reescrita; permitidos novos: Anexo IX, itens 2 (fertilizantes) e 6 (defensivos) |
| FL10 | Permitido faltando para o defensivo | Permitido novo: Anexo IX, item 6 |
| FL12 | O permitido dizia "em geral" para bens fornecidos a empregados, em choque com o F1 (EPI e uniforme) | Reescrito: "como regra, salvo as exceções do § 3º" |
| FL14 | O F3 omitia que o § 5º do art. 41 é dito para o Simples; vale para o optante rural pela remissão do art. 166 e pela extensão do § 6º | F3 reescrito com a ligação (art. 166 → § 5º; § 6º estende) e os três dispositivos |
| FL17 | A armadilha "ressarcimento é automático" colidia com o art. 39, § 4º (sem manifestação no prazo, ressarcimento em 15 dias) | Armadilha qualificada (automático sem pedido ou sem prazo); permitido novo: art. 39, § 4º |
| FL33 | A armadilha "fato gerador é o recebimento" ignorava as exceções do art. 10 para execução continuada e antecipação | Armadilha restrita ao fornecimento comum; permitidos novos: art. 10, § 3º, II e § 4º |
| FL29 | O proibido ("qualquer outra alíquota") poderia punir menção correta à transição do ICMS da EC 132 | Proibido restrito a alíquota vigente ou atual do ICMS; permitido novo: EC 132, ADCT arts. 128 e 129 (redução de 2029 a 2032 e extinção em 2033) |

## Ajustes menores (aplicados)

- **FL11, F2:** a venda por comerciante registrado no Ministério da Agricultura não é o caso de quem
  pergunta; passou a `essencial: false`.
- **FL16, F3:** a pergunta é de venda para empresa, então a exceção do inciso X não é o caso; passou a
  `essencial: false` (a armadilha "a empresa sempre recolhe" continua).
- **FL13 e FL18:** a pergunta não situa o ano-calendário; foi acrescentada `observacaoDoGabarito` (supor
  que o IBS e a CBS já são cobrados não torna a resposta infiel). As contas conferem (20% de R$ 3,6
  milhões = R$ 720.000, teto do excesso de R$ 4.320.000; setembro a dezembro = 4 meses, R$ 1.200.000).
- **FL19:** permitido novo para o art. 168, § 10 (de 2027 a 2031 o período de 5 anos pode ser menor).
- **FL20:** F1 passou a listar também as alíneas (a/b dos incisos I e II do § 2º), onde está o
  conteúdo; o F3 reproduz "inclusive em razão de suspensão do pagamento" e as alíneas do § 5º, I; a
  armadilha "dispensa o imposto de vez" virou "como regra" por causa do § 8º (permitido novo).
- **FL24, F3:** "outras modalidades de pagamento" trocado por "modalidades dos incisos III a V do caput
  do art. 27".

## O que o conferente não achou e o que ficou como está

- As 6 perguntas de lei ausente (FL25 a FL30) foram confirmadas: a base não traz ITR, atividade rural
  no imposto de renda, contribuição sindical rural, Proagro, alíquotas de ICMS nem CPR (só menções
  passageiras, que não permitem afirmar regra).
- A classe de FL15 (poderia ser caput) e de FL18 (poderia ser parágrafo) ficou como `EXCECAO`: a
  inversão do sentido é o ponto da pergunta.
- FL31 repete o F1 de FL24 de propósito: é o controle simples da mesma regra.
- As perguntas FL04, FL05, FL15 e outras assumem um contribuinte do regime regular; o produtor não
  contribuinte nem apropria crédito. A pergunta não diz isso e o gabarito não pune a resposta que
  também lembre a condição.

## Segunda rodada (versão 2026-11-12.2): 24 substitutas

Depois do portão de proximidade com os conjuntos antigos, 24 perguntas foram trocadas (ids novos
FL37 a FL60; mantidas 12: FL07–FL11, FL13, FL14, FL17, FL18, FL20, FL28, FL30; 6 por classe). Dois
subagentes independentes conferiram só as substitutas contra o texto da lei: nenhum fato essencial
errado; ajustes aplicados em permitidos, armadilhas e observações (FL43, FL45, FL47, FL48, FL50 com a
exceção do art. 76, § 3º, FL51 com o art. 181, FL54 reescrita porque a EC 132, ADCT art. 136, trata
em termos gerais das contribuições a fundos estaduais, FL55, FL57, FL58, FL59 com o art. 90). Os 173
rótulos do conjunto existem na base. A classe de FL45 ficou EXCECAO (condição e prazo da opção).

## Terceira rodada (versão 2026-11-12.3): 13 substitutas

Na segunda passada do portão, 13 das 24 substitutas foram marcadas e trocadas por 13 candidatas
escolhidas depois de um terceiro portão (ids FL61, FL62, FL64, FL65, FL69, FL71, FL73, FL74, FL76,
FL77, FL82, FL83, FL84). Dois subagentes independentes conferiram só essas 13 contra o texto da lei.
Nenhum fato essencial estava errado e nenhuma pergunta foi derrubada. Ajustes aplicados:

- FL77: a restrição do art. 35 (contribuições das alíneas a, b e c do art. 11, substitutivas e de
  terceiros) e a do art. 39 entraram nos fatos; o auto de infração (art. 37) virou não essencial;
  observação sobre o produtor rural pessoa física.
- FL71 e FL83: a regra do próprio ano-calendário (§ 2º, II, do art. 251, excesso de 20%) entrou como
  permitido; as armadilhas foram qualificadas ao § 1º, I.
- FL69: a parceria ou meação do inciso VI ganhou a condição do § 9º, I.
- FL76: "entre outras ações" saiu do F3; "ou infração" e "incisos I a III" entraram nos fatos 1 e 2.
- FL73, FL74, FL62, FL64, FL65: incisos do art. 172, complemento do art. 473, ressalva do art. 148 e
  permitidos novos; observações de escopo.

Os 154 rótulos do conjunto existem na base.
