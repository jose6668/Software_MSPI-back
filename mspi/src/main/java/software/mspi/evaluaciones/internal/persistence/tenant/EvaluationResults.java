package software.mspi.evaluaciones.internal.persistence.tenant;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import software.mspi.evaluaciones.api.EvaluationDrafts.Applicability;
import software.mspi.evaluaciones.api.EvaluationDrafts.ElementType;
import software.mspi.evaluaciones.api.EvaluationDrafts.GroupResult;
import software.mspi.evaluaciones.api.EvaluationDrafts.ResultStatus;
import software.mspi.evaluaciones.api.EvaluationDrafts.Results;
import software.mspi.evaluaciones.api.EvaluationDrafts.Totals;

final class EvaluationResults {

    private EvaluationResults() {}

    static Results calculate(EvaluationRecord evaluation, List<Object[]> rows) {
        var global = new Group();
        var domains = new TreeMap<String, Group>();
        var clauses = new TreeMap<String, Group>();
        for (var row : rows) {
            var type = (ElementType) row[0];
            var group = (String) row[1];
            var applicability = (Applicability) row[2];
            var score = (Integer) row[3];
            if (type == ElementType.CONTROL) {
                global.add(applicability, score);
                domains.computeIfAbsent(group, ignored -> new Group()).add(applicability, score);
            } else {
                clauses.computeIfAbsent(group, ignored -> new Group()).add(applicability, score);
            }
        }
        return new Results(evaluation.id, evaluation.revision, global.totals(), summarize(domains), summarize(clauses));
    }

    private static List<GroupResult> summarize(Map<String, Group> groups) {
        return groups.entrySet().stream().map(entry -> new GroupResult(entry.getKey(), entry.getValue().totals())).toList();
    }

    private static final class Group {
        long evaluated;
        long pending;
        long undecided;
        long excluded;
        long points;

        void add(Applicability applicability, Integer score) {
            switch (applicability) {
                case SIN_DECIDIR -> undecided++;
                case NO_APLICA -> excluded++;
                case APLICA -> {
                    if (score == null) {
                        pending++;
                    } else {
                        evaluated++;
                        points += score;
                    }
                }
            }
        }

        Totals totals() {
            long applicable = evaluated + pending + undecided;
            long total = applicable + excluded;
            BigDecimal score = evaluated == 0 ? null : ratio(points, evaluated);
            BigDecimal progress = applicable == 0 ? null : ratio(100 * evaluated, applicable);
            return new Totals(total, applicable, evaluated, pending, undecided, excluded, score, progress,
                    status(total, applicable));
        }

        private ResultStatus status(long total, long applicable) {
            if (total == 0) return ResultStatus.SIN_ELEMENTOS;
            if (applicable == 0) return ResultStatus.NO_APLICA;
            if (evaluated == 0) return ResultStatus.PENDIENTE;
            if (evaluated < applicable) return ResultStatus.PROVISIONAL;
            return ResultStatus.CALCULADO;
        }

        private static BigDecimal ratio(long numerator, long denominator) {
            return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
        }
    }
}
