package software.mspi.riesgos.internal.persistence.tenant;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.mspi.empresas.api.CompanyPermission;
import software.mspi.empresas.api.TenantUnitOfWork;
import software.mspi.riesgos.api.InherentRisks.Impact;
import software.mspi.riesgos.api.InherentRisks.Level;
import software.mspi.riesgos.api.InherentRisks.Probability;
import software.mspi.riesgos.api.InherentRisks.State;
import software.mspi.riesgos.api.RiskPanelProjection;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
final class JpaRiskPanelProjection implements RiskPanelProjection {
    private final TenantUnitOfWork work;
    private final JsonMapper mapper;

    @PersistenceContext(unitName = "enterprise")
    private EntityManager entities;

    JpaRiskPanelProjection(TenantUnitOfWork work, JsonMapper mapper) {
        this.work = work;
        this.mapper = mapper;
    }

    @Override
    public Projection read(UUID actor, UUID company, LocalDate start, LocalDate end, int limit, long offset,
                      Basis basis, State state, AssessmentStatus situation,
                      Level level, Probability probability, Impact impact, ZoneId zone, Instant now, boolean includeMethods) {
        return work.execute(actor, company, CompanyPermission.READ_PANEL, () -> {
            var session = entities.unwrap(Session.class);
            var json = session.createNativeQuery(SQL, String.class)
                    .setParameter("company", company)
                    .setParameter("startDate", start, LocalDate.class)
                    .setParameter("endDate", end, LocalDate.class)
                    .setParameter("pageSize", limit)
                    .setParameter("offset", offset)
                    .setParameter("basis", basis.name())
                    .setParameter("zone", zone.getId())
                    .setParameter("instant", now)
                    .setParameter("riskState", name(state), String.class)
                    .setParameter("situation", name(situation), String.class)
                    .setParameter("level", name(level), String.class)
                    .setParameter("probability", name(probability), String.class)
                    .setParameter("impact", name(impact), String.class)
                    .setParameter("includeMethods", includeMethods)
                    .getSingleResult();
            return mapper.readValue(json, Projection.class);
        });
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    // The aggregate and its optional page use the same filtered rows in one statement.
    private static final String SQL = """
            with selected as (
                select r.id, r.codigo, r.estado, r.creada_en, r.motivo_revision, r.contexto,
                       i.id as inherent_id, i.contexto as inherent_context, i.confirmada_en as inherent_confirmed,
                       v.id as residual_id, v.inherente_id as residual_inherent, v.confirmada_en as residual_confirmed,
                       case when :basis = 'INHERENTE' then i.id else v.id end as assessment,
                       case when :basis = 'INHERENTE' then i.version_metodologia_id else v.version_metodologia_id end as method,
                       case when :basis = 'INHERENTE' then i.nivel else v.nivel end as level,
                       case when :basis = 'INHERENTE' then i.probabilidad else v.probabilidad end as probability,
                       case when :basis = 'INHERENTE' then i.impacto else v.impacto end as impact
                from riesgos r
                left join valoraciones_inherentes i on i.empresa_id = r.empresa_id and i.riesgo_id = r.id
                    and i.id = r.inherente_vigente_id
                left join valoraciones_residuales v on v.empresa_id = r.empresa_id and v.riesgo_id = r.id
                    and v.id = r.residual_vigente_id and :basis = 'RESIDUAL'
                where r.empresa_id = :company
                  and (cast(:riskState as text) is null or r.estado = :riskState)
                  and (cast(:startDate as date) is null or r.creada_en >= cast(:startDate as timestamp) at time zone :zone)
                  and (cast(:endDate as date) is null or r.creada_en < cast(cast(:endDate as date) + 1 as timestamp) at time zone :zone)
            ), assessed as (
                select s.*, case
                    when s.assessment is null then 'PENDIENTE'
                    when s.inherent_confirmed is null or s.inherent_context <> s.contexto or s.motivo_revision is not null
                        or (:basis = 'RESIDUAL' and (
                            s.residual_confirmed is null or s.residual_inherent is distinct from s.inherent_id
                            or exists (select 1 from avisos_revision_residual a
                                where a.empresa_id = :company and a.residual_id = s.residual_id)
                            or exists (select 1 from residual_verificaciones v
                                where v.empresa_id = :company and v.residual_id = s.residual_id and v.vence_en <= :instant)
                        )) then 'REVISION_REQUERIDA'
                    else 'VIGENTE'
                end as situation
                from selected s
            ), filtered as (
                select * from assessed
                where (cast(:situation as text) is null or situation = :situation)
                  and (cast(:level as text) is null or (situation = 'VIGENTE' and level = :level))
                  and (cast(:probability as text) is null or (situation = 'VIGENTE' and probability = :probability))
                  and (cast(:impact as text) is null or (situation = 'VIGENTE' and impact = :impact))
            ), page as (
                select * from filtered order by creada_en desc, id limit :pageSize offset :offset
            )
            select cast(jsonb_build_object('datos', jsonb_build_object(
                'total', (select count(*) from filtered),
                'porEstado', coalesce((select jsonb_object_agg(estado, total)
                    from (select estado, count(*) as total from filtered group by estado) counts), '{}'),
                'porSituacion', coalesce((select jsonb_object_agg(situation, total)
                    from (select situation, count(*) as total from filtered group by situation) counts), '{}'),
                'porNivel', coalesce((select jsonb_object_agg(level, total) from (
                    select level, count(*) as total from filtered where situation = 'VIGENTE' group by level
                ) counts), '{}'),
                'matriz', coalesce((select jsonb_agg(jsonb_build_object(
                    'probabilidad', probability, 'impacto', impact, 'nivel', level, 'total', total
                ) order by probability, impact, level) from (
                    select probability, impact, level, count(*) as total from filtered
                    where situation = 'VIGENTE' group by probability, impact, level
                ) cells), '[]'),
                'elementos', coalesce((select jsonb_agg(jsonb_build_object(
                    'riesgoId', id, 'codigo', codigo, 'estado', estado, 'situacion', situation,
                    'valoracionId', assessment, 'versionMetodologiaId', method,
                    'nivel', case when situation = 'VIGENTE' then level end,
                    'probabilidad', case when situation = 'VIGENTE' then probability end,
                    'impacto', case when situation = 'VIGENTE' then impact end
                ) order by creada_en desc, id) from page), '[]')
            ), 'versionesMetodologia', case when :includeMethods then coalesce((
                select jsonb_agg(distinct method) from filtered where method is not null
            ), '[]') else '[]' end) as text)
            """;
}
