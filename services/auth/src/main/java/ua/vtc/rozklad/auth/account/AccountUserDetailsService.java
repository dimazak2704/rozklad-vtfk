package ua.vtc.rozklad.auth.account;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Password sign-in lookup. The returned {@link User}'s username is the account UUID (D9), so the session,
 * the OAuth2 authorization and the tokens all carry the same principal name. No account, or an account
 * without a password, is "not found"; the provider then still hashes against a dummy password.
 */
@Service
public class AccountUserDetailsService implements UserDetailsService {

    private final AccountRepository accounts;

    public AccountUserDetailsService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public UserDetails loadUserByUsername(String identifier) {
        Account account = accounts.findByIdentifier(identifier)
                .filter(Account::hasPassword)
                .orElseThrow(() -> new UsernameNotFoundException("no password account"));
        return toUser(account, account.passwordHash());
    }

    /** The principal of a signed-in account; {@code password} may be empty (Google sign-in). */
    public static User toUser(Account account, String password) {
        List<SimpleGrantedAuthority> authorities = account.roles().stream().sorted()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
        return new User(account.id().toString(), password, account.enabled(), true, true, true, authorities);
    }
}
