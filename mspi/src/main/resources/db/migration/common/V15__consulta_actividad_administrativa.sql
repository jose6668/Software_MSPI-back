CREATE INDEX eventos_administracion_por_fecha
    ON comun.eventos_administracion_empresa (empresa_id, registrado_en, id);

CREATE FUNCTION comun.actividad_administrativa(
    p_consultante uuid, p_empresa uuid, p_version text,
    p_desde timestamptz, p_hasta timestamptz, p_actor uuid, p_accion text, p_tipo text, p_objeto uuid,
    p_corte timestamptz, p_incluir_corte boolean, p_id bigint, p_limite integer
) RETURNS TABLE (evento_id text, actor_id uuid, accion text, tipo_objeto text, objeto_id uuid,
                 resultado text, motivo text, correlacion_id uuid, registrado_en timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM comun.exigir_rol_empresarial(p_consultante, p_empresa, p_version, ARRAY['AE']);
    RETURN QUERY
    SELECT e.id::text, e.actor_id, e.operacion, o.tipo, o.id, 'EXITO'::text, e.motivo::text,
           e.correlacion_id, e.registrado_en
    FROM comun.eventos_administracion_empresa e
    CROSS JOIN LATERAL (SELECT CASE WHEN e.membresia_id IS NULL THEN 'EMPRESA' ELSE 'MEMBRESIA' END AS tipo,
                              coalesce(e.membresia_id, e.empresa_id) AS id) o
    WHERE e.empresa_id = p_empresa
      AND (p_desde IS NULL OR e.registrado_en >= p_desde)
      AND (p_hasta IS NULL OR e.registrado_en < p_hasta)
      AND (p_actor IS NULL OR e.actor_id = p_actor)
      AND (p_accion IS NULL OR e.operacion = p_accion)
      AND (p_tipo IS NULL OR o.tipo = p_tipo)
      AND (p_objeto IS NULL OR o.id = p_objeto)
      AND (p_corte IS NULL OR e.registrado_en < p_corte
           OR (e.registrado_en = p_corte AND (p_incluir_corte OR e.id < p_id)))
    ORDER BY e.registrado_en DESC, e.id DESC
    LIMIT greatest(0, least(p_limite, 101));
END;
$$;

REVOKE ALL ON FUNCTION comun.actividad_administrativa(uuid,uuid,text,timestamptz,timestamptz,uuid,text,text,uuid,timestamptz,boolean,bigint,integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION comun.actividad_administrativa(uuid,uuid,text,timestamptz,timestamptz,uuid,text,text,uuid,timestamptz,boolean,bigint,integer) TO "${runtime_role}";
