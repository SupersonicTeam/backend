import { APIRequestContext, APIResponse, expect } from '@playwright/test';
import { CONTAS, Conta, senhaDeTeste } from './contas';
import {
  GRANELEIRA_TELES_PIRES,
  NATUREZA_VENDA,
  Parte,
  SOJA,
  numeroDeNotaUnico,
} from './cenario';

/**
 * Cliente da API para PREPARAR dados (a nota do fornecedor da UC002, a nota autorizada da UC005)
 * e para conferir o estado final no servidor. O caso de uso em si sempre passa pela tela.
 *
 * Fala com o mesmo endereco do navegador: o nginx do frontend repassa /api ao backend.
 */
export interface Nota {
  id: string;
  numero: number;
  status: string;
  produtorId: string;
  origem: 'PROPRIA' | 'IMPORTADA';
  chaveAcesso?: string | null;
}

export interface RespostaCeres {
  rota: string;
  resposta: string;
  modoFallback: boolean;
  fontesDados: { funcao: string; origem: string }[];
}

export class Api {
  private constructor(
    private readonly request: APIRequestContext,
    private readonly token: string,
  ) {}

  /** Entra pela rota de login, como a tela, e guarda o access token. */
  static async entrar(request: APIRequestContext, conta: Conta): Promise<Api> {
    const resposta = await request.post('/api/auth/login', {
      data: { email: CONTAS[conta], senha: senhaDeTeste() },
    });
    await exigirSucesso(resposta, `login de ${conta}`);
    const corpo = (await resposta.json()) as { accessToken: string };
    return new Api(request, corpo.accessToken);
  }

  private get cabecalhos(): Record<string, string> {
    return { Authorization: `Bearer ${this.token}` };
  }

  /** Rascunho de venda de soja, com numero que nao colide com o seed. */
  async criarVendaDeSoja(destinatario: Parte, quantidade = 1000): Promise<Nota> {
    const resposta = await this.request.post('/api/notas', {
      headers: this.cabecalhos,
      data: {
        numero: numeroDeNotaUnico(),
        serie: 1,
        naturezaOperacao: NATUREZA_VENDA,
        dataEmissao: new Date().toISOString(),
        tipoDocumento: 'NFE',
        destinatarioNome: destinatario.nome,
        destinatarioCpfCnpj: destinatario.documento,
        destinatarioIe: destinatario.ie,
        destinatarioEndereco: destinatario.endereco,
        itens: [
          {
            numeroItem: 1,
            codigoProduto: SOJA.codigo,
            descricao: SOJA.descricao,
            ncm: SOJA.ncm,
            cfop: SOJA.cfop,
            unidade: SOJA.unidade,
            quantidade,
            valorUnitario: Number(SOJA.valorUnitario),
          },
        ],
      },
    });
    await exigirSucesso(resposta, 'criacao da nota');
    return (await resposta.json()) as Nota;
  }

  async nota(id: string): Promise<Nota> {
    const resposta = await this.request.get(`/api/notas/${id}`, { headers: this.cabecalhos });
    await exigirSucesso(resposta, 'leitura da nota');
    return (await resposta.json()) as Nota;
  }

  /**
   * Notas do produtor com este numero (a busca da lista casa o numero por trecho; filtra-se o
   * exato), das criadas por ultimo. A propria e a compra importada podem ter o mesmo numero:
   * `origem` separa.
   */
  async notasPorNumero(numero: number): Promise<Nota[]> {
    const resposta = await this.request.get('/api/notas', {
      headers: this.cabecalhos,
      params: { busca: String(numero), sort: 'createdAt,desc', size: '50' },
    });
    await exigirSucesso(resposta, 'busca da nota pelo numero');
    const pagina = (await resposta.json()) as { content: Nota[] };
    return pagina.content.filter((n) => n.numero === numero);
  }

  async enviarParaRevisao(id: string): Promise<Nota> {
    return this.post<Nota>(`/api/notas/${id}/enviar-para-revisao`, 'envio para revisao');
  }

  async gerarXml(id: string): Promise<Nota> {
    return this.post<Nota>(`/api/notas/${id}/gerar-xml`, 'geracao do XML');
  }

  async enviarSefaz(id: string): Promise<{ status: string }> {
    return this.post(`/api/notas/${id}/enviar-sefaz`, 'envio a SEFAZ');
  }

  /** XML assinado da nota, como o emitente o entrega ao destinatario. */
  async xml(id: string): Promise<string> {
    const resposta = await this.request.get(`/api/notas/${id}/xml`, { headers: this.cabecalhos });
    await exigirSucesso(resposta, 'download do XML');
    return resposta.text();
  }

  /** Pergunta a Ceres pelo POST simples (sem SSE). */
  async perguntarCeres(pergunta: string): Promise<RespostaCeres> {
    const resposta = await this.request.post('/api/assistente/chat', {
      headers: this.cabecalhos,
      data: { pergunta },
    });
    await exigirSucesso(resposta, 'pergunta a Ceres');
    return (await resposta.json()) as RespostaCeres;
  }

  /** Aprovacao pelo escritorio (o contador nao edita a nota, #178). */
  async aprovar(produtorId: string, notaId: string): Promise<Nota> {
    return this.post<Nota>(
      `/api/contador/produtores/${produtorId}/notas/${notaId}/aprovar`,
      'aprovacao do contador',
    );
  }


  /** POST sem corpo de uma acao da nota, com o retorno em JSON. */
  private async post<T>(caminho: string, oQue: string): Promise<T> {
    const resposta = await this.request.post(caminho, { headers: this.cabecalhos });
    await exigirSucesso(resposta, oQue);
    return (await resposta.json()) as T;
  }
}

/** Falha com o status e o corpo do erro (envelope `errors[]`), que dizem o que o servidor recusou. */
async function exigirSucesso(resposta: APIResponse, oQue: string): Promise<void> {
  if (!resposta.ok()) {
    throw new Error(`Falhou a ${oQue}: HTTP ${resposta.status()} ${await resposta.text()}`);
  }
}

/**
 * Emite pela API uma NF-e de venda de soja do Carlos ja AUTORIZADA, passando pela revisao do
 * escritorio como a tela exige (com contador ativo, so nota aprovada gera XML, #178).
 */
export async function notaAutorizadaDoCarlos(request: APIRequestContext): Promise<Nota> {
  const carlos = await Api.entrar(request, 'carlos');
  const patricia = await Api.entrar(request, 'patricia');

  const rascunho = await carlos.criarVendaDeSoja(GRANELEIRA_TELES_PIRES);
  await carlos.enviarParaRevisao(rascunho.id);
  await patricia.aprovar(rascunho.produtorId, rascunho.id);
  await carlos.gerarXml(rascunho.id);
  const envio = await carlos.enviarSefaz(rascunho.id);
  expect(envio.status, 'a SEFAZ simulada autoriza a nota preparada').toBe('AUTORIZADA');
  return carlos.nota(rascunho.id);
}
