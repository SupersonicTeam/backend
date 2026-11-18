package br.com.agrocontabil.service;

import br.com.agrocontabil.config.LgpdProperties;
import br.com.agrocontabil.dto.OperadorExternoResponse;
import br.com.agrocontabil.dto.PoliticaDadosResponse;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Política de tratamento de dados pessoais (LGPD art. 9º, issue #148).
 *
 * <p>O texto descreve o que o sistema <em>de fato</em> faz — cada item tem correspondência no
 * código (o cadastro, a credencial, a guarda fiscal, a exclusão por anonimização). Mudou o
 * tratamento, muda o texto e a versão ({@code app.lgpd.versao-politica}).
 *
 * <p>Desde a #498 a política cita pelo nome os dois operadores de fora do Brasil, a Resend (e-mail)
 * e a Anthropic (modelo da Ceres), com a hipótese do art. 33 da LGPD em que a transferência se
 * apoia. O texto é decisão da equipe de 2026-11-17, <strong>sem revisão jurídica</strong> (registro
 * em {@code docs/lgpd.md}); o que o provedor não confirma por escrito fica escrito como pendente,
 * nunca arredondado.
 */
@Service
public class PoliticaDadosService {

  /**
   * Hipótese do art. 33 em que a GWNext se apoia nas duas transferências (redação 1 da #498). É a
   * base escolhida pela equipe, não a conclusão de que ela está provada: não há revisão jurídica.
   */
  static final String HIPOTESE_ART_33 =
      "A GWNext se apoia no art. 33, IX, da LGPD, combinado com o art. 7º, V: entende que a "
          + "transferência é necessária para executar o serviço que o titular usa. ";

  /** Retenção na Resend: uma redação só, usada na lista de retenção e no operador. */
  static final String RETENCAO_RESEND =
      "30 dias para os registros de envio e o corpo do e-mail, com o link; até 90 dias depois "
          + "de a conta do AgroConIA ser encerrada na Resend.";

  /** Retenção na Anthropic: pendente da conferência no Console; uma redação só. */
  static final String RETENCAO_ANTHROPIC =
      "Em verificação para a conta do AgroConIA. O provedor publica que apaga as entradas e "
          + "saídas em até 30 dias e que o que os sistemas automáticos de segurança dele "
          + "sinalizarem como violação da política de uso pode ficar por até 2 anos. Esta "
          + "política não promete retenção zero.";

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
            "Identificação: nome, CPF ou CNPJ, inscrição estadual; do contador, o CPF e o "
                + "registro no CRC, informados no cadastro.",
            "Contato e acesso: e-mail (login) e telefone, que pode ser informado no cadastro; a "
                + "senha é guardada só como hash.",
            "Escritório de contabilidade: o nome e o CNPJ, o telefone e o endereço, "
                + "obrigatórios na conta empresarial e opcionais na pessoal, informados no "
                + "cadastro do contador ou depois, pelo administrador. Na conta pessoal, o nome "
                + "do escritório é o do próprio contador.",
            "Aceite dos termos de uso: quando foi dado e a versão aceita, registrados no "
                + "cadastro.",
            "CPF/CNPJ, e-mail e telefone ficam cifrados no banco (AES-256).",
            "Endereço do produtor (emitente das notas fiscais).",
            "Dados fiscais: notas fiscais emitidas e importadas, guias, apurações de IBS/CBS.",
            "Segurança: tentativas de login e sessões ativas (sem guardar o token em si).",
            "Uso da assistente Ceres: metadados de cada pergunta, sem o texto da pergunta nem da "
                + "resposta. O texto da pergunta vai ao provedor do modelo, a Anthropic, para a "
                + "resposta ser gerada, e não fica gravado no AgroConIA.",
            "E-mails do sistema (link de nova senha e convites): o endereço de e-mail do "
                + "destinatário, que a Resend recebe para entregar a mensagem.",
            "Endereço próprio do escritório de contabilidade ({slug}.agroconia.com.br), quando o "
                + "administrador o cria: fica público e permanente nos registros de certificados "
                + "da internet (Certificate Transparency). Não use o nome de uma pessoa."),
        List.of(
            "Emitir, importar e guardar documentos fiscais do produtor.",
            "Calcular e apurar IBS/CBS e prazos tributários.",
            "Autenticar o titular e proteger a conta contra acesso indevido.",
            "Permitir que o escritório de contabilidade vinculado atenda o produtor.",
            "Enviar os e-mails do sistema (nova senha e convites) e responder às perguntas da "
                + "Ceres."),
        List.of(
            "Execução de contrato (art. 7º, V): cadastro, login e uso do sistema.",
            "Cumprimento de obrigação legal (art. 7º, II): emissão e guarda de documentos "
                + "fiscais.",
            "Legítimo interesse (art. 7º, IX): segurança da conta e prevenção de fraude.",
            "Transferência internacional (art. 33, IX, combinado com o art. 7º, V): é a base em "
                + "que a GWNext se apoia para o envio de e-mail pela Resend e das perguntas da "
                + "Ceres à Anthropic, por entender que são necessários para executar o serviço "
                + "que o titular usa. Detalhes de cada operador em \"operadoresExternos\"."),
        List.of(
            "SEFAZ: notas fiscais, por obrigação legal.",
            "Escritório de contabilidade vinculado pelo produtor.",
            "Resend (envio de e-mail), operadora nos Estados Unidos: o endereço de e-mail do "
                + "destinatário e o corpo da mensagem, que traz o link de uso único (nova senha) "
                + "ou o convite. Não recebe nome, CPF/CNPJ nem telefone do titular.",
            "Anthropic (modelo da Ceres, pela API), operadora fora do Brasil: a pergunta que o "
                + "produtor escreve, sem CPF, CNPJ, chave de acesso, e-mail, telefone e CEP (o "
                + "filtro não retira um nome escrito por extenso), os trechos de lei consultados "
                + "e, no caso de dúvida que mistura lei e compras, os itens de anexo da lei "
                + "ligados aos NCM comprados. Se a continuidade da conversa estiver ligada, "
                + "também as até 3 trocas anteriores inteiras, com valores, datas, números de "
                + "nota e os textos que o escritório escreve ao produtor (título de um pedido, "
                + "motivo de uma devolução). Não recebe nome, documento, contato nem endereço do "
                + "produtor, nem o XML das notas. A exclusão da conta não alcança o que já foi "
                + "enviado.",
            "Autoridade certificadora (Let's Encrypt) e registros públicos de certificados: o "
                + "endereço próprio do escritório, para emitir o certificado HTTPS."),
        List.of(
            "Documentos fiscais: 5 anos (CTN art. 173), mesmo após a exclusão da conta.",
            "Conta e contato: até o titular pedir a exclusão.",
            "Sessões: até 30 dias; links de redefinição de senha: 30 minutos.",
            "Resend: " + RETENCAO_RESEND + " O link de nova senha vale 30 minutos e uma vez só; "
                + "o de convite de equipe, 7 dias.",
            "Anthropic: " + RETENCAO_ANTHROPIC,
            "Endereço anterior do escritório: bloqueado por 90 dias depois da troca e apagado em "
                + "seguida. A exclusão dos dados do titular apaga o endereço na hora, mas não "
                + "alcança os registros públicos de certificados."),
        List.of(
            "Confirmar o tratamento e acessar os dados (art. 18, I e II).",
            "Corrigir dados incompletos ou desatualizados (art. 18, III).",
            "Eliminar os dados pessoais (art. 18, VI), respeitada a guarda fiscal obrigatória.",
            "Revogar o consentimento e ser informado sobre compartilhamento (art. 18, VII e IX)."),
        "Para eliminar os dados, use DELETE /api/usuarios/{id}/dados autenticado e com a senha "
            + "atual: os dados pessoais são anonimizados, a conta é encerrada e os documentos "
            + "fiscais seguem guardados pelo prazo legal. Isso não apaga o que a Resend e a "
            + "Anthropic já receberam: elas apagam no prazo delas, e o AgroConIA não tem como "
            + "apagar uma pergunta da Ceres isolada antes disso. Demais pedidos, inclusive o de "
            + "informação sobre os operadores: "
            + propriedades.contatoEncarregado() + ".",
        operadoresExternos());
  }

  private List<OperadorExternoResponse> operadoresExternos() {
    return List.of(resend(), anthropic());
  }

  private OperadorExternoResponse resend() {
    return new OperadorExternoResponse(
        "Resend",
        "Envio de e-mail: link de nova senha, convite do escritório ao produtor e convite de "
            + "equipe.",
        "Estados Unidos. O envio sai pela região de São Paulo, mas a Resend informa que guarda "
            + "os dados nos Estados Unidos qualquer que seja a região de envio.",
        "Entregar ao titular os e-mails do sistema.",
        List.of(
            "O endereço de e-mail do destinatário.",
            "O assunto e o corpo da mensagem: no e-mail de nova senha, o link de uso único; no "
                + "convite de equipe, o link com o token; nos convites, o nome do escritório "
                + "(que, na conta pessoal, é o nome do contador) e o CNPJ, só se verificado."),
        List.of(
            "Nome, CPF/CNPJ e telefone do destinatário.",
            "Dados fiscais, notas e qualquer dado da Ceres."),
        HIPOTESE_ART_33
            + "O contrato de tratamento de dados (DPA) da Resend usa as cláusulas-padrão da União "
            + "Europeia. A Resend informa que não assina acordos específicos de outros países, o "
            + "que inclui o da LGPD: as cláusulas-padrão da ANPD (Resolução CD/ANPD nº 19/2024) "
            + "não foram assinadas por ela.",
        RETENCAO_RESEND,
        "Pedidos ao encarregado (" + propriedades.contatoEncarregado() + "). A Resend não "
            + "tem como apagar um e-mail isolado pela API: o pedido vai ao suporte dela, com o "
            + "identificador do e-mail, e o AgroConIA o encaminha. A exclusão de dados do "
            + "AgroConIA não alcança a Resend.");
  }

  private OperadorExternoResponse anthropic() {
    return new OperadorExternoResponse(
        "Anthropic",
        "Modelo de linguagem Claude, pela API, que entende a pergunta à Ceres e redige a "
            + "resposta de lei.",
        "Fora do Brasil. O provedor tem sede nos Estados Unidos, e o contrato dele não lista os "
            + "países onde processa os dados nem os dos subprocessadores.",
        "Responder às perguntas que o produtor faz à Ceres.",
        List.of(
            "A pergunta escrita pelo produtor, depois de o AgroConIA retirar CPF, CNPJ, chave de "
                + "acesso, e-mail, telefone e CEP. O filtro não retira um nome escrito por "
                + "extenso.",
            "Os trechos de lei consultados e, quando a pergunta mistura lei e compras, os itens "
                + "de anexo da lei ligados aos NCM comprados.",
            "Se a continuidade da conversa estiver ligada: as até 3 trocas anteriores, com as "
                + "respostas inteiras (valores, percentuais, datas, números de nota, situação, "
                + "produtos e NCM) e os textos que o escritório escreve ao produtor (título de "
                + "um pedido, motivo de uma devolução).",
            "Se a explicação da resposta estiver ligada: o texto da resposta de dados já "
                + "mostrado ao produtor."),
        List.of(
            "Nome, documento, contato e endereço do produtor.",
            "O XML das notas e os resultados brutos das consultas de dados."),
        HIPOTESE_ART_33
            + "O DPA público da Anthropic inclui as cláusulas-padrão da União Europeia e não "
            + "traz as cláusulas-padrão da ANPD (Resolução CD/ANPD nº 19/2024); conferimos só o "
            + "texto público, não um contrato próprio da conta. A Ceres é opcional: quem não a "
            + "usa não tem dado enviado à Anthropic.",
        RETENCAO_ANTHROPIC,
        "Pedidos ao encarregado (" + propriedades.contatoEncarregado() + "). O AgroConIA não "
            + "tem hoje procedimento para apagar da Anthropic uma pergunta isolada antes do prazo "
            + "dela. A exclusão de dados do AgroConIA não alcança o que já foi enviado.");
  }
}
