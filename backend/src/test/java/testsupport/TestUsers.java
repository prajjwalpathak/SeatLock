package testsupport;

import com.seatlock.identity.domain.AuthProvider;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.domain.UserRepository;
import com.seatlock.identity.security.AuthenticatedUser;
import com.seatlock.identity.security.TokenService;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Creates users straight in the database and issues real access tokens for them.
 * Skips /auth/register on purpose: 200 BCrypt hashes would add ~20 s and test nothing new.
 * The tokens are genuine JWTs, so every race request is still authenticated by the real filter chain.
 */
public final class TestUsers {

    private final UserRepository users;
    private final TokenService tokens;

    public TestUsers(UserRepository users, TokenService tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    /** {@code count} fresh USER accounts; returns one bearer token per user, in order. */
    public List<String> tokens(String prefix, int count) {
        String run = UUID.randomUUID().toString().substring(0, 8);
        List<User> saved = users.saveAll(IntStream.range(0, count)
                .mapToObj(i -> new User("%s-%s-%d@example.com".formatted(prefix, run, i),
                        "{bcrypt}unused", prefix + "-" + i, Role.USER, AuthProvider.LOCAL))
                .toList());
        return saved.stream()
                .map(u -> tokens.issueAccessToken(
                        new AuthenticatedUser(u.getId(), u.getEmail(), Role.USER, "unused")).value())
                .toList();
    }
}
