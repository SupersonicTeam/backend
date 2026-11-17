package br.com.agrocontabil.assistente.continuidade;

import br.com.agrocontabil.assistente.continuidade.CargaTurno.Faixa;
import java.util.List;
import java.util.Objects;

/**
 * A parte de lei da carga assinada (#499).
 *
 * @param classe      {@code d}: o que a resposta de lei foi
 * @param textoModelo {@code r}: faixa do texto que o modelo escreveu e a rota aceitou, sem o
 *                    glossário nem os textos do backend; nulo sem texto aceito
 * @param citacoes    {@code c}: só sem {@code r}, até 8 citações da rota, {@code "norma|dispositivo"}
 * @param perguntaLei {@code q}: no MISTO, a pergunta legal separada (até 300 caracteres)
 * @param semPergunta {@code s}: a pergunta legal não coube na carga
 * @param reconferida {@code k}: o par saiu de uma reconferência com o modelo
 * @param atalho      {@code a}: no atalho de lei, o nome dele
 */
public record LeiAssinada(Classe classe, Faixa textoModelo, List<String> citacoes,
    String perguntaLei, boolean semPergunta, boolean reconferida, String atalho) {

  /** Máximo de citações em {@code lei.c}. */
  public static final int MAXIMO_CITACOES = 8;

  /** Tamanho máximo de {@code lei.q}. */
  public static final int MAXIMO_PERGUNTA = 300;

  /** O que a resposta de lei foi. */
  public enum Classe {
    /** Lei aceita, {@code SO_TRECHOS} ou atalho. */
    CONFIRMADA,
    /** Trechos sem confirmação (o modelo foi descartado, os trechos ficaram). */
    SEM_CONFIRMACAO,
    /** Nenhum trecho na base. */
    SEM_FUNDAMENTO
  }

  /** Normaliza. */
  public LeiAssinada {
    Objects.requireNonNull(classe, "classe");
    citacoes = citacoes == null ? null : List.copyOf(citacoes);
  }

  LeiAssinada semCitacoes() {
    return new LeiAssinada(classe, textoModelo, null, perguntaLei, semPergunta, reconferida,
        atalho);
  }

  LeiAssinada semPerguntaLei() {
    return new LeiAssinada(classe, textoModelo, citacoes, null, true, reconferida, atalho);
  }
}
