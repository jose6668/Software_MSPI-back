\set ON_ERROR_STOP on
BEGIN;
LOCK TABLE comun.atribuciones_plataforma IN SHARE ROW EXCLUSIVE MODE;
SELECT id AS verified_user
FROM comun.usuarios
WHERE id = :'usuario_id'::uuid AND estado_cuenta = 'HABILITADA' AND correo_verificado_en IS NOT NULL
FOR SHARE
\gset
SELECT NOT EXISTS (
    SELECT 1 FROM comun.atribuciones_plataforma WHERE codigo = 'AP' AND usuario_id <> :'verified_user'::uuid
) AS bootstrap_allowed
\gset
\if :bootstrap_allowed
INSERT INTO comun.atribuciones_plataforma (usuario_id, codigo)
VALUES (:'verified_user'::uuid, 'AP') ON CONFLICT DO NOTHING;
\else
DO $$ BEGIN
    RAISE EXCEPTION 'La plataforma ya tiene otro administrador inicial.';
END $$;
\endif
COMMIT;
