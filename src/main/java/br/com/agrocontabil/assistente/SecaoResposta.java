package br.com.agrocontabil.assistente;

import java.util.List;

/**
 * Uma seção da resposta da rota de dados (issue #266): o resultado de uma função, já redigido pelo
 * template do backend. O modelo só escolhe as funções; o texto ao produtor sai daqui.
 *
 * @param assunto       ordem de prioridade da seção na resposta composta
 * @param texto         parágrafo pronto, com o recorte usado
 * @param pontos        itens de atenção da seção (guias em atraso, notas paradas...); 0 no
 *                      informativo e na seção vazia
 * @param resumo        como a seção entra na linha de resumo quando tem pontos ("2 guias em
 *                      atraso"); nulo no informativo
 * @param semPendencia  como a seção vazia entra no resumo, com o recorte ("guias em atraso, com
 *                      vencimento até 01/11/2026"); nulo no informativo
 * @param vazia         a consulta não encontrou nada
 * @param pedida        a pergunta pediu este assunto (a função foi chamada direto pelo modelo); só
 *                      a seção vazia pedida aparece, a não pedida fica no resumo
 * @param textosLivres  pedaços do texto que vieram de texto livre ou de cálculo do backend (título
 *                      do pedido, motivo da devolução, a data de ontem): entram nas fontes do
 *                      {@code ValidadorNumeros} junto com os resultados das funções
 */
public record SecaoResposta(Assunto assunto, String texto, int pontos, String resumo,
    String semPendencia, boolean vazia, boolean pedida, List<String> textosLivres) {

  /** Copia as listas. */
  public SecaoResposta {
    textosLivres = textosLivres == null ? List.of() : List.copyOf(textosLivres);
  }

  /** Seção informativa (apuração, créditos, vendas...): sem pontos de atenção. */
  public static SecaoResposta informativa(String texto, boolean vazia, List<String> textosLivres) {
    return new SecaoResposta(Assunto.INFORMATIVO, texto, 0, null, null, vazia, true,
        textosLivres);
  }

  /**
   * A mesma seção com o próprio texto como fonte do {@code ValidadorNumeros}: para as partes do
   * resumo do produtor (#266), cujo resultado ao modelo só tem contagens. Todo o texto saiu das
   * consultas do backend; o modelo não escreveu nada dele.
   */
  public SecaoResposta comTextoComoFonte() {
    List<String> livres = new java.util.ArrayList<>(textosLivres);
    livres.add(texto);
    return new SecaoResposta(assunto, texto, pontos, resumo, semPendencia, vazia, pedida, livres);
  }

  /** A mesma seção, marcada como vinda do resumo (não pedida diretamente). */
  public SecaoResposta naoPedida() {
    return new SecaoResposta(assunto, texto, pontos, resumo, semPendencia, vazia, false,
        textosLivres);
  }

  /**
   * Assunto da seção, na ordem de prioridade fixa da resposta composta: dinheiro em atraso,
   * prazos dos próximos dias, nota parada, pedido do escritório, nota a conferir (#276) e, por
   * último, os informativos.
   */
  public enum Assunto {
    GUIAS_EM_ATRASO,
    PRAZOS,
    NOTAS_PARADAS,
    PEDIDOS_DO_ESCRITORIO,
    NOTAS_A_CONFERIR,
    INFORMATIVO;

    /** Assunto de atenção (entra na linha de resumo). */
    public boolean deAtencao() {
      return this != INFORMATIVO;
    }
  }
}
