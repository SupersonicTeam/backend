/**
 * Contas da stack E2E. Os e-mails sao os do seed da banca (`config/demonstracao`,
 * docs/demonstracao-banca.md) e do `DataLoader`; a senha de todas e a `SEED_SENHA` de teste que o
 * rodar.sh gera no e2e/.env (nunca a da VPS).
 */
export const CONTAS = {
  /** Produtor PF de Sorriso/MT, com a Contabilidade Rural Oeste como escritorio (vinculo ATIVO). */
  carlos: 'carlos.rossi@example.com',
  /** Administradora do escritorio da demonstracao: aprova notas da carteira. */
  patricia: 'patricia.moreira@example.com',
  /**
   * Produtora B do `DataLoader` (Rondonopolis/MT), sem escritorio: gera o XML direto do rascunho.
   * Faz o papel do fornecedor que emite a NF-e que o Carlos importa (UC002).
   */
  maria: 'maria.agricultora@example.com',
} as const;

export type Conta = keyof typeof CONTAS;

/** Senha de teste das contas semeadas, exportada pelo rodar.sh a partir do e2e/.env. */
export function senhaDeTeste(): string {
  const senha = process.env.E2E_SENHA;
  if (!senha) {
    throw new Error('E2E_SENHA ausente: rode a suite pelo rodar.sh (README.md), que gera o e2e/.env.');
  }
  return senha;
}
