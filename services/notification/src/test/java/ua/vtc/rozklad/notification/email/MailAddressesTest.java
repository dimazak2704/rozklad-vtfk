package ua.vtc.rozklad.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.notification.email.MailAddresses.Status;
import ua.vtc.rozklad.notification.email.MailAddresses.VerifiedBy;

/** Masking, strict parsing, header cleaning and the verification policy of addresses. */
class MailAddressesTest {

    private static final List<String> TRUSTED = List.of("vtc.vn.ua");

    @Test
    void masksForLogsAndTheAdminLog() {
        assertThat(MailAddresses.mask("k.mykola@vtc.vn.ua")).isEqualTo("k•••@vtc.vn.ua");
        assertThat(MailAddresses.mask("broken")).isEqualTo("•••");
        assertThat(MailAddresses.maskAll("550 5.1.1 <k.mykola@vtc.vn.ua>: Recipient address rejected"))
                .isEqualTo("550 5.1.1 <k•••@vtc.vn.ua>: Recipient address rejected");
    }

    @Test
    void parsesExactlyOneMailbox() {
        assertThat(MailAddresses.normalize(" K.Mykola@VTC.vn.ua ")).contains("k.mykola@vtc.vn.ua");
        assertThat(MailAddresses.normalize("a@b.ua, c@d.ua")).isEmpty();
        assertThat(MailAddresses.normalize("Ім'я <a@b.ua>")).isEmpty();
        assertThat(MailAddresses.normalize("a@b.ua\r\nBcc: c@d.ua")).isEmpty();
        assertThat(MailAddresses.normalize("nobody")).isEmpty();
        assertThat(MailAddresses.normalize("a@localhost")).isEmpty();
        assertThat(MailAddresses.normalize("")).isEmpty();
        assertThat(MailAddresses.normalize(null)).isEmpty();
        assertThat(MailAddresses.normalize("x".repeat(250) + "@b.ua")).isEmpty();
    }

    @Test
    void googleSignInOrTheCollegeDomainVerifiesAnAddress() {
        assertThat(MailAddresses.verify("k@vtc.vn.ua", false, TRUSTED))
                .returns(Status.VERIFIED, MailAddresses.Verification::status)
                .returns(VerifiedBy.TRUSTED_DOMAIN, MailAddresses.Verification::verifiedBy);
        assertThat(MailAddresses.verify("k@gmail.com", true, TRUSTED))
                .returns(Status.VERIFIED, MailAddresses.Verification::status)
                .returns(VerifiedBy.GOOGLE, MailAddresses.Verification::verifiedBy);
        assertThat(MailAddresses.verify("k@gmail.com", false, TRUSTED).status()).isEqualTo(Status.UNVERIFIED);
        // a lookalike domain is not the college's
        assertThat(MailAddresses.verify("k@evil-vtc.vn.ua", false, TRUSTED).status()).isEqualTo(Status.UNVERIFIED);
        assertThat(MailAddresses.verify("k@vtc.vn.ua", false, List.of()).status()).isEqualTo(Status.UNVERIFIED);
        assertThat(MailAddresses.verify(null, true, TRUSTED).status()).isEqualTo(Status.MISSING);
        assertThat(MailAddresses.verify("not an address", true, TRUSTED).status()).isEqualTo(Status.MISSING);
    }

    @Test
    void fingerprintHidesTheAddressButIsStable() {
        String one = MailAddresses.fingerprint("K@vtc.vn.ua");
        assertThat(one).hasSize(32).isEqualTo(MailAddresses.fingerprint("k@vtc.vn.ua")).doesNotContain("vtc");
    }

    @Test
    void headerValuesLoseLineBreaksAndControls() {
        assertThat(MailHeaders.clean("Заміна\r\nBcc: x@y.z\t\u0000 кінець")).isEqualTo("Заміна Bcc: x@y.z кінець");
        assertThat(MailHeaders.clean("  a b  ")).isEqualTo("a b");
        assertThat(MailHeaders.clean(null)).isEmpty();
        assertThat(MailHeaders.clean("я".repeat(300))).hasSize(MailHeaders.MAX_LENGTH).endsWith("…");
    }
}
