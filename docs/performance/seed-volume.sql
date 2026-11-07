-- Volume sintetico para o benchmark de p95 (issue #153). Roda SO no banco da stack isolada
-- (agro_perf), depois de o backend ter subido e o DataLoader ter semeado o produtor A.
--
--   docker compose exec -T postgres psql -U agro_perf -d agro_perf -v notas=20000 \
--     < seed-volume.sql
--
-- Gera `notas` notas (2 itens cada) e uma guia a cada 10 notas, para o produtor A, espalhadas
-- pelos ultimos 24 meses. 85% AUTORIZADA (metade propria, metade importada, para a apuracao ter
-- debito e credito), 10% RASCUNHO e 5% CANCELADA. Nenhum mes e fechado: a apuracao calcula todos
-- ao vivo, o pior caso do endpoint. Idempotente: apaga a carga anterior (marcada na natureza da
-- operacao / observacoes) antes de gerar.
\set ON_ERROR_STOP on
\if :{?notas}
\else
  \set notas 20000
\endif

BEGIN;

CREATE TEMP TABLE alvo AS
  SELECT id FROM produtores WHERE nome_completo LIKE 'Jo%o da Silva Produtor';
DO $$
BEGIN
  IF (SELECT count(*) FROM alvo) <> 1 THEN
    RAISE EXCEPTION 'Produtor A (seed) nao encontrado: suba o backend com SEED_SENHA antes.';
  END IF;
END $$;

DELETE FROM nota_fiscal_item WHERE nota_fiscal_id IN
  (SELECT id FROM nota_fiscal WHERE natureza_operacao = 'Carga sintetica k6');
DELETE FROM nota_fiscal WHERE natureza_operacao = 'Carga sintetica k6';
DELETE FROM guias_fiscais WHERE observacoes = 'Carga sintetica k6';

INSERT INTO nota_fiscal (
  id, chave_acesso, numero, serie, modelo, natureza_operacao, data_emissao, produtor_id,
  destinatario_nome, destinatario_cpf_cnpj, dest_uf, valor_total_produtos, valor_total_nota,
  valor_total_ibs, valor_total_cbs, valor_total_credito_ibs, valor_total_credito_cbs, status,
  tipo_documento, origem, version, created_at, updated_at)
SELECT
  md5('k6-nota-' || g)::uuid,
  '99' || lpad(g::text, 42, '0'),
  900000 + g,
  1, '55', 'Carga sintetica k6',
  now() - ((g % 720) || ' days')::interval - ((g % 24) || ' hours')::interval,
  (SELECT id FROM alvo),
  'Comprador Sintetico ' || (g % 200), '39053344705', 'MG',
  1000.00, 1000.00,
  CASE WHEN g % 2 = 0 THEN 0 ELSE 1.00 END, CASE WHEN g % 2 = 0 THEN 0 ELSE 9.00 END,
  CASE WHEN g % 2 = 0 THEN 1.00 ELSE 0 END, CASE WHEN g % 2 = 0 THEN 9.00 ELSE 0 END,
  CASE WHEN g % 20 = 0 THEN 'RASCUNHO' WHEN g % 20 = 1 THEN 'RASCUNHO'
       WHEN g % 20 = 2 THEN 'CANCELADA' ELSE 'AUTORIZADA' END,
  'NFE', CASE WHEN g % 2 = 0 THEN 'IMPORTADA' ELSE 'PROPRIA' END, 0, now(), now()
FROM generate_series(1, :notas) AS g;

INSERT INTO nota_fiscal_item (
  id, nota_fiscal_id, numero_item, descricao, ncm, cfop, unidade, quantidade, valor_unitario,
  valor_total, aliquota_ibs, valor_ibs, aliquota_cbs, valor_cbs, natureza_operacao, created_at,
  updated_at)
SELECT
  md5('k6-item-' || n.g || '-' || i)::uuid,
  md5('k6-nota-' || n.g)::uuid,
  i, 'Soja em graos', '12019000',
  CASE WHEN n.g % 2 = 0 THEN '1101' ELSE '5101' END,
  'KG', 250.0000, 2.0000, 500.00, 0.1000, 0.50, 0.9000, 4.50,
  CASE WHEN n.g % 2 = 0 THEN 'CREDITO' ELSE 'DEBITO' END, now(), now()
FROM generate_series(1, :notas) AS n(g), generate_series(1, 2) AS i;

INSERT INTO guias_fiscais (
  id, produtor_id, tipo_guia, data_vencimento, valor, observacoes, status, created_at, updated_at)
SELECT
  md5('k6-guia-' || g)::uuid, (SELECT id FROM alvo), 'FUNRURAL',
  current_date - 360 + (g % 720), 150.00, 'Carga sintetica k6',
  CASE WHEN g % 3 = 0 THEN 'PAGA' ELSE 'PENDENTE' END, now(), now()
FROM generate_series(1, GREATEST(:notas / 10, 1)) AS g;

COMMIT;

ANALYZE nota_fiscal;
ANALYZE nota_fiscal_item;
ANALYZE guias_fiscais;

SELECT (SELECT count(*) FROM nota_fiscal WHERE natureza_operacao = 'Carga sintetica k6') AS notas,
       (SELECT count(*) FROM nota_fiscal_item) AS itens,
       (SELECT count(*) FROM guias_fiscais WHERE observacoes = 'Carga sintetica k6') AS guias;
