CREATE FUNCTION comun.validar_responsable_correccion(p_empresa uuid, p_responsable uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
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
END;
$$;
REVOKE ALL ON FUNCTION comun.validar_responsable_correccion(uuid, uuid) FROM PUBLIC;

CREATE FUNCTION comun.puede_revisar_evaluacion(p_empresa uuid, p_actor uuid,
    p_catalogo uuid, p_metodologia uuid, p_exige_politica boolean)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF session_user <> 'mspi_run_' || replace(p_empresa::text, '-', '') THEN
        RAISE EXCEPTION 'Ámbito empresarial inválido.' USING ERRCODE = '42501';
    END IF;
    PERFORM 1 FROM comun.membresias m
    JOIN comun.membresia_roles r ON r.membresia_id = m.id
    JOIN comun.usuarios u ON u.id = m.usuario_id
    WHERE m.empresa_id = p_empresa AND m.usuario_id = p_actor AND m.estado = 'ACTIVA'
      AND u.estado_cuenta = 'HABILITADA' AND r.rol_codigo IN ('AE', 'GS')
    FOR SHARE OF m, r, u;
    IF NOT FOUND THEN RETURN false; END IF;

    RETURN NOT p_exige_politica OR (
        EXISTS (SELECT 1 FROM comun.catalogos_publicados c WHERE c.version_id = p_catalogo
            AND c.codigo = 'MSPI_DIAGNOSTICO_F1' AND c.version = 'F1-1')
        AND EXISTS (SELECT 1 FROM comun.metodologias_publicadas m WHERE m.version_id = p_metodologia
            AND m.identificador = 'EVALUACION_CONTROLES_V1' AND m.aprobada_en IS NOT NULL)
    );
END;
$$;
REVOKE ALL ON FUNCTION comun.puede_revisar_evaluacion(uuid, uuid, uuid, uuid, boolean) FROM PUBLIC;
