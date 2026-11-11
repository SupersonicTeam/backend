package br.com.agrocontabil.assistente;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sinais de pedido que merece a recusa firme: dado de outra pessoa, documento ou identificador, ou
 * tentativa de mexer no próprio sistema que o {@code FiltroInjecao} não pega. Três limiares, do
 * mais largo ao mais estreito:
 *
 * <ul>
 *   <li>{@link #presente}: só escolhe o texto de uma pergunta que já foi recusada (fora de escopo,
 *       ou sem função na rota de dados) (#494). Um falso positivo custa apenas um texto mais seco,
 *       então os sinais são largos de propósito ("dele", "do produtor", "terceiro").</li>
 *   <li>{@link #pedeDesempate}: marca de terceiro ou de ordem ao sistema. As regras e as
 *       embeddings não decidem a rota dessa pergunta: quem decide é o modelo, que separa "as notas
 *       do meu vizinho" de "vendi para o meu vizinho" (#497). O custo de um falso positivo é uma
 *       chamada ao classificador por modelo. Fica de fora o "do produtor" genérico, que é comum
 *       em pergunta de lei ("o Funrural do produtor pessoa física").</li>
 *   <li>{@link #inequivoco}: o pedido não tem leitura legítima e vira fora de escopo antes do
 *       classificador, sem modelo (#497): troca de papel, segredo ou instrução do sistema, burla
 *       da proteção, comando técnico, dados de todos os usuários, dado de uma pessoa específica que
 *       não é o produtor e documento sem dono. Outra pessoa como contraparte da operação do
 *       produtor ("vendi para o meu vizinho", "comprei do meu irmão", "meu pai me vendeu") ou com
 *       o produtor como sujeito da pergunta ("meu vizinho pagou, eu também tenho que pagar?")
 *       nunca decide aqui. Um falso positivo é recusa indevida: na dúvida, o padrão fica de fora
 *       e a pergunta segue para o desempate.</li>
 * </ul>
 *
 * <p>Fica no pacote {@code assistente}, e não em {@code orquestracao}, para a {@link RotaDados}
 * usá-lo sem criar ciclo entre os pacotes.
 */
public final class SinalRecusaFirme {

  /**
   * Parente ou pessoa próxima com possessivo ("meu sogro"), nas duas listas. A de {@link #presente}
   * deixa de fora quem aparece em pedido fora de escopo comum ("carta para minha esposa"), para
   * ele seguir com a recusa gentil.
   */
  private static final String PARENTES = "pai|mae|irmaos?|irmas?|tios?|tias?|primos?|primas?"
      + "|socios?|socias?|cunhad[oa]s?|sogr[oa]s?|genros?|noras?|compadres?|comadres?"
      + "|concorrentes?";
  private static final String PARENTE = "(?:" + PARENTES + ")";
  private static final String PARENTE_LARGO = "(?:" + PARENTES + "|filh[oa]s?|esposa|marido"
      + "|avos?|amig[oa]s?|colegas?|patrao|patroa|chefe|funcionari[oa]s?|empregad[oa]s?)";

  private static final String POSSE = "(?:meu|minha|meus|minhas)";

  /**
   * Pessoa ou conta que não é o produtor, depois de "outro" ou "demais". Ficam de fora as que, na
   * boca do produtor, podem ser dele ou da operação dele: "clientes" e "empresas" (os compradores),
   * "fazendas" e "propriedades" (ele pode ter mais de uma) e "outras contas a pagar" (boletos).
   */
  private static final String PESSOA = "(?:produtor\\w*|pessoas?|usuari[oa]s?|agricultor\\w*"
      + "|pecuarist\\w*|fazendeir[oa]s?|contribuintes?|cooperad[oa]s?"
      + "|contas?(?! (?:a|para|de) pagar)|cpfs?|cnpjs?|contadores|escritorios?)";

  /** Nome no lugar de uma pessoa ("produtor João", "seu Zé"): vira "fulano" na normalização. */
  private static final String NOME = "fulan[oa]s?|beltran[oa]|ciclan[oa]|sicran[oa]";

  /** Outra pessoa específica: o sujeito ou o dono do dado pedido. */
  private static final String TERCEIRO = "(?:(?:" + POSSE + " )?vizinh[oa]s?|" + POSSE + " "
      + PARENTE_LARGO + "|(?:(?:produtor|produtora|seu|dona|senhor|senhora|sr|sra) )?(?:"
      + NOME + ")|(?:outr[oa]s?|demais) " + PESSOA
      + "|produtor(?:a|es|as)? (?:do lado|vizinh[oa]s?|ao lado|da frente)"
      + "|(?:fazenda|propriedade|sitio|chacara|empresa)s? (?:do|da|ao) (?:lado|frente))";

  private static final String ARTIGO = "(?:(?:o|a|os|as|um|uma|esse|essa|esses|essas|aquele"
      + "|aquela|este|esta) )?";

  /** Dado que o sistema guarda de uma conta. */
  private static final String DADO = "(?:notas?|notas fiscais|nf e|vendas?|compras?|guias?"
      + "|creditos?|debitos?|apuracao|apuracoes|faturamentos?|receita|renda|lucro|saldo"
      + "|impostos?|tributos?|ibs|cbs|funrural|dados|cadastro|cpf|cnpj|e mail|email|telefone"
      + "|celular|endereco|inscricao estadual|pendencias?|alertas?|vencimentos?|retencoes"
      + "|declarac\\w*|movimentac\\w*|historico|informacoes|documentos?|patrimonio|producao"
      + "|safra|extrato|situacao|regime)";

  /** Verbo de um fato nos dados (passado ou quantia devida); "deve pagar" é regra, não dado. */
  private static final String FATO = "(?:vendeu|venderam|comprou|compraram|pagou|pagaram"
      + "|faturou|faturaram|emitiu|emitiram|recolheu|recolheram|recebeu|receberam|lucrou"
      + "|lucraram|declarou|declararam|apurou|apuraram|ganhou|ganharam|produziu|produziram"
      + "|colheu|colheram|exportou|exportaram|movimentou|movimentaram"
      + "|dev(?:e|em|ia|iam)(?! \\w+(?:ar|er|ir)\\b)"
      + "|tem (?:\\w+ )?(?:notas?|guias?|creditos?|debitos?|pendencias?|alertas?|impostos?"
      + "|saldo|dividas?))";

  // ---- presente (#494) ----

  /**
   * Dado de outra pessoa ou de outra conta. Ficam de fora as formas que, na rota de dados, falam
   * do próprio produtor: "do produtor rural" (a regra geral), "terceiro trimestre" (um período) e
   * "outras contas a pagar" (boletos dele).
   */
  private static final Pattern OUTRA_PESSOA = Pattern.compile("\\b(outr[oa]s? (produtor\\w*"
      + "|pessoas?|usuari[oa]s?|clientes?|fazendas?|empresas?)"
      + "|outr[oa]s? contas?(?! (?:a|para|de) pagar)|vizinh[oa]s?"
      + "|terceir[oa]s?(?! (?:trimestre|semestre|mes|parcela|quinzena|semana|dia|ano|lugar))"
      + "|" + NOME + "|de alguem|" + POSSE + " " + PARENTE + "|deles?|delas?)\\b");

  /** O "do produtor" genérico: sinal para o texto, nunca para o desempate (é comum na lei). */
  private static final Pattern DO_PRODUTOR = Pattern.compile("\\bd[oa] produtor(?! rural)\\b");

  /** Imperativo dirigido ao sistema, junto do que ele guarda. */
  private static final Pattern ORDEM_AO_SISTEMA = Pattern.compile("\\b(repita|repete|revele"
      + "|revela|mostre|mostra|imprima)\\b");
  private static final Pattern O_QUE_O_SISTEMA_GUARDA = Pattern.compile("\\b(texto|regras"
      + "|instrucoes|prompt|configuracao|mensagem anterior)\\b");

  /** Troca de papel ou credencial: ataque que o filtro de injeção não pega. */
  private static final Pattern PAPEL_OU_CREDENCIAL = Pattern.compile("\\b(finja|aja como"
      + "|se passe por|faca de conta|administrador|senha|token)\\b");

  /** Identificador interno (o {@code X-Usuario-Id}, o id de uma nota). */
  private static final Pattern UUID = Pattern.compile(
      "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");

  /** Marcadores do {@link DataSanitizer} que são de pessoa (a chave de acesso e o CEP não). */
  private static final List<String> MARCADORES_DE_PESSOA =
      List.of("[CPF]", "[CNPJ]", "[EMAIL]", "[TELEFONE]");

  // ---- inequivoco (#497) ----

  /** Troca de papel ou de modo. */
  private static final Pattern PAPEL = Pattern.compile(String.join("|",
      "\\b(?:finja|finge|fingir|fingindo|faca de conta|faz de conta|se passe por|se passa por"
          + "|se passando por)\\b",
      "\\b(?:aja|age|atue|atua|comporte se|comporta se) como (?:se fosse )?(?:o |a |um |uma )?"
          + "(?:administrador\\w*|admin|desenvolvedor\\w*|programador\\w*|root|hacker"
          + "|superusuario|moderador|dono do sistema|suporte|sistema)\\b",
      "\\b(?:voce|vc|tu) (?:agora )?(?:e|sera|vai ser|passa a ser|esta) (?:agora )?"
          + "(?:em modo |o |a |um |uma )?(?:administrador\\w*|admin|desenvolvedor\\w*"
          + "|programador\\w*|root|superusuario|hacker|livre|liberad[oa]|desbloquead[oa]"
          + "|sem restric\\w*|sem filtros?|sem limites?)\\b",
      "\\ba partir de agora (?:voce|vc|tu)\\b",
      "\\bmodo (?:administrador|admin|desenvolvedor|dev|debug|depuracao|manutencao|deus|god"
          + "|root|irrestrito|livre|sem restric\\w*|sem filtros?|sem censura|jailbreak)\\b",
      "\\bcomo se eu fosse (?:o |a |um |uma )?(?:administrador\\w*|admin|dono do sistema"
          + "|desenvolvedor\\w*|programador\\w*|suporte|outr[oa] (?:produtor\\w*|usuari\\w*"
          + "|pessoa|client\\w*))\\b"));

  /** Senha, token ou chave pedidos ao sistema (a senha do próprio certificado não). */
  private static final Pattern SEGREDO = Pattern.compile(String.join("|",
      "\\b(?:qual|quais|me (?:de|da|passe|passa|mostre|mostra|diga|diz|envie|envia|mande|manda"
          + "|informe|informa|fale|fala)|revele|revela|liste|lista|mostre|mostra|informe|informa"
          + "|passe|passa|gere|gera) (?:e |sao )?(?:a |o |as |os |um |uma |sua |seu |suas |seus )?"
          + "(?:senhas?|tokens?|credenciais|chaves? (?:de|da) (?:api|acesso|criptografia)"
          + "|chaves? (?:secretas?|privadas?|mestras?)|api keys?|jwt|segredos?)\\b"
          + "(?! (?:do|da|de) (?:meu|minha)\\b)",
      "\\b(?:senhas?|tokens?|credenciais?|chaves?) (?:do|da|dos|das|de) (?:sistema"
          + "|administrador\\w*|admin|banco|servidor|api|aplicacao|plataforma|agroconia|ceres"
          + "|usuarios?|todos|" + TERCEIRO + ")\\b"));

  /**
   * O que o sistema guarda: prompt, instruções, configuração. "Regras" e "texto" com "iniciais",
   * "originais" ou "anteriores" ficam de fora: "as regras iniciais da transição" e "o texto
   * original da lei" são perguntas de lei.
   */
  private static final Pattern INTERNO = Pattern.compile(String.join("|",
      "\\b(?:seu|teu) (?:prompt|codigo fonte|treinamento|contexto inicial|comando inicial"
          + "|instrucao inicial|system prompt|prompt inicial)\\b",
      "\\b(?:suas|tuas) (?:instrucoes|diretrizes|restricoes|configuracoes|regras internas"
          + "|regras de seguranca|orientacoes internas|ordens)\\b",
      "\\b(?:instrucoes|diretrizes|configurac\\w*|prompt|mensagens?|textos?|conteudo)"
          + " (?:internas?|ocultas?|ocultos?|secretas?|secretos?|escondid[oa]s?"
          + "|que (?:voce|vc|te|lhe) (?:recebeu|deram|passaram|mandaram|enviaram))\\b",
      "\\b(?:repita|repete|revele|revela|imprima|imprime|copie|copia|cole|transcreva|traduza"
          + "|traduz|leia) (?:\\w+ ){0,3}(?:texto|mensagem|mensagens|conteudo|instruc\\w*"
          + "|prompt|configurac\\w*) (?:acima|anterior|anteriores|antes|que (?:voce|vc|te"
          + "|lhe))\\b"));

  /** Burla da proteção: desligar, ignorar ou responder sem as regras. */
  private static final Pattern BURLA = Pattern.compile(String.join("|",
      "\\b(?:ignore|ignora|esqueca|esquece|desconsidere|desconsidera|burle|burla|contorne"
          + "|contorna|desative|desativa|desligue|desliga|quebre|quebra|pule|pula|suspenda"
          + "|suspende|desabilite|desabilita) (?:\\w+ ){0,2}(?:restricoes|instrucoes|orientacoes"
          + "|diretrizes|travas|protecoes|censura|limitacoes|bloqueios?|verificac\\w*"
          + "|autenticac\\w*|seguranca|permissoes|controles? de acesso|filtros? de seguranca"
          + "|(?:suas|tuas|seus|teus) regras)\\b",
      // "sem regras" e "sem limites" ficam de fora: "responda sem regras complicadas"
      "\\b(?:responda|responde|fale|fala|aja|age|atue|atua|funcione|opere|converse|seja)"
          + " (?:\\w+ ){0,3}sem (?:restric\\w*|censura|filtros?|bloqueios?|travas)\\b",
      "\\b(?:libere|libera|desbloqueie|desbloqueia|conceda|concede) (?:\\w+ ){0,3}(?:acesso"
          + "|permiss\\w*|privilegi\\w*|tudo)\\b"));

  /** Comando técnico: SQL, banco, troca de conta ou de identificador. */
  private static final Pattern TECNICO = Pattern.compile(String.join("|",
      "\\b(?:select|delete|insert|drop|truncate|alter|union) (?:\\w+ ){0,4}(?:from|table|into"
          + "|database|select|where)\\b",
      "\\bor 1 1\\b",
      "\\b(?:acesse|acessa|consulte|consulta|abra|abre|entre|entra|mostre|mostra|liste|lista"
          + "|exporte|exporta|baixe|baixa|apague|apaga|altere|altera|modifique|modifica|rode|roda"
          + "|execute|executa|leia|copie|copia) (?:\\w+ ){0,3}(?:banco de dados|database"
          + "|tabela (?:de|do|da|dos|das) (?:usuarios|credenciais|senhas|sessoes|contadores"
          + "|produtores)|logs? do sistema|variaveis de ambiente|codigo fonte|api interna)\\b",
      "\\b(?:execute|executa|rode|roda|rodar|executar) (?:\\w+ ){0,2}(?:comandos?|codigos?"
          + "|scripts?|sql|query|querys|consulta sql|shell|terminal)\\b",
      "\\bx usuario id\\b",
      "\\b(?:troque|troca|mude|muda|altere|altera|alterne|alterna|entre|entra|logue|loga"
          + "|acesse|acessa|use|usa|assuma|assume) (?:\\w+ ){0,3}(?:conta|usuario|perfil|login"
          + "|identidade|sessao|id) (?:de|do|da) " + ARTIGO + "(?:" + TERCEIRO
          + "|administrador\\w*|admin|contador|outr\\w+|alguem|produtor|cliente|usuario)\\b",
      "\\b(?:id|uuid|identificador) (?:de|do|da) (?:outr\\w+|usuario|produtor|cliente|contador"
          + "|administrador)\\b"));

  /** Marcação de código no texto cru (script, template). */
  private static final Pattern MARCACAO = Pattern.compile(
      "(?i)<\\s*/?\\s*script|javascript\\s*:|\\$\\{|\\{\\{");

  /**
   * Dados de todos os usuários ou das outras contas. "Clientes" fica de fora: na boca do produtor
   * são os compradores dele ("as notas de todos os clientes").
   */
  private static final Pattern TODOS = Pattern.compile(String.join("|",
      "\\b(?:notas|vendas|compras|guias|dados|cpfs?|cnpjs?|e mails?|emails?|telefones?|nomes"
          + "|enderecos|senhas|cadastros|faturamentos?|informacoes|documentos|lista) (?:\\w+ ){0,2}"
          + "(?:de|dos|das|do|da) (?:todos|todas|cada|outros|outras|demais) (?:os |as )?"
          + "(?:" + PESSOA + "|cadastrad\\w*)\\b",
      "\\b(?:liste|lista|listar|mostre|mostra|exiba|exibe|exporte|exporta|baixe|baixa|traga"
          + "|traz|quais sao|quem sao|me (?:de|da|passe|passa|envie|mande|manda|mostre|mostra))"
          + " (?:\\w+ ){0,2}(?:todos|todas) (?:os |as )?(?:produtores|usuarios|contadores"
          + "|escritorios|cadastrados|cadastros|contas|pessoas|cpfs|cnpjs)\\b",
      "\\bquantos (?:produtores|usuarios|contadores|escritorios|pessoas) (?:\\w+ ){0,3}"
          + "(?:sistema|cadastrad\\w*|plataforma|agroconia|usam|existem)\\b",
      "\\bquanto cada (?:produtor|usuario|agricultor|pecuarista|cooperado|contador)\\w* (?:\\w+ )?"
          + FATO + "\\b"));

  /** Dado de uma pessoa específica que não é o produtor. */
  private static final Pattern DADO_DE_TERCEIRO = Pattern.compile(String.join("|",
      // "as notas do meu vizinho", "o faturamento da fazenda do meu irmao"
      "\\b" + DADO + " (?:\\w+ ){0,3}(?:do|da|dos|das|de) " + ARTIGO + TERCEIRO + "\\b",
      // "o meu sogro pagou", "o vizinho tem guia atrasada"
      "\\b" + TERCEIRO + " (?:\\w+ ){0,2}" + FATO + "\\b",
      // "quanto vendeu o vizinho"
      "\\b" + FATO + " " + ARTIGO + TERCEIRO + "\\b",
      // "quanto o meu vizinho paga", "quanto de soja o vizinho"
      "\\bquanto (?:de \\w+ )?" + ARTIGO + TERCEIRO + "\\b"));

  /**
   * Outra pessoa como contraparte da operação do produtor: "vendi para o vizinho", "comprei do
   * meu irmão", "meu pai me vendeu". Com ela, o dado de terceiro não decide a rota.
   */
  private static final Pattern CONTRAPARTE = Pattern.compile(String.join("|",
      "\\b(?:para|pro|pra|pros|pras|ao|aos) " + ARTIGO + TERCEIRO + "\\b",
      "\\b(?:comprei|compramos|comprar|compro|comprando|comprado|comprada|compras?|adquiri"
          + "|adquirimos|adquirir|arrendei|arrendo|aluguei|alugo|peguei|recebi|recebemos"
          + "|importei) (?:\\w+ ){0,5}(?:do|da|dos|das|de) " + ARTIGO + TERCEIRO + "\\b",
      "\\b" + TERCEIRO + " (?:\\w+ )?(?:me|nos) (?:vend\\w+|compr\\w+|pag\\w+|dev\\w+"
          + "|entreg\\w+|emit\\w+|mand\\w+|repass\\w+|forn\\w+)\\b",
      "\\b" + TERCEIRO + " (?:\\w+ ){0,3}(?:de mim|para mim|pra mim|comigo|conosco)\\b"));

  /**
   * O produtor como sujeito da pergunta ("vendi para...", "eu também tenho que pagar?"): o terceiro
   * pode ser a contraparte ou a referência de uma dúvida dele, e a rota fica com o desempate.
   */
  private static final Pattern PRODUTOR_FALA_DE_SI = Pattern.compile("\\b(?:eu|tenho|tive"
      + "|preciso|devo|posso|sou|estou|vou|fiz|vendi|comprei|emiti|paguei|recebi|faturei"
      + "|entreguei|arrendei|aluguei|negociei|vendemos|compramos|emitimos|pagamos|recebemos"
      + "|entregamos)\\b");

  /** O produtor falando de si: com isso, um documento na pergunta pode ser dele. */
  private static final Pattern PRIMEIRA_PESSOA = Pattern.compile("\\b(?:eu|meu|minha|meus"
      + "|minhas|mim|comigo|vendi|comprei|emiti|paguei|recebi|faturei|tenho|tive|fiz|devo|nosso"
      + "|nossa|nossos|nossas|vendemos|compramos|emitimos|pagamos|recebemos|sou)\\b");

  /** Destinatário de uma operação ("para o CNPJ ..."): o documento pode ser do comprador. */
  private static final Pattern DESTINO = Pattern.compile("\\b(?:para|pro|pra|ao)\\b");

  /**
   * Nome próprio depois de "produtor" ou de tratamento ("produtor João Pereira", "seu Zé", "Dona
   * Maria"), no texto cru, que tem as maiúsculas. Vira "fulano" antes da normalização.
   */
  private static final Pattern NOME_PROPRIO = Pattern.compile(
      "\\b((?:[Pp]rodutora?|[Ss]eu|[Dd]ona|[Ss]enhora?|[Ss]ra?\\.?)\\s+)"
          + "(\\p{Lu}\\p{Ll}+(?:\\s+(?:d[aeo]s?\\s+)?\\p{Lu}\\p{Ll}+)*)");

  /** Palavras com maiúscula depois de "produtor" que não são nome ("Produtor Rural"). */
  private static final Set<String> NAO_NOME = Set.of("rural", "pessoa", "integrado", "integrada",
      "optante", "familiar", "individual", "agropecuario", "agropecuaria", "funrural", "senar",
      "pix");

  private SinalRecusaFirme() {}

  /**
   * {@code true} se a pergunta pede dado de outra pessoa, traz CPF, CNPJ, e-mail, telefone ou
   * UUID, ou dá ordem ao sistema; inclui todo pedido {@link #inequivoco}.
   *
   * @param pergunta pergunta do usuário
   * @return se o pedido recusado merece a recusa firme
   */
  public static boolean presente(String pergunta) {
    if (pergunta == null || pergunta.isBlank()) {
      return false;
    }
    String texto = normalizar(pergunta);
    return marca(pergunta, texto) || DO_PRODUTOR.matcher(texto).find()
        || inequivoco(pergunta, texto);
  }

  /**
   * {@code true} se a pergunta tem marca de outra pessoa ou de ordem ao sistema: a rota fica com o
   * classificador por modelo, que lê o contexto (#497).
   *
   * @param pergunta pergunta do usuário
   * @return se regras e embeddings devem deixar a decisão para o modelo
   */
  public static boolean pedeDesempate(String pergunta) {
    if (pergunta == null || pergunta.isBlank()) {
      return false;
    }
    String texto = normalizar(pergunta);
    return marca(pergunta, texto) || inequivoco(pergunta, texto);
  }

  /**
   * {@code true} se o pedido não tem leitura legítima: a pergunta é fora de escopo, com a recusa
   * firme, antes do classificador (#497).
   *
   * @param pergunta pergunta do usuário
   * @return se a pergunta deve ser recusada sem passar pelo classificador
   */
  public static boolean inequivoco(String pergunta) {
    if (pergunta == null || pergunta.isBlank()) {
      return false;
    }
    return inequivoco(pergunta, normalizar(pergunta));
  }

  private static boolean marca(String pergunta, String texto) {
    return OUTRA_PESSOA.matcher(texto).find()
        || (ORDEM_AO_SISTEMA.matcher(texto).find()
            && O_QUE_O_SISTEMA_GUARDA.matcher(texto).find())
        || PAPEL_OU_CREDENCIAL.matcher(texto).find()
        || UUID.matcher(pergunta).find()
        || temDadoDePessoa(pergunta);
  }

  private static boolean inequivoco(String pergunta, String texto) {
    if (PAPEL.matcher(texto).find() || SEGREDO.matcher(texto).find()
        || INTERNO.matcher(texto).find() || BURLA.matcher(texto).find()
        || TECNICO.matcher(texto).find() || MARCACAO.matcher(pergunta).find()
        || TODOS.matcher(texto).find()) {
      return true;
    }
    if (CONTRAPARTE.matcher(texto).find() || PRODUTOR_FALA_DE_SI.matcher(texto).find()) {
      return false;
    }
    return DADO_DE_TERCEIRO.matcher(texto).find() || documentoSemDono(pergunta, texto);
  }

  /** CPF, CNPJ, e-mail, telefone ou UUID sem o produtor falando de si nem um destinatário. */
  private static boolean documentoSemDono(String pergunta, String texto) {
    return (UUID.matcher(pergunta).find() || temDadoDePessoa(pergunta))
        && !PRIMEIRA_PESSOA.matcher(texto).find() && !DESTINO.matcher(texto).find();
  }

  /** O {@link DataSanitizer} achou CPF, CNPJ, e-mail ou telefone no texto cru. */
  private static boolean temDadoDePessoa(String pergunta) {
    String mascarada = DataSanitizer.mascarar(pergunta);
    if (mascarada.equals(pergunta)) {
      return false;
    }
    return MARCADORES_DE_PESSOA.stream()
        .anyMatch(m -> ocorrencias(mascarada, m) > ocorrencias(pergunta, m));
  }

  private static int ocorrencias(String texto, String trecho) {
    int n = 0;
    for (int i = texto.indexOf(trecho); i >= 0; i = texto.indexOf(trecho, i + trecho.length())) {
      n++;
    }
    return n;
  }

  /** Nome próprio vira "fulano"; depois, a normalização da {@link PeriodoDaPergunta}. */
  static String normalizar(String pergunta) {
    Matcher m = NOME_PROPRIO.matcher(pergunta);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String primeiro = m.group(2).split("\\s+")[0];
      String troca = NAO_NOME.contains(PeriodoDaPergunta.normalizar(primeiro))
          ? m.group() : m.group(1) + "fulano";
      m.appendReplacement(sb, Matcher.quoteReplacement(troca));
    }
    m.appendTail(sb);
    return PeriodoDaPergunta.normalizar(sb.toString());
  }
}
