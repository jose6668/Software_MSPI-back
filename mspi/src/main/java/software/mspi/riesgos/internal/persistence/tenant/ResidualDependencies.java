package software.mspi.riesgos.internal.persistence.tenant;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import software.mspi.riesgos.api.ResidualRisks.Notice;
import software.mspi.riesgos.api.ResidualRisks.Proof;
import software.mspi.riesgos.api.ResidualRisks.Residual;
import software.mspi.riesgos.api.RiskFailure;

final class ResidualDependencies {
    private final EntityManager entities;

    ResidualDependencies(EntityManager entities) {
        this.entities = entities;
    }

    void lockLinkedControls(RiskRecord risk) {
        var session = entities.unwrap(Session.class);
        // Serialize confirmation with changes to any linked control, including controls excluded from mitigation.
        session.createNativeQuery("""
                select i.id from implementaciones_control i
                join riesgos_implementaciones l on l.empresa_id = i.empresa_id and l.implementacion_id = i.id
                where i.empresa_id = :company and l.riesgo_id = :risk
                order by i.id for share of i
                """, UUID.class).setParameter("company", risk.company).setParameter("risk", risk.id)
                .getResultList();
    }

    List<Proof> verify(RiskRecord risk, List<UUID> selected, ZoneId zone) {
        var session = entities.unwrap(Session.class);
        var rows = session.createNativeQuery("""
                select i.id, v.version_id, v.id, ea.id, ev.id, cast(v.proxima_revision as text)
                from verificaciones_control v
                join implementaciones_control i on i.empresa_id = v.empresa_id and i.id = v.implementacion_id
                join riesgos_implementaciones l on l.empresa_id = i.empresa_id and l.implementacion_id = i.id
                join versiones_implementacion_control c on c.id = v.version_id and c.numero = i.numero_version
                join aplicaciones_control a on a.version_id = v.version_id
                join evidencias ea on ea.id = a.evidencia_id
                join evidencias ev on ev.id = v.evidencia_id
                where v.empresa_id = :company and l.riesgo_id = :risk and v.id in (:ids)
                  and i.estado = 'IMPLEMENTADO' and v.criterios_cumplidos > 0
                  and ea.estado_carga = 'DISPONIBLE' and ev.estado_carga = 'DISPONIBLE'
                  and ea.estado_inspeccion <> 'BLOQUEADA' and ev.estado_inspeccion <> 'BLOQUEADA'
                  and not exists (select 1 from verificaciones_control newer
                                  where newer.version_id = v.version_id and newer.revision > v.revision)
                order by i.id for share of ea,ev
                """, Object[].class).setParameter("company", risk.company).setParameter("risk", risk.id)
                .setParameterList("ids", selected).getResultList();
        Instant now = Instant.now();
        if (rows.size() != selected.size()) throw new RiskFailure(RiskFailure.Reason.RESIDUAL_PROOF);
        var result = new ArrayList<Proof>();
        for (var row : rows) {
            Instant expiry = LocalDate.parse((String) row[5]).atStartOfDay(zone).toInstant();
            if (!expiry.isAfter(now)) throw new RiskFailure(RiskFailure.Reason.RESIDUAL_PROOF);
            result.add(new Proof((UUID) row[0], (UUID) row[1], (UUID) row[2], (UUID) row[3], (UUID) row[4], expiry));
        }
        return result;
    }

    List<Residual> views(RiskRecord risk, List<ResidualAssessment> assessments) {
        if (assessments.isEmpty()) return List.of();
        var ids = assessments.stream().map(row -> row.id).toList();
        var proofs = entities.createQuery("""
                select d from ResidualControl d where d.company = :company and d.residual in :ids
                order by d.implementation
                """, ResidualControl.class).setParameter("company", risk.company).setParameter("ids", ids)
                .getResultList().stream().collect(Collectors.groupingBy(row -> row.residual,
                        Collectors.mapping(ResidualControl::view, Collectors.toList())));
        var notices = entities.createQuery("""
                select n from ResidualNotice n where n.company = :company and n.residual in :ids
                """, ResidualNotice.class).setParameter("company", risk.company).setParameter("ids", ids)
                .getResultList().stream().collect(Collectors.groupingBy(row -> row.residual));
        var resolved = entities.createQuery("""
                select r from ResidualAssessment r where r.company = :company and r.previous in :ids and r.confirmedAt is not null
                """, ResidualAssessment.class).setParameter("company", risk.company).setParameter("ids", ids)
                .getResultList().stream().collect(Collectors.toMap(row -> row.previous, row -> row.confirmedAt));
        Instant now = Instant.now();
        return assessments.stream().map(row -> {
            var selected = proofs.getOrDefault(row.id, List.of());
            Instant replaced = resolved.get(row.id);
            var history = new ArrayList<Notice>();
            for (var item : notices.getOrDefault(row.id, List.of())) {
                history.add(new Notice(item.reason, item.reference, item.detected, replaced));
            }
            Instant cutoff = replaced == null ? now : replaced;
            for (var proof : selected) {
                if (!proof.venceEn().isAfter(cutoff)) {
                    history.add(new Notice("VERIFICACION_VENCIDA", proof.verificacionId(), proof.venceEn(), replaced));
                }
            }
            history.sort(Comparator.comparing(Notice::detectadoEn).thenComparing(Notice::causa).thenComparing(Notice::referenciaId));
            return row.view(risk, selected, history);
        }).toList();
    }
}
