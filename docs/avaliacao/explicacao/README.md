# Medição da explicação depois da resposta de dados (#268)

A explicação do modelo depois da resposta de dados (backend #268, com o que era da #415) entra
**desligada** (`app.assistente.explicacao.enabled=false`, `AI_EXPLICACAO_HABILITADA`). Para ligar,
valem os critérios da #415, todos com o modelo real:

- taxa de explicações descartadas abaixo de **20%**;
- tempo extra (p50 e p95) medido no ambiente publicado;
- exemplos do modelo real mostrados ao Murillo;
- clareza média ≥ 4/5 pelo avaliador externo (#275), comparada com o template sozinho;
- o OK do Murillo.

Este PR só deixa o caminho pronto. Nenhuma medição foi feita.

## Ferramenta manual

`src/test/java/br/com/agrocontabil/assistente/explicacao/DiagnosticoExplicacaoManual.java` chama o
`ExplicadorResposta` real (prompt, teto, corte e `VerificadorFidelidade`) sobre um conjunto de
respostas de dados e grava, por rodada, o desfecho, o texto cru do modelo, o texto aceito, o tempo
e os tokens. O resumo traz a taxa de descarte por motivo e o p50/p95 do tempo da chamada.

Não roda no `./mvnw test` (o nome não casa com o padrão do surefire). Com o modelo do `.env` (o
Ollama local ou o provedor por API):

```bash
./mvnw test -Dtest=DiagnosticoExplicacaoManual -Ddiagnostico.respostas=docs/avaliacao/explicacao/exemplo-respostas.json -Ddiagnostico.saida=target/explicacao-medicao.json -Ddiagnostico.repeticoes=3
```

O profile `dev` vem da anotação da classe (o contexto sobe com o banco local, como o
`DiagnosticoCitacoesLeiManual`); exporte `DB_PASSWORD` e as variáveis do provedor
(`AI_CHAT_PROVIDER`, `ANTHROPIC_API_KEY`...) como no `./mvnw spring-boot:run`. A medição não grava
nada no banco.

**Tempo extra no ambiente publicado:** o tempo da ferramenta é o da chamada, na máquina de quem
roda. O da VPS sai da auditoria, com a flag ligada lá:
`assistente_interacao.latencia_explicacao_ms` (e os tokens em `tokens_*_explicacao`), por
`desfecho_explicacao`.

## Formato do conjunto

```json
{
  "respostas": [
    {
      "id": "R01",
      "resposta": "texto do template, como a Ceres entrega",
      "consultas": [
        {"funcao": "guiasEmAtraso", "parametros": {"ate": "2026-11-12"}, "vazia": false}
      ]
    }
  ]
}
```

`resposta` é o texto que a rota de dados entrega (o template da #266); `consultas`, as funções que
o geraram, com os parâmetros e se acharam algo, como a explicação as vê.

O `exemplo-respostas.json` é **fictício** e só mostra o formato: não vale como medição. O conjunto
da medição deve ser escrito a partir de respostas reais do seed da demonstração ou da avaliação,
por quem não ajustou o `VerificadorFidelidade`, e com dados fictícios quando o provedor for por
API (o texto vai ao provedor).

## Desfechos

`EXPLICACAO_ACEITA` ou `EXPLICACAO_DESCARTADA_<motivo>` (`model/DesfechoExplicacao`): `NUMERO`,
`TAMANHO`, `SIGLA`, `CONCLUSAO_LEGAL`, `VAZAMENTO`, `CORTADA` e, só na auditoria (fora da
ferramenta), `TEMPO_ESGOTADO`, `SEM_SALDO`, `MODELO_INDISPONIVEL`, `CANCELADA` e `ERRO`. Na
ferramenta, a falha do modelo sai como `ERRO_MODELO:<classe>`.
