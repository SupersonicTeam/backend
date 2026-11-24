package br.com.agrocontabil.service;

import br.com.agrocontabil.config.LgpdProperties;
import br.com.agrocontabil.config.TermosProperties;
import br.com.agrocontabil.dto.SecaoTermosResponse;
import br.com.agrocontabil.dto.TermosUsoResponse;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Termos de uso do AgroConIA (issue #498, aceite gravado desde a #519).
 *
 * <p>O texto descreve o que o sistema <em>de fato</em> faz hoje; cada afirmação tem correspondência
 * no código ou no {@code CLAUDE.md}. Não promete o que o sistema não cumpre e não traz cláusula de
 * foro, de limitação de responsabilidade nem de reaceite: essas ficam para decisão da GWNext (lista
 * no PR da #498). É texto da equipe de 2026-11-24, <strong>sem revisão jurídica</strong>.
 *
 * <p>A versão é a de {@code app.termos.versao}, a mesma que o cadastro grava na credencial com o
 * aceite: servir e gravar vêm da mesma propriedade. Mudou o texto, mude o valor padrão de
 * {@code app.termos.versao} para a data da mudança. {@link #VERSAO_PROVISORIA} não muda nunca: é
 * o que já foi gravado antes de o texto existir.
 */
@Service
public class TermosUsoService {

  /** Versão provisória gravada nos cadastros feitos antes de o texto existir (#519). */
  static final String VERSAO_PROVISORIA = "2026-11-11";

  private final TermosProperties termos;
  private final LgpdProperties lgpd;

  public TermosUsoService(TermosProperties termos, LgpdProperties lgpd) {
    this.termos = termos;
    this.lgpd = lgpd;
  }

  /**
   * Os termos vigentes.
   *
   * @return versão, controlador, contato e as seções do texto
   */
  public TermosUsoResponse termos() {
    String contato = lgpd.contatoEncarregado();
    String controlador = lgpd.controlador();
    return new TermosUsoResponse(termos.versao(), controlador, contato,
        List.of(
            quemSomos(controlador, contato),
            demonstracao(),
            calculo(),
            ceres(),
            conta(),
            escritorio(),
            dadosEExclusao(),
            disponibilidade(),
            mudancas(),
            contato(controlador, contato)));
  }

  private static SecaoTermosResponse quemSomos(String controlador, String contato) {
    return secao("quem-somos", "Quem oferece o AgroConIA",
        List.of(
            "O AgroConIA (www.agroconia.com.br) é um software de apoio ao produtor rural e ao "
                + "escritório de contabilidade na reforma tributária do agronegócio: IBS e CBS "
                + "(Emenda Constitucional 132/2023 e Lei Complementar 214/2025).",
            "Quem oferece o serviço e decide sobre o tratamento dos dados é a " + controlador
                + " (" + contato + "). A Supersonic Team é a equipe que desenvolveu o sistema "
                + "como Trabalho de Conclusão de Curso (TCC) de 2026; ela não é a dona do "
                + "AgroConIA.",
            "Ao aceitar estes termos no cadastro, você declara que leu estes termos e a Política "
                + "de privacidade e que concorda com eles."),
        List.of());
  }

  private static SecaoTermosResponse demonstracao() {
    return secao("demonstracao", "Fase de demonstração",
        List.of(
            "O AgroConIA está em fase de demonstração. Enquanto a comunicação com a SEFAZ for "
                + "simulada, o que vale hoje:"),
        List.of(
            "O sistema monta, assina e valida o XML da NF-e, mas a transmissão à SEFAZ é "
                + "simulada: uma nota que aparece como autorizada não passou pelo fisco e não tem "
                + "valor fiscal.",
            "A conferência de uma NF-e recebida, na importação, também é simulada.",
            "Não use o AgroConIA para emitir, receber nem guardar documentos fiscais que "
                + "precisem valer perante o fisco.",
            "Os dados de exemplo da demonstração podem ser restaurados ao estado inicial."));
  }

  private static SecaoTermosResponse calculo() {
    return secao("calculo", "O que o cálculo faz e o que não faz",
        List.of(
            "O cálculo de IBS e CBS vale para o produtor rural no regime regular da Lei "
                + "Complementar 214/2025: o que está acima do limite do art. 164 ou o que optou "
                + "pelo regime (art. 165).",
            "O AgroConIA não trata, por ora: o produtor não contribuinte, o crédito presumido do "
                + "comprador (art. 168), a transição de limite, a opção e a renúncia, nem o "
                + "diferimento dos insumos do Anexo IX (art. 138, § 2º). O crédito de uma compra "
                + "desse tipo de insumo pode, por isso, não corresponder ao seu caso.",
            "O sistema emite o grupo de ICMS da NF-e, mas não calcula o ICMS devido nem o ICMS-ST: "
                + "isso segue com o seu contador. O Funrural e o Senar são calculados para "
                + "informar e, quando o comprador os retém, saem destacados na nota; o "
                + "recolhimento é por guia.",
            "O AgroConIA ajuda, e não substitui o contador. Confira o enquadramento, os valores e "
                + "o que vai na nota com o seu contador antes de emitir ou de recolher."),
        List.of());
  }

  private static SecaoTermosResponse ceres() {
    return secao("ceres", "A Ceres, a assistente",
        List.of(
            "A Ceres responde a perguntas sobre os seus dados (notas, apuração, créditos e "
                + "prazos) e sobre a legislação da reforma tributária. Ela só consulta a conta de "
                + "quem pergunta.",
            "A Ceres pode errar. Quando responde sobre a lei, cita os dispositivos da base de "
                + "legislação do sistema, e você deve conferir a citação. A resposta não é "
                + "parecer jurídico nem contábil, e o enquadramento do seu caso é com o contador.",
            "O uso da Ceres tem um limite por semana, que se renova a cada semana.",
            "A pergunta que você escreve é enviada a um provedor de modelo de linguagem fora do "
                + "Brasil, sem o CPF, o CNPJ, a chave de acesso, o e-mail, o telefone e o CEP, que "
                + "o sistema retira antes. Escreva só o que precisa. Os detalhes estão na "
                + "Política de privacidade."),
        List.of());
  }

  private static SecaoTermosResponse conta() {
    return secao("conta", "Sua conta",
        List.of("Você é responsável pela sua conta:"),
        List.of(
            "informar dados verdadeiros e mantê-los atualizados;",
            "guardar a sua senha e não a compartilhar; se suspeitar que alguém a usa, troque a "
                + "senha, o que também encerra todas as sessões;",
            "o conteúdo do que você emite: o sistema valida e sinaliza o que encontra, mas não "
                + "garante que a nota esteja correta;",
            "não acessar nem tentar acessar a conta ou os dados de outra pessoa, não contornar "
                + "os limites de uso e não enviar arquivos com vírus (os anexos passam por "
                + "antivírus);",
            "não usar o sistema para fim ilícito."));
  }

  private static SecaoTermosResponse escritorio() {
    return secao("escritorio", "Escritório de contabilidade e convites",
        List.of(
            "O escritório atende o produtor por um vínculo, que só nasce quando o produtor aceita "
                + "o convite enviado ao e-mail dele. Com o vínculo ativo, os membros do escritório "
                + "veem os dados fiscais do produtor (notas, apuração, guias e pedidos de "
                + "documento). Quando o produtor tem contador vinculado, a nota só gera o XML "
                + "depois de aprovada pelo escritório.",
            "O produtor pode encerrar o vínculo quando quiser, e o acesso do escritório termina na "
                + "hora. O escritório também pode suspender ou encerrar o vínculo.",
            "O nome do escritório é texto livre. O CNPJ só aparece como verificado depois de "
                + "conferido pela GWNext; antes disso, o sistema avisa para confirmar o convite "
                + "com o contador antes de aceitar.",
            "Quem cadastra um escritório responde pelas informações dele e pelos membros que "
                + "convida. O endereço próprio do escritório é público e permanente nos registros "
                + "de certificados da internet: não use nele o nome de uma pessoa."),
        List.of());
  }

  private static SecaoTermosResponse dadosEExclusao() {
    return secao("dados-e-exclusao", "Seus dados e a exclusão da conta",
        List.of(
            "O que o AgroConIA coleta, para quê, com quem compartilha (inclusive os provedores de "
                + "e-mail e de modelo de linguagem fora do Brasil) e por quanto tempo guarda está "
                + "na Política de privacidade.",
            "Você pode pedir a eliminação dos seus dados pessoais, com a senha atual da conta ou "
                + "pelo contato abaixo. Os dados pessoais são anonimizados, a conta é encerrada e todas as sessões "
                + "caem. Não dá para desfazer.",
            "Os documentos fiscais (notas, guias e apurações) continuam guardados por 5 anos, por "
                + "obrigação legal (Código Tributário Nacional, art. 173), com o CPF ou o CNPJ "
                + "do contribuinte. Por isso, depois da exclusão, o produtor não consegue criar "
                + "outra conta com o mesmo CPF ou CNPJ: fale com o contato abaixo.",
            "O que já foi enviado aos provedores de e-mail e de modelo de linguagem não é "
                + "apagado por essa exclusão; eles apagam no prazo deles."),
        List.of());
  }

  private static SecaoTermosResponse disponibilidade() {
    return secao("disponibilidade", "Disponibilidade",
        List.of(
            "O AgroConIA está em demonstração e não há compromisso de disponibilidade: ele pode "
                + "ficar fora do ar, mudar ou ser interrompido."),
        List.of());
  }

  private static SecaoTermosResponse mudancas() {
    return secao("mudancas", "Mudanças nestes termos e versões",
        List.of(
            "Quando o texto muda, a versão muda (a data da versão aparece no topo desta página). "
                + "O AgroConIA registra, no cadastro, a data e a versão que cada pessoa aceitou.",
            "A versão " + VERSAO_PROVISORIA + ", gravada nos cadastros feitos antes da "
                + "publicação deste texto, é provisória: ela marcou o aceite, mas o texto ainda "
                + "não existia."),
        List.of());
  }

  private static SecaoTermosResponse contato(String controlador, String contato) {
    return secao("contato", "Contato",
        List.of("Dúvidas sobre estes termos e pedidos sobre os seus dados: " + controlador
            + ", " + contato + "."),
        List.of());
  }

  private static SecaoTermosResponse secao(String id, String titulo, List<String> paragrafos,
      List<String> itens) {
    return new SecaoTermosResponse(id, titulo, paragrafos, itens);
  }
}
