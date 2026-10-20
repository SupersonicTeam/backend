package br.com.agrocontabil.service;

import br.com.agrocontabil.config.LgpdProperties;
import br.com.agrocontabil.dto.PoliticaDadosResponse;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Política de tratamento de dados pessoais (LGPD art. 9º, issue #148).
 *
 * <p>O texto descreve o que o sistema <em>de fato</em> faz — cada item tem correspondência no
 * código (o cadastro, a credencial, a guarda fiscal, a exclusão por anonimização). Mudou o
 * tratamento, muda o texto e a versão ({@code app.lgpd.versao-politica}).
 */
@Service
public class PoliticaDadosService {

  private final LgpdProperties propriedades;

  public PoliticaDadosService(LgpdProperties propriedades) {
    this.propriedades = propriedades;
  }

  /**
   * A política vigente.
   *
   * @return política de dados
   */
  public PoliticaDadosResponse politica() {
    return new PoliticaDadosResponse(
        propriedades.versaoPolitica(),
        propriedades.controlador(),
        propriedades.contatoEncarregado(),
        List.of(
            "Identificacao: nome, CPF ou CNPJ, inscricao estadual.",
            "Contato e acesso: e-mail (login) e telefone; a senha e guardada so como hash.",
            "Endereco do produtor (emitente das notas fiscais).",
            "Dados fiscais: notas fiscais emitidas e importadas, guias, apuracoes de IBS/CBS.",
            "Seguranca: tentativas de login e sessoes ativas (sem guardar o token em si).",
            "Uso da assistente Ceres: metadados de cada pergunta, sem o texto da pergunta nem da "
                + "resposta."),
        List.of(
            "Emitir, importar e guardar documentos fiscais do produtor.",
            "Calcular e apurar IBS/CBS e prazos tributarios.",
            "Autenticar o titular e proteger a conta contra acesso indevido.",
            "Permitir que o escritorio de contabilidade vinculado atenda o produtor."),
        List.of(
            "Execucao de contrato (art. 7o, V): cadastro, login e uso do sistema.",
            "Cumprimento de obrigacao legal (art. 7o, II): emissao e guarda de documentos fiscais.",
            "Legitimo interesse (art. 7o, IX): seguranca da conta e prevencao de fraude."),
        List.of(
            "SEFAZ: notas fiscais, por obrigacao legal.",
            "Escritorio de contabilidade vinculado pelo produtor.",
            "Provedor do modelo da Ceres, quando configurado por API: so dados ja mascarados "
                + "(sem CPF, CNPJ, e-mail ou telefone)."),
        List.of(
            "Documentos fiscais: 5 anos (CTN art. 173), mesmo apos a exclusao da conta.",
            "Conta e contato: ate o titular pedir a exclusao.",
            "Sessoes: ate 30 dias; links de redefinicao de senha: 30 minutos."),
        List.of(
            "Confirmar o tratamento e acessar os dados (art. 18, I e II).",
            "Corrigir dados incompletos ou desatualizados (art. 18, III).",
            "Eliminar os dados pessoais (art. 18, VI), respeitada a guarda fiscal obrigatoria.",
            "Revogar o consentimento e ser informado sobre compartilhamento (art. 18, VII e IX)."),
        "Para eliminar os dados, use DELETE /api/usuarios/{id}/dados autenticado e com a senha "
            + "atual: os dados pessoais sao anonimizados, a conta e encerrada e os documentos "
            + "fiscais seguem guardados pelo prazo legal. Demais pedidos: "
            + propriedades.contatoEncarregado() + ".");
  }
}
