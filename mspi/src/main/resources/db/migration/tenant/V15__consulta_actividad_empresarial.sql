CREATE FUNCTION "${flyway:defaultSchema}".actividad_operativa(
    p_empresa uuid, p_desde timestamptz, p_hasta timestamptz, p_actor uuid, p_accion text, p_tipo text, p_objeto uuid,
    p_corte timestamptz, p_incluir_corte boolean, p_id uuid, p_limite integer
) RETURNS TABLE (evento_id text, actor_id uuid, accion text, tipo_objeto text, objeto_id uuid,
                 resultado text, motivo text, correlacion_id uuid, registrado_en timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
    SELECT e.id::text, e.actor_id, e.accion, e.tipo_objeto, e.objeto_id,
           e.resultado, e.motivo::text, e.correlacion_id, e.creado_en
    FROM "${flyway:defaultSchema}".eventos_actividad e
    WHERE e.empresa_id = p_empresa
      AND (p_desde IS NULL OR e.creado_en >= p_desde)
      AND (p_hasta IS NULL OR e.creado_en < p_hasta)
      AND (p_actor IS NULL OR e.actor_id = p_actor)
      AND (p_accion IS NULL OR e.accion = p_accion)
      AND (p_tipo IS NULL OR e.tipo_objeto = p_tipo)
      AND (p_objeto IS NULL OR e.objeto_id = p_objeto)
      AND (p_corte IS NULL OR e.creado_en < p_corte
           OR (e.creado_en = p_corte AND (p_incluir_corte OR e.id < p_id)))
    ORDER BY e.creado_en DESC, e.id DESC
    LIMIT greatest(0, least(p_limite, 101));
$$;

REVOKE ALL ON FUNCTION "${flyway:defaultSchema}".actividad_operativa(uuid,timestamptz,timestamptz,uuid,text,text,uuid,timestamptz,boolean,uuid,integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION "${flyway:defaultSchema}".actividad_operativa(uuid,timestamptz,timestamptz,uuid,text,text,uuid,timestamptz,boolean,uuid,integer) TO "${runtime_role}";
