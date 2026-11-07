package software.mspi.evidencias.internal.persistence.tenant;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("mspi.evidence")
record EvidenceSettings(@NotNull Path directory, @NotEmpty Map<UUID, @NotNull @Positive Long> quotas) {}
