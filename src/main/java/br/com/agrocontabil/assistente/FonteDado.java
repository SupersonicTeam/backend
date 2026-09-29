package br.com.agrocontabil.assistente;

import java.util.Map;

/**
 * Uma função consultada para responder a pergunta (issue #142): de onde vieram os números.
 *
 * @param funcao     nome da função (ex.: {@code totalComprasPorInsumo})
 * @param parametros parâmetros efetivamente usados, já validados e normalizados
 */
public record FonteDado(String funcao, Map<String, String> parametros) {}
