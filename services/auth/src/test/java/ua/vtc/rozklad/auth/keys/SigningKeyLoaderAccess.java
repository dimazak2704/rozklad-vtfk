package ua.vtc.rozklad.auth.keys;

import com.nimbusds.jose.jwk.RSAKey;

/** Test access to the package-private key generator. */
public final class SigningKeyLoaderAccess {

    private SigningKeyLoaderAccess() {
    }

    public static RSAKey generated(String keyId) {
        return SigningKeyLoader.generated(keyId);
    }
}
