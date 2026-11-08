package software.mspi.gestion.internal.web;

import java.nio.file.Files;
import java.nio.file.Path;

/** Enforced deployment requires Linux Docker with cgroup v2 and a bounded tmpfs. */
final class ContainerResourceBudget {
    private ContainerResourceBudget() {}
    static void verify() {
        try {
            if (!Files.isRegularFile(Path.of("/.dockerenv"))) throw new IllegalStateException();
            long memory = Long.parseLong(read("memory.max"));
            long swap = Long.parseLong(read("memory.swap.max"));
            long pids = Long.parseLong(read("pids.max"));
            String[] cpu = read("cpu.max").split("\\s+");
            long quota = Long.parseLong(cpu[0]); long period = Long.parseLong(cpu[1]);
            var temporary = Files.getFileStore(Path.of("/tmp"));
            boolean root = Files.readAllLines(Path.of("/proc/self/status")).stream()
                    .filter(line -> line.startsWith("Uid:")).anyMatch(line -> "0".equals(line.trim().split("\\s+")[1]));
            if (memory < 268435456 || memory > 1073741824L || swap != 0 || pids < 1 || pids > 128
                    || quota < 1 || period < 1 || quota > 2 * period || root
                    || !"tmpfs".equals(temporary.type()) || temporary.getTotalSpace() > 134217728
                    || !Files.getFileStore(Path.of("/")).isReadOnly()) throw new IllegalStateException();
        } catch (Exception failure) {
            throw new IllegalStateException("El perfil de recursos exige Docker no root, cgroup v2 limitado, raiz de solo lectura y tmpfs acotado.");
        }
    }
    private static String read(String name) throws Exception {
        return Files.readString(Path.of("/sys/fs/cgroup", name)).trim();
    }
}
