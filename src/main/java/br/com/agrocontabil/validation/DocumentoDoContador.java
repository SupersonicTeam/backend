package br.com.agrocontabil.validation;

/**
 * Regra única do documento da pessoa do contador (issue #519): só CPF.
 *
 * <p>Desde a #228 o escritório é uma entidade com CNPJ próprio, e o contador é a pessoa, membro
 * dele. Quem digitava o CNPJ do escritório no campo "CPF ou CNPJ" gravava esse CNPJ como documento
 * da pessoa, e o escritório continuava sem CNPJ. O CNPJ do escritório tem campo próprio
 * ({@code escritorio.cnpj} no cadastro, {@code PATCH /api/escritorio} na aba Equipe).
 *
 * <p>Vale para as duas rotas que criam contador: {@code POST /api/auth/cadastro} (papel
 * {@code CONTADOR}, pelo {@link CadastroCoerenteComPapelValidator}) e
 * {@code POST /api/auth/cadastro/equipe} (pelo {@link CpfDoContador}). O produtor continua com CPF
 * ou CNPJ, e as contas que já existem não mudam.
 *
 * <p><strong>Para reverter</strong> (a regra aguarda a confirmação do Murillo): faça
 * {@link #recusado(String)} devolver {@code false} e apague os testes que a exercitam
 * ({@code CadastroIntegrationTest}, {@code EscritorioEquipeIntegrationTest} e
 * {@code DocumentoDoContadorTest}). As duas rotas voltam a aceitar "CPF ou CNPJ" sem outra
 * mudança: o formato e o dígito verificador seguem com o {@code @Pattern} e o
 * {@link ValidCpfCnpj} de cada request.
 */
public final class DocumentoDoContador {

  /** Mensagem no cadastro direto, que tem o campo do CNPJ do escritório. */
  public static final String MENSAGEM_CADASTRO = "Informe o seu CPF, com 11 dígitos: o documento"
      + " do contador é o da pessoa. O CNPJ do escritório vai em 'escritorio.cnpj'.";

  /** Mensagem no cadastro pelo convite da equipe: o escritório já existe e tem administrador. */
  public static final String MENSAGEM_EQUIPE = "Informe o seu CPF, com 11 dígitos: o documento"
      + " do contador é o da pessoa. O CNPJ do escritório fica nos dados do escritório, na aba"
      + " Equipe.";

  /** Tamanho do CNPJ só com dígitos. */
  private static final int TAMANHO_CNPJ = 14;

  private DocumentoDoContador() {
  }

  /**
   * O documento informado para a pessoa do contador é recusado?
   *
   * <p>Só o que tem forma de CNPJ (14 dígitos) é recusado aqui. Formato, tamanho e dígito
   * verificador continuam com as anotações do campo: um valor curto ou com letra gera um erro só,
   * o do formato. Um CNPJ com o dígito verificador errado gera dois no {@code cpfCnpj}, o do
   * dígito e o desta regra, para o contador não corrigir o dígito e só então descobrir que o CNPJ
   * não vale ali.
   *
   * @param cpfCnpj documento informado, só dígitos
   * @return {@code true} se é um CNPJ
   */
  public static boolean recusado(String cpfCnpj) {
    return cpfCnpj != null && cpfCnpj.length() == TAMANHO_CNPJ && cpfCnpj.chars()
        .allMatch(c -> c >= '0' && c <= '9');
  }
}
