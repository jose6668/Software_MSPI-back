package software.mspi.acciones.internal.persistence.tenant;

import java.time.LocalDate;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.mspi.empresas.api.CompanyPermission;
import software.mspi.empresas.api.TenantUnitOfWork;
import software.mspi.acciones.api.ActionPanelProjection;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
final class JpaActionPanelProjection implements ActionPanelProjection {
    private final TenantUnitOfWork work;
    private final JsonMapper mapper;

    @PersistenceContext(unitName = "enterprise")
    private EntityManager entities;

    JpaActionPanelProjection(TenantUnitOfWork work, JsonMapper mapper) {
        this.work = work;
        this.mapper = mapper;
    }

    @Override
    public Actions read(UUID actor, UUID company, LocalDate start, LocalDate end, int limit, long offset,
               ActionState state, Boolean overdue, LocalDate today) {
        return work.execute(actor, company, CompanyPermission.READ_PANEL, () -> {
            var session = entities.unwrap(Session.class);
            var json = session.createNativeQuery(SQL, String.class)
                    .setParameter("company", company)
                    .setParameter("startDate", start, LocalDate.class)
                    .setParameter("endDate", end, LocalDate.class)
                    .setParameter("pageSize", limit)
                    .setParameter("offset", offset)
                    .setParameter("actionState", state == null ? null : state.name(), String.class)
                    .setParameter("overdue", overdue, Boolean.class)
                    .setParameter("today", today)
                    .getSingleResult();
            return mapper.readValue(json, Actions.class);
        });
    }

    // The aggregate and its optional page use the same filtered rows in one statement.
    private static final String SQL = """
            with by_due_date as (
                select id, codigo, estado, fecha_limite,
                       fecha_limite < :today and estado not in ('COMPLETADA', 'CANCELADA') as overdue
                from acciones_mejora
                where empresa_id = :company
                  and (cast(:actionState as text) is null or estado = :actionState)
                  and (cast(:startDate as date) is null or fecha_limite >= :startDate)
                  and (cast(:endDate as date) is null or fecha_limite <= :endDate)
            ), filtered as (
                select * from by_due_date where cast(:overdue as boolean) is null or overdue = :overdue
            ), page as (
                select * from filtered order by fecha_limite, id limit :pageSize offset :offset
            )
            select cast(jsonb_build_object(
                'total', (select count(*) from filtered),
                'vencidas', (select count(*) from filtered where overdue),
                'porEstado', coalesce((select jsonb_object_agg(estado, total)
                    from (select estado, count(*) as total from filtered group by estado) counts), '{}'),
                'elementos', coalesce((select jsonb_agg(jsonb_build_object(
                    'accionId', id, 'codigo', codigo, 'estado', estado, 'fechaLimite', fecha_limite, 'vencida', overdue
                ) order by fecha_limite, id) from page), '[]')
            ) as text)
            """;
}
