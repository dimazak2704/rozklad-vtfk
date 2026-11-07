package ua.vtc.rozklad.notification.binding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatKind;

class BindingCodesTest {

    @Test
    void codesUseAnUnambiguousAlphabet() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String code = BindingCodes.newCode();
            assertThat(code).hasSize(8).matches("[ABCDEFGHJKLMNPQRSTUVWXYZ2-9]{8}");
            codes.add(code);
        }
        assertThat(codes).hasSize(500);
    }

    @Test
    void typedCodesAreNormalized() {
        assertThat(BindingCodes.normalize(" k7qx-m2pa ")).contains("K7QXM2PA");
        assertThat(BindingCodes.normalize("K7QX M2PA")).contains("K7QXM2PA");
        assertThat(BindingCodes.normalize("K7QXM2P")).isEmpty();
        assertThat(BindingCodes.normalize("K7QXM2P0")).isEmpty();
        assertThat(BindingCodes.normalize(null)).isEmpty();
        assertThat(BindingCodes.display("K7QXM2PA")).isEqualTo("K7QX-M2PA");
    }

    @Test
    void onlyTheHashIsStored() {
        String hash = BindingCodes.hash("K7QXM2PA");
        assertThat(hash).hasSize(64).doesNotContain("K7QX").isEqualTo(BindingCodes.hash("K7QXM2PA"));
        assertThat(BindingCodes.hash("K7QXM2PB")).isNotEqualTo(hash);
    }

    @Test
    void codeTypesFitTheirChats() {
        assertThat(BindingType.GROUP_CHAT.fitsChat(ChatKind.GROUP)).isTrue();
        assertThat(BindingType.GROUP_CHAT.fitsChat(ChatKind.PRIVATE)).isFalse();
        assertThat(BindingType.TEACHER.fitsChat(ChatKind.PRIVATE)).isTrue();
        assertThat(BindingType.TEACHER.fitsChat(ChatKind.GROUP)).isFalse();
        assertThat(BindingType.ADMIN_CHAT.fitsChat(ChatKind.GROUP)).isTrue();
        assertThat(BindingType.ADMIN_CHAT.fitsChat(ChatKind.PRIVATE)).isTrue();
        assertThat(BindingType.SUBSCRIBER.fitsChat(ChatKind.CHANNEL)).isFalse();
    }

    @Test
    void targetsKeepTheDatabaseInvariants() {
        UUID group = UUID.randomUUID();
        assertThat(BindingTarget.group(BindingType.GROUP_CHAT, group, "4КІ1").label()).isEqualTo("4КІ1");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new BindingTarget(BindingType.SUBSCRIBER, null, null, null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new BindingTarget(BindingType.TEACHER, null, null, null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
