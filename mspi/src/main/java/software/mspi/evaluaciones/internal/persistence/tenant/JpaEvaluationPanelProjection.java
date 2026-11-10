package software.mspi.evaluaciones.internal.persistence.tenant;

import java.time.LocalDate;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.mspi.empresas.api.CompanyPermission;
import software.mspi.empresas.api.TenantUnitOfWork;
import software.mspi.evaluaciones.api.EvaluationFailure;
import software.mspi.evaluaciones.api.EvaluationPanelProjection;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
final class JpaEvaluationPanelProjection implements EvaluationPanelProjection {
    private final TenantUnitOfWork work;
    private final JsonMapper mapper;

    @PersistenceContext(unitName = "enterprise")
    private EntityManager entities;

    JpaEvaluationPanelProjection(TenantUnitOfWork work, JsonMapper mapper) {
        this.work = work;
        this.mapper = mapper;
    }

    @Override
    public Projection read(UUID actor, UUID company, LocalDate start, LocalDate end, int limit, long offset,
                           UUID evaluation, EvaluationState state, boolean includeMethods) {
        return work.execute(actor, company, CompanyPermission.READ_PANEL, () -> {
            var session = entities.unwrap(Session.class);
            if (evaluation != null && !session.createNativeQuery("""
                    select exists (select 1 from evaluaciones where empresa_id = :company and id = :id)
                    """, Boolean.class).setParameter("company", company).setParameter("id", evaluation)
                    .getSingleResult()) {
                throw new EvaluationFailure(EvaluationFailure.Reason.MISSING);
            }
            var json = session.createNativeQuery(SQL, String.class)
                    .setParameter("company", company)
                    .setParameter("startDate", start, LocalDate.class)
                    .setParameter("endDate", end, LocalDate.class)
                    .setParameter("pageSize", limit)
                    .setParameter("offset", offset)
                    .setParameter("evaluation", evaluation, UUID.class)
                    .setParameter("evaluationState", state == null ? null : state.name(), String.class)
                    .setParameter("includeMethods", includeMethods)
                    .getSingleResult();
            return mapper.readValue(json, Projection.class);
        });
    }

    // The aggregate and its optional page use the same filtered rows in one statement.
    private static final String SQL = """
            with filtered as (
                select e.id, e.empresa_id, e.nombre, e.fecha_evaluacion, e.estado,
                       e.version_catalogo_id, e.version_metodologia_id
                from evaluaciones e
                where e.empresa_id = :company
                  and (cast(:evaluation as uuid) is null or e.id = :evaluation)
                  and (cast(:evaluationState as text) is null or e.estado = :evaluationState)
                  and (cast(:startDate as date) is null or e.fecha_evaluacion >= :startDate)
                  and (cast(:endDate as date) is null or e.fecha_evaluacion <= :endDate)
            ), page as (
                select * from filtered order by fecha_evaluacion desc, id limit :pageSize offset :offset
            )
            select cast(jsonb_build_object('datos', jsonb_build_object(
                'total', (select count(*) from filtered),
                'porEstado', coalesce((select jsonb_object_agg(estado, total)
                    from (select estado, count(*) as total from filtered group by estado) counts), '{}'),
                'elementos', coalesce((select jsonb_agg(jsonb_build_object(
                    'evaluacionId', e.id, 'nombre', e.nombre, 'fecha', e.fecha_evaluacion, 'estado', e.estado,
                    'versionCatalogoId', e.version_catalogo_id, 'versionMetodologiaId', e.version_metodologia_id,
                    'sinDecidir', r.sin_decidir, 'sinCalificacion', r.sin_calificacion,
                    'heredadasPendientes', r.heredadas,
                    'controlesAplicables', r.controles_aplicables, 'controlesEvaluados', r.controles_evaluados,
                    'clausulasAplicables', r.clausulas_aplicables, 'clausulasEvaluadas', r.clausulas_evaluadas)
                    order by e.fecha_evaluacion desc, e.id)
                    from page e cross join lateral (
                        select count(*) filter (where aplicabilidad = 'SIN_DECIDIR') as sin_decidir,
                               count(*) filter (where aplicabilidad = 'APLICA' and calificacion is null) as sin_calificacion,
                               count(*) filter (where not heredada_confirmada) as heredadas,
                               count(*) filter (where tipo = 'CONTROL' and aplicabilidad <> 'NO_APLICA') as controles_aplicables,
                               count(*) filter (where tipo = 'CONTROL' and calificacion is not null) as controles_evaluados,
                               count(*) filter (where tipo = 'CLAUSULA' and aplicabilidad <> 'NO_APLICA') as clausulas_aplicables,
                               count(*) filter (where tipo = 'CLAUSULA' and calificacion is not null) as clausulas_evaluadas
                        from respuestas_evaluacion where empresa_id = e.empresa_id and evaluacion_id = e.id
                    ) r), '[]')
            ), 'versionesMetodologia', case when :includeMethods then coalesce((
                select jsonb_agg(distinct version_metodologia_id) from filtered
            ), '[]') else '[]' end) as text)
            """;
}
