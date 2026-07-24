-- `content` era TEXT, y con ddl-auto=validate Hibernate compara tipos: un TEXT contra un String de
-- entidad da problemas al arrancar (ya pasó con failure_reason). Se pasa a VARCHAR, que valida sin
-- ambigüedad; el servicio trunca el mensaje a esta longitud antes de guardarlo.
ALTER TABLE chat_message MODIFY content VARCHAR(8000) NOT NULL;
