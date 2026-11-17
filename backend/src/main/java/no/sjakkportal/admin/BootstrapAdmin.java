package no.sjakkportal.admin;

import no.sjakkportal.auth.AuthService;
import no.sjakkportal.auth.PasswordPolicy;
import no.sjakkportal.config.SjakkportalProperties;
import no.sjakkportal.security.Role;
import no.sjakkportal.user.AppUser;
import no.sjakkportal.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Registration is invite-only and only admins create invites, so the first admin has to come from
 * somewhere: when no admin exists and SJAKKPORTAL_ADMIN_USERNAME / _PASSWORD are set, one is created
 * at startup and must change the password at first login.
 */
@Component
public class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final SjakkportalProperties properties;
    private final UserRepository users;
    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;

    public BootstrapAdmin(SjakkportalProperties properties, UserRepository users, JdbcClient jdbc, PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.users = users;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        SjakkportalProperties.BootstrapAdmin config = properties.bootstrapAdmin();
        if (config == null || config.username() == null || config.username().isBlank()
                || config.password() == null || config.password().isBlank()) {
            return;
        }
        boolean adminExists = jdbc.sql("SELECT count(*) FROM app_user WHERE role = 'ADMIN'").query(Integer.class).single() > 0;
        String username = AuthService.normalizeUsername(config.username());
        if (adminExists || users.existsByUsername(username)) {
            return;
        }
        PasswordPolicy.check(username, config.password());
        AppUser admin = AppUser.withLogin(username, "Administrator", passwordEncoder.encode(config.password()), Role.ADMIN);
        admin.changePassword(admin.getPasswordHash(), true);
        users.save(admin);
        log.info("Created bootstrap admin '{}'; the password must be changed at first login", username);
    }
}
