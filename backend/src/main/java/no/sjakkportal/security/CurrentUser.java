package no.sjakkportal.security;

/** The authenticated caller, loaded from the database on every request (never trusted from the token). */
public record CurrentUser(long id, String username, Role role, boolean mustChangePassword) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
