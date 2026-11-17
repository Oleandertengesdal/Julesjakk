package no.sjakkportal.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import no.sjakkportal.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    UserView me(@AuthenticationPrincipal CurrentUser currentUser) {
        return userService.view(currentUser.id());
    }

    /** {@code null} leaves a field unchanged; an empty string removes the link. */
    record AccountsRequest(@Pattern(regexp = "|[A-Za-z0-9_-]{2,50}") String lichessUsername,
                           @Pattern(regexp = "|[A-Za-z0-9_-]{2,50}") String chessComUsername) {
    }

    @PutMapping("/me/accounts")
    UserView linkAccounts(@Valid @RequestBody AccountsRequest body, @AuthenticationPrincipal CurrentUser currentUser) {
        return userService.linkAccounts(currentUser.id(), body.lichessUsername(), body.chessComUsername());
    }

    @GetMapping
    List<UserView> list() {
        return userService.list();
    }

    @GetMapping("/{id}")
    UserView one(@PathVariable long id) {
        return userService.view(id);
    }
}
