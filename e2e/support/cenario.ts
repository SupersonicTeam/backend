/**
 * Dados do cenario da demonstracao usados pela suite. Espelham o `CenarioDemonstracao` do backend
 * (src/main/java/br/com/agrocontabil/config/demonstracao): mudou la, mude aqui.
 */

/** CPF (so digitos) a partir dos 9 primeiros, com os digitos verificadores (mesma conta do seed). */
export function cpf(noveDigitos: string): string {
  const d1 = digitoModulo11(noveDigitos, [10, 9, 8, 7, 6, 5, 4, 3, 2]);
  const d2 = digitoModulo11(noveDigitos + d1, [11, 10, 9, 8, 7, 6, 5, 4, 3, 2]);
  return `${noveDigitos}${d1}${d2}`;
}

/** CNPJ (so digitos) a partir dos 12 primeiros, com os digitos verificadores. */
export function cnpj(dozeDigitos: string): string {
  const d1 = digitoModulo11(dozeDigitos, [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]);
  const d2 = digitoModulo11(dozeDigitos + d1, [6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]);
  return `${dozeDigitos}${d1}${d2}`;
}

function digitoModulo11(digitos: string, pesos: number[]): number {
  const soma = pesos.reduce((acc, peso, i) => acc + Number(digitos[i]) * peso, 0);
  const resto = soma % 11;
  return resto < 2 ? 0 : 11 - resto;
}

export interface Endereco {
  logradouro: string;
  numero: string;
  municipio: string;
  codigoMunicipioIbge: string;
  uf: string;
  cep: string;
}

export interface Parte {
  nome: string;
  documento: string;
  ie: string;
  endereco: Endereco;
}

/** Carlos Eduardo Rossi, o produtor principal (Sorriso/MT). Destinatario da compra da UC002. */
export const CARLOS: Parte = {
  nome: 'Carlos Eduardo Rossi',
  documento: cpf('284716530'),
  ie: '13248615720',
  endereco: {
    logradouro: 'Estrada Gleba Rio Verde',
    numero: 'KM 38',
    municipio: 'Sorriso',
    codigoMunicipioIbge: '5107925',
    uf: 'MT',
    cep: '78890000',
  },
};

/**
 * Comprador da soja do Carlos no seed: venda interna a contribuinte com IE (CFOP 5101), em que o
 * sistema aplica o CST 51 do ICMS confirmado para MT + soja (#284, pergunta 9) sem pedir o campo
 * que a tela ainda nao tem.
 */
export const GRANELEIRA_TELES_PIRES: Parte = {
  nome: 'Graneleira Vale do Teles Pires Ltda',
  documento: cnpj('274813950001'),
  ie: '133518276',
  endereco: {
    logradouro: 'Rodovia BR-163',
    numero: 'KM 742',
    municipio: 'Sorriso',
    codigoMunicipioIbge: '5107925',
    uf: 'MT',
    cep: '78890000',
  },
};

/** Soja em grao, como no seed (NCM 1201.90.00, in natura do art. 137). */
export const SOJA = {
  codigo: 'SOJA-GR',
  descricao: 'Soja em grao a granel, safra 2025/26',
  ncm: '12019000',
  cfop: '5101',
  unidade: 'KG',
  valorUnitario: '2.12',
} as const;

export const NATUREZA_VENDA = 'Venda de producao do estabelecimento';

/**
 * Numero de nota que nao colide com as do seed (sequenciais e pequenos) nem com o de outra rodada
 * na mesma stack: a nota e unica por produtor, numero, serie e modelo.
 */
export function numeroDeNotaUnico(): number {
  return 700_000_000 + Math.floor(Math.random() * 299_999_999);
}
