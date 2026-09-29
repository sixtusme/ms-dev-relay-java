-- Causa reconocida de un fallo (mejoras-senior §7), para no depender solo de interpretar el texto
-- de "detail" a la hora de contar, agrupar o enrutar una remediación. Nullable: no todo fallo
-- tiene todavía una causa reconocida por el código que produce la evidencia.
ALTER TABLE task_phase_evidence ADD COLUMN reason_code VARCHAR(32) NULL;
