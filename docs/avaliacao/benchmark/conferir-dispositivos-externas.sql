-- Gerado por ceres_benchmark.externas.sql_conferencia (#358): nao editar a mao.
-- Chaves dos dispositivosEsperados das perguntas externas contra a base de lei.
-- exato = trecho com a chave inteira; com_subdivisoes = trechos que comecam por ela
-- seguida de ',', ')' ou espaco (a regra do AvaliadorResposta.citaAlgum). Zero nos dois = sai dos
-- aceitos.
-- Conjunto 2026-11-05.11.
WITH chaves(chave, norma, dispositivo, perguntas) AS (VALUES
  ('EC 132/2023, art. 1º (redação do art. 156-A', 'EC 132/2023', 'art. 1º (redação do art. 156-A', 'E30, E35'),
  ('EC 132/2023, art. 2º (redação do art. 128', 'EC 132/2023', 'art. 2º (redação do art. 128', 'E35'),
  ('LC 214/2025, Anexo I, item 19', 'LC 214/2025', 'Anexo I, item 19', 'E06'),
  ('LC 214/2025, Anexo I, item 2', 'LC 214/2025', 'Anexo I, item 2', 'E03'),
  ('LC 214/2025, Anexo I, item 8', 'LC 214/2025', 'Anexo I, item 8', 'E09'),
  ('LC 214/2025, Anexo IX, item 10', 'LC 214/2025', 'Anexo IX, item 10', 'E13'),
  ('LC 214/2025, Anexo IX, item 12', 'LC 214/2025', 'Anexo IX, item 12', 'E16'),
  ('LC 214/2025, Anexo IX, item 15', 'LC 214/2025', 'Anexo IX, item 15', 'E37'),
  ('LC 214/2025, Anexo IX, item 18', 'LC 214/2025', 'Anexo IX, item 18', 'E15, E16'),
  ('LC 214/2025, Anexo IX, item 2', 'LC 214/2025', 'Anexo IX, item 2', 'E11'),
  ('LC 214/2025, Anexo IX, item 21', 'LC 214/2025', 'Anexo IX, item 21', 'E16'),
  ('LC 214/2025, Anexo IX, item 28', 'LC 214/2025', 'Anexo IX, item 28', 'E19'),
  ('LC 214/2025, Anexo IX, item 29', 'LC 214/2025', 'Anexo IX, item 29', 'E27'),
  ('LC 214/2025, Anexo IX, item 3', 'LC 214/2025', 'Anexo IX, item 3', 'E14'),
  ('LC 214/2025, Anexo IX, item 6', 'LC 214/2025', 'Anexo IX, item 6', 'E12'),
  ('LC 214/2025, Anexo XV, item 1', 'LC 214/2025', 'Anexo XV, item 1', 'E04'),
  ('LC 214/2025, Anexo XV, item 2', 'LC 214/2025', 'Anexo XV, item 2', 'E04'),
  ('LC 214/2025, Anexo XV, item 3', 'LC 214/2025', 'Anexo XV, item 3', 'E04'),
  ('LC 214/2025, Anexo XV, item 5', 'LC 214/2025', 'Anexo XV, item 5', 'E07'),
  ('LC 214/2025, art. 10', 'LC 214/2025', 'art. 10', 'E28'),
  ('LC 214/2025, art. 11', 'LC 214/2025', 'art. 11', 'E30, E35'),
  ('LC 214/2025, art. 12', 'LC 214/2025', 'art. 12', 'E29'),
  ('LC 214/2025, art. 125', 'LC 214/2025', 'art. 125', 'E03, E06, E09, E10'),
  ('LC 214/2025, art. 137', 'LC 214/2025', 'art. 137', 'E01, E02, E05, E06, E08, E09, E10, E27, E32, E37'),
  ('LC 214/2025, art. 137, § 1º, inciso I', 'LC 214/2025', 'art. 137, § 1º, inciso I', 'E05'),
  ('LC 214/2025, art. 137, § 1º, inciso II', 'LC 214/2025', 'art. 137, § 1º, inciso II', 'E03'),
  ('LC 214/2025, art. 138', 'LC 214/2025', 'art. 138', 'E10, E11, E12, E13, E14, E15, E16, E17, E19, E27, E33, E37'),
  ('LC 214/2025, art. 138, § 1º', 'LC 214/2025', 'art. 138, § 1º', 'E20'),
  ('LC 214/2025, art. 148', 'LC 214/2025', 'art. 148', 'E04, E07, E10'),
  ('LC 214/2025, art. 164', 'LC 214/2025', 'art. 164', 'E21, E22, E23, E24, E36, E40'),
  ('LC 214/2025, art. 164, § 6º', 'LC 214/2025', 'art. 164, § 6º', 'E23'),
  ('LC 214/2025, art. 165', 'LC 214/2025', 'art. 165', 'E21, E40'),
  ('LC 214/2025, art. 168', 'LC 214/2025', 'art. 168', 'E22, E26, E36'),
  ('LC 214/2025, art. 251', 'LC 214/2025', 'art. 251', 'E24, E39'),
  ('LC 214/2025, art. 252', 'LC 214/2025', 'art. 252', 'E39'),
  ('LC 214/2025, art. 252, inciso III', 'LC 214/2025', 'art. 252, inciso III', 'E24'),
  ('LC 214/2025, art. 261, parágrafo único', 'LC 214/2025', 'art. 261, parágrafo único', 'E24'),
  ('LC 214/2025, art. 271', 'LC 214/2025', 'art. 271', 'E01'),
  ('LC 214/2025, art. 343', 'LC 214/2025', 'art. 343', 'E31'),
  ('LC 214/2025, art. 344', 'LC 214/2025', 'art. 344', 'E32'),
  ('LC 214/2025, art. 346', 'LC 214/2025', 'art. 346', 'E31'),
  ('LC 214/2025, art. 347', 'LC 214/2025', 'art. 347', 'E32'),
  ('LC 214/2025, art. 348', 'LC 214/2025', 'art. 348', 'E31'),
  ('LC 214/2025, art. 47', 'LC 214/2025', 'art. 47', 'E26, E34'),
  ('LC 214/2025, art. 4º', 'LC 214/2025', 'art. 4º', 'E25, E29, E38, E39'),
  ('LC 214/2025, art. 4º, § 2º, inciso I', 'LC 214/2025', 'art. 4º, § 2º, inciso I', 'E25'),
  ('LC 214/2025, art. 57', 'LC 214/2025', 'art. 57', 'E34'),
  ('LC 214/2025, art. 5º', 'LC 214/2025', 'art. 5º', 'E38')
)
SELECT c.chave, c.perguntas,
       count(t.*) FILTER (WHERE t.dispositivo = c.dispositivo) AS exato,
       count(t.*) FILTER (WHERE t.dispositivo LIKE c.dispositivo || ',%'
                            OR t.dispositivo LIKE c.dispositivo || ')%'
                            OR t.dispositivo LIKE c.dispositivo || ' %') AS com_subdivisoes
FROM chaves c
LEFT JOIN legislacao_trecho t ON t.norma = c.norma
  AND (t.dispositivo = c.dispositivo OR t.dispositivo LIKE c.dispositivo || ',%'
       OR t.dispositivo LIKE c.dispositivo || ')%' OR t.dispositivo LIKE c.dispositivo || ' %')
GROUP BY c.chave, c.perguntas
ORDER BY (count(t.*) = 0) DESC, c.chave;
