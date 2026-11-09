-- Paridade que barra a rodada da #452 (rodar_medicao_452.sh): o que as funções da Ceres leem
-- para o produtor da medição, nos dois bancos. Ids e horários de gravação ficam de fora (mudam de
-- um banco para outro); valores, datas e textos entram, e os textos agregados (md5) são ordenados
-- pelo próprio conteúdo, nunca pelo id. Variável: nome (nome_completo do seed).
-- Linha 'produtor da medicao|1' obrigatória: com 0 os dois bancos sairiam iguais por vazio.
with p as (select id from produtores where nome_completo = :'nome'),
     e as (select escritorio_id from carteira_vinculo
           where produtor_id in (select id from p) and situacao = 'ATIVO'),
     n as (select * from nota_fiscal where produtor_id in (select id from p))
select 'produtor da medicao', count(*)::text from p
union all select 'escritorios com vinculo ativo', count(*)::text from e
union all select 'trechos de lei', count(*)::text from legislacao_trecho
union all select 'notas ' || tipo_documento || ' ' || status || ' ' || origem || ' '
    || to_char(data_emissao at time zone 'America/Sao_Paulo', 'YYYY-MM-DD'),
    count(*) || ' ' || coalesce(sum(valor_total_nota), 0) || ' ' || coalesce(sum(valor_total_ibs), 0)
    || ' ' || coalesce(sum(valor_total_cbs), 0) || ' ' || coalesce(sum(valor_total_credito_ibs), 0)
    || ' ' || coalesce(sum(valor_total_credito_cbs), 0) || ' '
    || md5(string_agg(serie || '-' || numero || '|' || coalesce(destinatario_nome, '') || '|'
        || coalesce(emitente_nome, '') || '|' || coalesce(natureza_operacao, ''), ','
        order by serie, numero, destinatario_nome, emitente_nome))
  from n
  group by tipo_documento, status, origem, to_char(data_emissao at time zone 'America/Sao_Paulo', 'YYYY-MM-DD')
union all select 'itens ' || i.ncm || ' ' || n.status || ' '
    || to_char(n.data_emissao at time zone 'America/Sao_Paulo', 'YYYY-MM'),
    count(*) || ' ' || sum(i.quantidade) || ' ' || sum(i.valor_total) || ' '
    || md5(string_agg(coalesce(i.descricao, '') || '|' || coalesce(i.codigo_produto, '') || '|'
        || coalesce(i.unidade, '') || '|' || coalesce(i.cfop, '') || '|'
        || coalesce(i.c_class_trib_aplicado, ''), ','
        order by i.descricao, i.codigo_produto, i.unidade, i.cfop, i.c_class_trib_aplicado))
  from nota_fiscal_item i join n on n.id = i.nota_fiscal_id
  group by i.ncm, n.status, to_char(n.data_emissao at time zone 'America/Sao_Paulo', 'YYYY-MM')
union all select 'revisoes ' || n.serie || '-' || n.numero || ' ' || r.acao,
    count(*) || ' ' || md5(string_agg(coalesce(r.motivo, '') || '|' || r.papel, ','
        order by r.motivo, r.papel))
  from nota_fiscal_revisao r join n on n.id = r.nota_fiscal_id
  group by n.serie, n.numero, r.acao
union all select 'validacoes ' || n.serie || '-' || n.numero,
    count(*) || ' ' || md5(string_agg(v.code || '|' || coalesce(v.field, '') || '|'
        || coalesce(v.message, ''), ',' order by v.code, v.field, v.message))
  from nota_fiscal_validacao v join n on n.id = v.nota_fiscal_id
  group by n.serie, n.numero
union all select 'guias ' || tipo_guia || ' ' || status || ' ' || data_vencimento,
    count(*) || ' ' || sum(valor) || ' '
    || md5(string_agg(coalesce(observacoes, ''), '|' order by observacoes))
  from guias_fiscais where produtor_id in (select id from p) group by tipo_guia, status, data_vencimento
union all select 'apuracao ' || periodo || ' ' || status,
    count(*) || ' ' || sum(debito_ibs) || ' ' || sum(debito_cbs) || ' ' || sum(credito_ibs) || ' '
    || sum(credito_cbs) || ' ' || sum(saldo_ibs) || ' ' || sum(saldo_cbs) || ' ' || sum(total_notas)
  from apuracao_mensal where produtor_id in (select id from p) group by periodo, status
union all select 'alertas ' || tipo || ' ' || status || ' ' || data_limite,
    count(*) || ' ' || md5(string_agg(descricao, '|' order by descricao))
  from alerta_tributario where produtor_id in (select id from p) group by tipo, status, data_limite
union all select 'pendencias ' || status || ' ' || coalesce(data_limite::text, '-') || ' ' || tipos_documento,
    count(*) || ' ' || md5(string_agg(titulo || '|' || coalesce(descricao, '') || '|'
        || coalesce(observacoes, '') || '|' || coalesce(motivo, ''), ','
        order by titulo, descricao, observacoes, motivo))
  from pendencia where produtor_id in (select id from p) group by status, data_limite, tipos_documento
union all select 'obrigacoes ' || titulo || ' ' || periodicidade || ' ' || coalesce(dia::text, '-') || ' '
    || coalesce(mes::text, '-') || ' ' || ativa || ' ' || modelo,
    count(*) || ' ' || md5(string_agg(texto, ',' order by texto))
  from (select *, coalesce(tipo_produtor, '') || '|' || coalesce(regimes_tributarios, '') || '|'
            || coalesce(ajuste_fim_de_semana, '') || '|' || coalesce(origem, '') || '|'
            || coalesce(descricao, '') as texto
        from obrigacao_fiscal where modelo or escritorio_id in (select escritorio_id from e)) o
  group by titulo, periodicidade, dia, mes, ativa, modelo
order by 1, 2;
