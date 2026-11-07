package software.mspi.identidad.api;

/** Hash for the account creation transaction, after the invitation proves mailbox possession. */
public interface InvitedAccountPassword {
    String encode(String rawPassword);
}
