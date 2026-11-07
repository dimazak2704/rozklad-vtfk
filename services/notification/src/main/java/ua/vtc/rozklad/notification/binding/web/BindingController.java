package ua.vtc.rozklad.notification.binding.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.notification.binding.BindingCodeRequests;
import ua.vtc.rozklad.notification.binding.BindingCodeRequests.Caller;
import ua.vtc.rozklad.notification.binding.BindingCodes.IssuedCode;
import ua.vtc.rozklad.notification.binding.BindingType;
import ua.vtc.rozklad.notification.binding.ChatBinding;
import ua.vtc.rozklad.notification.binding.ChatBindings;
import ua.vtc.rozklad.notification.config.KeycloakRealmRoleConverter;
import ua.vtc.rozklad.notification.shared.ChatIds;

/** Chat bindings and one-time binding codes (CONTRACTS §6). */
@RestController
@RequestMapping("/api/notify/bindings")
@Tag(name = "Прив’язки чатів")
public class BindingController {

    private final ChatBindings bindings;
    private final BindingCodeRequests codeRequests;

    public BindingController(ChatBindings bindings, BindingCodeRequests codeRequests) {
        this.bindings = bindings;
        this.codeRequests = codeRequests;
    }

    @GetMapping
    @Operation(summary = "Прив’язані чати (id чату замасковано)")
    public List<BindingDto> list() {
        return bindings.list().stream().map(BindingDto::of).toList();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Відв’язати чат")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        bindings.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/codes")
    @Operation(summary = "Одноразовий код прив’язки (24 год) і deep link; викладач — лише для себе")
    public BindingCodeDto createCode(@Valid @RequestBody BindingCodeRequest request, @AuthenticationPrincipal Jwt jwt) {
        Caller caller = new Caller(jwt.getClaimAsString("preferred_username") != null
                ? jwt.getClaimAsString("preferred_username") : jwt.getSubject(),
                KeycloakRealmRoleConverter.realmRoles(jwt), jwt.getTokenValue());
        return BindingCodeDto.of(codeRequests.create(request.type(), request.groupId(), request.teacherId(), caller));
    }

    public record BindingCodeRequest(@NotNull(message = "Оберіть тип прив’язки.") BindingType type, UUID groupId,
                                     UUID teacherId) {
    }

    /**
     * {@code chatId} is masked. Additive to the contract: {@code groupCode}, {@code digestEnabled}.
     */
    public record BindingDto(UUID id, BindingType type, String chatId, String title, UUID groupId, String groupCode,
                             UUID teacherId, boolean digestEnabled, Instant createdAt) {

        static BindingDto of(ChatBinding binding) {
            return new BindingDto(binding.getId(), binding.getType(), ChatIds.mask(binding.getChatId()),
                    binding.getTitle(), binding.getGroupId(), binding.getGroupCode(), binding.getTeacherId(),
                    binding.isDigestEnabled(), binding.getCreatedAt());
        }
    }

    /**
     * {@code {code, deepLink, expiresAt}} of the contract plus {@code type}, {@code label} and, for
     * chats that can be groups, {@code groupLink} (adds the bot to a group with the code) and
     * {@code command} ({@code /bind CODE}).
     */
    public record BindingCodeDto(String code, String deepLink, Instant expiresAt, BindingType type, String label,
                                 String groupLink, String command) {

        static BindingCodeDto of(IssuedCode issued) {
            return new BindingCodeDto(issued.code(), issued.deepLink(), issued.expiresAt(), issued.target().type(),
                    issued.target().label(), issued.groupLink(), issued.command());
        }
    }
}
