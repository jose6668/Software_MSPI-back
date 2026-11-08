-- Keep existing sessions and recovery proofs revoked after account reactivation.
CREATE FUNCTION comun.revocar_sesiones_al_desactivar()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.version_credencial := GREATEST(NEW.version_credencial, OLD.version_credencial + 1);
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION comun.revocar_sesiones_al_desactivar() FROM PUBLIC;

CREATE TRIGGER revocar_sesiones_al_desactivar
BEFORE UPDATE OF estado_cuenta ON comun.usuarios
FOR EACH ROW
WHEN (OLD.estado_cuenta = 'HABILITADA' AND NEW.estado_cuenta <> 'HABILITADA')
EXECUTE FUNCTION comun.revocar_sesiones_al_desactivar();

-- Cover accounts already inactive before this migration is installed.
UPDATE comun.usuarios SET version_credencial = version_credencial + 1
WHERE estado_cuenta <> 'HABILITADA';
