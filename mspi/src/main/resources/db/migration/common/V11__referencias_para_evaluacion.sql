-- The tenant runtime calls this only through its own evaluation-initialization trigger.
CREATE FUNCTION comun.contexto_nueva_evaluacion(p_empresa uuid, p_responsable uuid,
    p_catalogo uuid, p_metodologia uuid)
RETURNS TABLE (elemento_id uuid, tipo text, codigo text, titulo text, grupo text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF session_user <> 'mspi_run_' || replace(p_empresa::text, '-', '') THEN
        RAISE EXCEPTION 'Ámbito empresarial inválido.' USING ERRCODE = '42501';
    END IF;

    PERFORM 1 FROM comun.membresias m
    JOIN comun.membresia_roles r ON r.membresia_id = m.id
    JOIN comun.usuarios u ON u.id = m.usuario_id
    WHERE m.empresa_id = p_empresa AND m.id = p_responsable AND m.estado = 'ACTIVA'
      AND u.estado_cuenta = 'HABILITADA' AND r.rol_codigo IN ('AE', 'GS')
    FOR SHARE OF m, r, u;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Responsable de evaluación no disponible.' USING ERRCODE = 'P1701';
    END IF;

    PERFORM 1 FROM comun.versiones_catalogo v
    WHERE v.id = p_catalogo AND v.publicada_en IS NOT NULL AND v.retirada_en IS NULL FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Catálogo no disponible para nuevas evaluaciones.' USING ERRCODE = 'P1702';
    END IF;

    PERFORM 1 FROM comun.versiones_metodologia m
    WHERE m.id = p_metodologia AND m.identificador = 'EVALUACION_CONTROLES_V1'
      AND m.publicada_en IS NOT NULL AND m.retirada_en IS NULL FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Metodología no disponible para nuevas evaluaciones.' USING ERRCODE = 'P1702';
    END IF;

    RETURN QUERY SELECT e.id, e.tipo, e.codigo::text, e.titulo::text, e.grupo::text
    FROM comun.elementos_catalogo e WHERE e.version_catalogo_id = p_catalogo;
END;
$$;
REVOKE ALL ON FUNCTION comun.contexto_nueva_evaluacion(uuid, uuid, uuid, uuid) FROM PUBLIC;
