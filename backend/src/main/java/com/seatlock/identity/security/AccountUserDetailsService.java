package com.seatlock.identity.security;

import com.seatlock.identity.domain.User;
import com.seatlock.identity.domain.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

/** Looks users up by e-mail for username/password login. */
@Component
class AccountUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    AccountUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        return users.findByEmail(User.normalizeEmail(email))
                .map(AuthenticatedUser::from)
                // Spring turns this into BadCredentialsException, so callers can't tell it apart
                .orElseThrow(() -> new UsernameNotFoundException("unknown user"));
    }
}
