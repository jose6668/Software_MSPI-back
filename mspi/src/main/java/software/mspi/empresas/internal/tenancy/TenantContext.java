package software.mspi.empresas.internal.tenancy;
import java.util.UUID;
import org.springframework.transaction.TransactionStatus;

final class TenantContext {
    private final ThreadLocal<Frame> current = new ThreadLocal<>();
    Frame current() { return current.get(); }
    Frame require() {
        var frame = current();
        if (frame == null) throw new IllegalStateException("No existe una unidad empresarial autorizada.");
        return frame;
    }
    void bind(Frame frame) { current.set(frame); }
    void clear() { current.remove(); }
    static final class Frame {
        final UUID actor;
        final UUID company;
        final TenantConnectionPools.Lease lease;
        TransactionStatus status;
        Frame(UUID actor, UUID company, TenantConnectionPools.Lease lease) {
            this.actor = actor; this.company = company; this.lease = lease;
        }
        void rejectChange() {
            if (status != null) status.setRollbackOnly();
            throw new IllegalStateException("No se permite cambiar empresa o actor dentro de la transacción.");
        }
    }
}
