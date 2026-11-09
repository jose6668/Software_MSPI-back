package software.mspi.evidencias.internal.persistence.tenant;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

final class PrivateEvidenceFiles {
    private static final Logger LOG = LoggerFactory.getLogger(PrivateEvidenceFiles.class);
    private static final String CONTENT = "contenido";

    private final Path root;

    PrivateEvidenceFiles(Path directory) throws IOException {
        if (!directory.isAbsolute() || !Files.isDirectory(directory, NOFOLLOW_LINKS) || !Files.isWritable(directory)) {
            throw new IOException("Configure un directorio privado existente con ruta absoluta.");
        }
        root = directory.toRealPath();
    }

    void store(UUID company, UUID evidence, byte[] bytes) throws IOException {
        requireDirectory(root);
        Path companyDirectory = root.resolve(company.toString());
        try {
            Files.createDirectory(companyDirectory);
        } catch (FileAlreadyExistsException existing) {
            requireDirectory(companyDirectory);
        }
        Path entry = Files.createDirectory(companyDirectory.resolve(evidence.toString()));
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    // On an ambiguous commit outcome, keep the file for reconciliation.
                    if (status == STATUS_ROLLED_BACK) discard(entry, evidence);
                }
            });
            try (var channel = FileChannel.open(entry.resolve(CONTENT), CREATE_NEW, WRITE)) {
                var content = ByteBuffer.wrap(bytes);
                while (content.hasRemaining()) channel.write(content);
                channel.force(true);
            }
            if (Files.getFileStore(entry).supportsFileAttributeView("posix")) {
                syncDirectory(entry);
                syncDirectory(companyDirectory);
                syncDirectory(root);
            }
        } catch (IOException | RuntimeException failure) {
            discard(entry, evidence);
            throw failure;
        }
    }

    byte[] read(EvidenceRecord metadata) throws IOException {
        try (var stream = open(metadata)) {
            byte[] bytes = stream.readNBytes(metadata.size + 1);
            if (bytes.length != metadata.size || !metadata.digest.equals(EvidenceContent.sha256(bytes))) {
                throw new IOException("El contenido privado no coincide con los metadatos.");
            }
            return bytes;
        }
    }

    private InputStream open(EvidenceRecord metadata) throws IOException {
        Path companyDirectory = root.resolve(metadata.company.toString());
        requireDirectory(companyDirectory);
        Path entry = companyDirectory.resolve(metadata.id.toString());
        requireDirectory(entry);
        Path content = entry.resolve(CONTENT);
        if (!Files.isRegularFile(content, NOFOLLOW_LINKS)) {
            throw new IOException("Archivo privado no disponible.");
        }
        return Files.newInputStream(content, NOFOLLOW_LINKS);
    }

    private static void requireDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) {
            throw new IOException("Directorio privado no disponible.");
        }
    }

    private static void syncDirectory(Path directory) throws IOException {
        try (var channel = FileChannel.open(directory, READ)) {
            channel.force(true);
        }
    }

    private static void discard(Path entry, UUID evidence) {
        try {
            Files.deleteIfExists(entry.resolve(CONTENT));
            Files.deleteIfExists(entry);
        } catch (IOException failure) {
            LOG.warn("Compensación de evidencia pendiente evidenciaId={}", evidence);
        }
    }
}
