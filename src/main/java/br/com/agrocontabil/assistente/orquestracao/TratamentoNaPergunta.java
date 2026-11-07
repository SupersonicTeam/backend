package br.com.agrocontabil.assistente.orquestracao;

import br.com.agrocontabil.assistente.ContextoAssistente;
import br.com.agrocontabil.assistente.RotaDados;
import br.com.agrocontabil.assistente.ferramentas.TemplateTratamentoProduto;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta;
import br.com.agrocontabil.assistente.porta.TratamentoProdutoPorta.TratamentoConsultado;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Tratamento de um produto pela tabela oficial, sem o modelo (issue #443): quando a pergunta fala
 * de redução, desconto, isenção ou imposto menor ({@link PerguntaTratamentoProduto}) e a rota não
 * consultou a tabela, o backend procura o produto nas palavras da própria pergunta e, achando, o
 * texto da tabela com a ressalva do enquadramento ({@link TemplateTratamentoProduto}) entra na
 * resposta pelo {@link TextosDoBackend}, na rota que for.
 *
 * <p>Os candidatos são o NCM de 8 dígitos da pergunta e as palavras dela, na ordem em que
 * aparecem, sem as vazias, as de imposto e os verbos do produtor ({@link #candidatos}). Cada um é
 * consultado sozinho (a busca por nome soma qualquer palavra que case) e vale o primeiro que a
 * tabela achar: "ração para vaca de leite" acha a ração, não o leite. Nada achado: a resposta da
 * rota fica como está.
 */
@Component
public class TratamentoNaPergunta {

  /** Palavras consultadas no máximo, para a pergunta longa não virar dezenas de consultas. */
  static final int MAXIMO_CANDIDATOS = 6;

  private static final int PALAVRA_MINIMA = 3;

  /** Palavras que não nomeiam produto: vazias, de imposto, verbos e nomes genéricos. */
  private static final Set<String> NAO_PRODUTO = Set.copyOf(List.of(
      // artigos, preposições, pronomes e advérbios
      "uma", "uns", "umas", "dos", "das", "nos", "nas", "aos", "pra", "pro", "pras", "pros",
      "para", "por", "pelo", "pela", "pelos", "pelas", "com", "sem", "que", "quem", "meu",
      "minha", "meus", "minhas", "seu", "sua", "seus", "suas", "voce", "ele", "ela", "eles",
      "elas", "isso", "isto", "esse", "essa", "este", "esta", "aqui", "tem", "ter", "tenho",
      "qual", "quais", "quanto", "quanta", "quantos", "quantas", "como", "quando", "onde",
      "porque", "ainda", "tambem", "mais", "menos", "muito", "pouco", "algum", "alguma",
      "alguns", "algumas", "nenhum", "nenhuma", "todo", "toda", "todos", "todas", "cada",
      "outro", "outra", "mesmo", "mesma", "entao", "sobre", "entre", "ate", "desde", "hoje",
      "agora", "sim", "nao", "existe", "existem", "ha", "hora", "ncm", "codigo",
      // imposto e tratamento
      "reducao", "reducoes", "reduzida", "reduzido", "reduzidas", "reduzidos", "reduz",
      "desconto", "descontos", "aliquota", "aliquotas", "isento", "isenta", "isentos",
      "isentas", "isencao", "zerada", "zerado", "zero", "menor", "menores", "cheio", "cheia",
      "integral", "barato", "barata", "beneficio", "incide", "incidem", "incidencia", "cesta",
      "basica", "ibs", "cbs", "imposto", "impostos", "tributo", "tributos", "tributacao",
      "tributaria", "reforma", "lei", "novo", "nova", "paga", "pagam", "pagar", "pago",
      "cobra", "cobram", "cobrado", "cobrada", "regime", "contribuinte",
      // verbos e nomes genéricos do produtor
      "vendo", "vende", "vendem", "vender", "venda", "vendas", "vendi", "compro", "compra",
      "compram", "comprar", "compras", "comprei", "planto", "planta", "plantam", "plantar",
      "plantio", "colho", "colhe", "colher", "colheita", "crio", "cria", "criar", "criacao",
      "produzo", "produz", "produzir", "producao", "cultivo", "cultiva", "cultivar", "entrego",
      "entrega", "fabrico", "fabrica", "faco", "faz", "feito", "feita", "uso", "usa", "usar",
      "quero", "queria", "saber", "fica", "ficar", "vai", "vou", "sera", "sao", "sou", "esta",
      "estao", "produto", "produtos", "mercadoria", "mercadorias", "item", "itens", "servico",
      "servicos", "empresa", "empresas", "fazenda", "sitio", "propriedade", "produtor",
      "produtores", "rural", "agricola", "agro", "nota", "notas", "fiscal", "valor", "preco",
      "quilo", "saca", "sacas", "litro", "caixa", "tonelada", "tipo", "gente", "vez"));

  private final TratamentoProdutoPorta tabela;

  /** Constrói sobre a tabela oficial. */
  public TratamentoNaPergunta(TratamentoProdutoPorta tabela) {
    this.tabela = tabela;
  }

  /**
   * Procura o produto da pergunta na tabela e registra o texto dela no contexto.
   *
   * @param pergunta pergunta do usuário
   * @param contexto contexto da pergunta
   * @return {@code true} se algum produto foi achado
   */
  public boolean aplicar(String pergunta, ContextoAssistente contexto) {
    for (String termo : candidatos(pergunta)) {
      contexto.verificarAtiva();
      TratamentoConsultado consulta = tabela.consultar(termo, contexto.hoje());
      if (!consulta.produtos().isEmpty()) {
        contexto.registrarConsultaDeTabela(RotaDados.FUNCAO_TRATAMENTO,
            Map.of("produtoOuNcm", termo), false);
        contexto.registrarTextoDoBackend(TemplateTratamentoProduto.texto(consulta),
            TemplateTratamentoProduto.dispositivos(consulta));
        return true;
      }
    }
    return false;
  }

  /**
   * Os termos a consultar, na ordem: o NCM de 8 dígitos e as palavras que podem nomear produto.
   *
   * @param pergunta pergunta do usuário
   * @return até {@value #MAXIMO_CANDIDATOS} termos
   */
  static List<String> candidatos(String pergunta) {
    String texto = RegrasClassificacao.normalizar(pergunta);
    Set<String> termos = new LinkedHashSet<>();
    String ncm = texto.replaceAll("(\\d{4})\\.(\\d{2})\\.(\\d{2})", "$1$2$3");
    for (String palavra : ncm.split(" ")) {
      if (palavra.matches("\\d{8}")) {
        termos.add(palavra);
      }
    }
    List<String> palavras = new ArrayList<>();
    for (String palavra : texto.split(" ")) {
      if (palavra.length() >= PALAVRA_MINIMA && palavra.chars().allMatch(Character::isLetter)
          && !NAO_PRODUTO.contains(palavra)) {
        palavras.add(palavra);
      }
    }
    termos.addAll(palavras);
    return termos.stream().limit(MAXIMO_CANDIDATOS).toList();
  }
}
