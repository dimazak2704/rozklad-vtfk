package ua.vtc.rozklad.core.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.catalog.CatalogQueries;
import ua.vtc.rozklad.core.catalog.TeacherInfo;
import ua.vtc.rozklad.core.catalog.TeacherRef;
import ua.vtc.rozklad.core.identity.CurrentUserService;
import ua.vtc.rozklad.core.identity.UserProfile;
import ua.vtc.rozklad.core.shared.security.CurrentUser;
import ua.vtc.rozklad.core.shared.security.CurrentUserProvider;

/** {@link CurrentUserService} from the access token ({@code sub}) and {@code teacher.keycloak_user_id}. */
@Service
@Transactional(readOnly = true)
class TokenCurrentUserService implements CurrentUserService {

    private final CurrentUserProvider users;
    private final CatalogQueries catalog;

    TokenCurrentUserService(CurrentUserProvider users, CatalogQueries catalog) {
        this.users = users;
        this.catalog = catalog;
    }

    @Override
    public CurrentUser require() {
        return users.require();
    }

    @Override
    public Optional<CurrentUser> find() {
        return users.find();
    }

    @Override
    public UserProfile profile() {
        CurrentUser user = users.require();
        return new UserProfile(user.id(), user.username(), user.roles().stream().sorted().toList(),
                teacherOf(user).map(TeacherInfo::ref).orElse(null));
    }

    @Override
    public Optional<TeacherRef> currentTeacher() {
        return users.find().flatMap(this::teacherOf).map(TeacherInfo::ref);
    }

    @Override
    public UUID requireTeacherId() {
        return teacherOf(users.require()).map(TeacherInfo::id).orElseThrow(IdentityProblems::accountNotLinked);
    }

    private Optional<TeacherInfo> teacherOf(CurrentUser user) {
        return user.id() == null ? Optional.empty() : catalog.teacherByAccount(user.id());
    }
}
