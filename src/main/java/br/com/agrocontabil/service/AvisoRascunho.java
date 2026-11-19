package br.com.agrocontabil.service;

/**
 * Aviso não bloqueante de uma nota própria recém-gravada (issues #389 e #299): a nota foi salva,
 * mas um item pede atenção antes de emitir. Sai no {@code avisos[]} do {@code POST}/{@code PUT
 * /api/notas}; não é sinal da Ceres ({@link SinalNota}), que lê a nota pelo
 * {@link SinaisNotaConferirService#sinais}.
 *
 * @param codigo     qual aviso, com a mensagem própria
 * @param numeroItem item da nota a que o aviso se refere
 */
public record AvisoRascunho(Codigo codigo, Integer numeroItem) {

  /** O cClassTrib informado não vale para o NCM: o {@code gerar-xml} o recusaria (#375). */
  static final String MENSAGEM_CCLASSTRIB_NAO_ADMITIDO = "O cClassTrib informado no item %d não"
      + " vale para o NCM dele: o imposto mostrado não vale na emissão e o gerar-xml será"
      + " recusado. Corrija o cClassTrib ou retire-o do item.";

  /** O 200020 vale só se a cooperativa optou e o produtor é associado, e o sistema não confere. */
  static final String MENSAGEM_COOPERATIVA_OPTANTE = "O cClassTrib 200020 (alíquota zero) vale só"
      + " na venda à cooperativa que optou pelo regime específico do IBS/CBS (LC 214/2025, art."
      + " 271) e da qual você é associado. O sistema não confere isso: guarde a comunicação da"
      + " cooperativa sobre a opção.";

  /** Avisos conhecidos; o nome é o {@code codigo} estável da API. */
  public enum Codigo {
    /** cClassTrib informado que o NCM do item não admite (#375, #389). */
    CCLASSTRIB_NAO_ADMITIDO,
    /** cClassTrib 200020 informado: opção da cooperativa e associação não conferidas (#299). */
    COOPERATIVA_OPTANTE_A_CONFIRMAR
  }

  /**
   * Campo do item a que o aviso se refere.
   *
   * @return hoje sempre {@code cClassTrib}
   */
  public String campo() {
    return "cClassTrib";
  }

  /**
   * Texto para o usuário, com acento (#476).
   *
   * @return mensagem do aviso
   */
  public String mensagem() {
    return switch (codigo) {
      case CCLASSTRIB_NAO_ADMITIDO -> MENSAGEM_CCLASSTRIB_NAO_ADMITIDO.formatted(numeroItem);
      case COOPERATIVA_OPTANTE_A_CONFIRMAR -> MENSAGEM_COOPERATIVA_OPTANTE;
    };
  }
}
