-- Qué sistema o quién produjo la evidencia (mejoras-senior §6.2/§18), sin depender de interpretar
-- el texto de "detail" o de "actor" (que es el nombre concreto, no la categoría). Nullable: no
-- toda evidencia tiene todavía un origen clasificado por el código que la produce.
ALTER TABLE task_phase_evidence ADD COLUMN source VARCHAR(16) NULL;
