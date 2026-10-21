package com.ethan.exchange.api.auth;

import com.ethan.exchange.security.AppUser;
import com.ethan.exchange.security.AppUserStore;
import com.ethan.exchange.security.JwtTokenService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AppUserStore users;
    private final PasswordEncoder passwords;
    private final JwtTokenService tokens;

    public AuthController(AppUserStore users, PasswordEncoder passwords, JwtTokenService tokens) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
    }

    @PostMapping("/login")
    @ResponseStatus(HttpStatus.OK)
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        AppUser user = users.findByEmail(request.email()).orElseThrow(AuthController::unauthorized);
        if (!user.enabled() || !passwords.matches(request.password(), user.passwordHash())) {
            throw unauthorized();
        }
        JwtTokenService.IssuedToken token = tokens.issue(user);
        return new LoginResponse(token.value(), token.expiresAt(), user.id(),
                user.email(), user.role());
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
    }
}
