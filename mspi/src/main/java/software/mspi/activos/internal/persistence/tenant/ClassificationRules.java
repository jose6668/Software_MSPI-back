package software.mspi.activos.internal.persistence.tenant;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import software.mspi.activos.api.AssetClassification.Authorization;
import software.mspi.activos.api.AssetClassification.Input;
import software.mspi.activos.api.AssetClassification.PersonalDataType;
import software.mspi.activos.api.ClassificationFailure;

final class ClassificationRules {

    private ClassificationRules() {}

    static Input validate(Input input) {
        var invalid = new ArrayList<String>();
        dimension(input.confidencialidad(), "confidencialidad", invalid);
        dimension(input.integridad(), "integridad", invalid);
        dimension(input.disponibilidad(), "disponibilidad", invalid);
        List<PersonalDataType> types = input.tiposDatosPersonales() == null ? List.of() : input.tiposDatosPersonales();
        if (types.stream().anyMatch(Objects::isNull) || types.stream().distinct().count() != types.size()) {
            invalid.add("tiposDatosPersonales");
        }

        String purpose = optionalText(input.finalidadTratamiento());
        String justification = optionalText(input.justificacionNoAplica());
        boolean personal = Boolean.TRUE.equals(input.contieneDatosPersonales());
        if (!personal) {
            if (Boolean.TRUE.equals(input.contieneDatosMenores())) invalid.add("contieneDatosMenores");
            if (!types.isEmpty()) invalid.add("tiposDatosPersonales");
            if (purpose != null) invalid.add("finalidadTratamiento");
            if (input.autorizacionTratamiento() != null) invalid.add("autorizacionTratamiento");
            if (justification != null) invalid.add("justificacionNoAplica");
        } else if ((input.autorizacionTratamiento() == Authorization.NO_APLICA) != (justification != null)) {
            invalid.add("justificacionNoAplica");
        }
        if (!invalid.isEmpty()) {
            throw new ClassificationFailure(ClassificationFailure.Reason.INVALID, invalid);
        }
        return new Input(input.confidencialidad(), input.integridad(), input.disponibilidad(),
                optionalText(input.fundamento()), input.contieneDatosPersonales(), input.contieneDatosMenores(),
                List.copyOf(types), purpose, input.autorizacionTratamiento(), justification);
    }

    static void requireComplete(Input input) {
        var missing = new ArrayList<String>();
        if (input.confidencialidad() == null) missing.add("confidencialidad");
        if (input.integridad() == null) missing.add("integridad");
        if (input.disponibilidad() == null) missing.add("disponibilidad");
        if (input.fundamento() == null) missing.add("fundamento");
        if (input.contieneDatosPersonales() == null) missing.add("contieneDatosPersonales");
        if (Boolean.TRUE.equals(input.contieneDatosPersonales())) {
            if (input.contieneDatosMenores() == null) missing.add("contieneDatosMenores");
            if (input.tiposDatosPersonales().isEmpty()) missing.add("tiposDatosPersonales");
            if (input.finalidadTratamiento() == null) missing.add("finalidadTratamiento");
            if (input.autorizacionTratamiento() == null) missing.add("autorizacionTratamiento");
        }
        if (!missing.isEmpty()) {
            throw new ClassificationFailure(ClassificationFailure.Reason.INCOMPLETE, missing);
        }
    }

    private static void dimension(Integer value, String field, List<String> invalid) {
        if (value != null && value != 1 && value != 3 && value != 5) {
            invalid.add(field);
        }
    }

    static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
