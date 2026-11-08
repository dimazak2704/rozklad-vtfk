package ua.vtc.rozklad.core.shared.web;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** {@code 201 Created} with {@code Location} = the current request path + {@code /{id}}. */
public final class Created {

    private Created() {
    }

    public static <T> ResponseEntity<T> at(UUID id, T body) {
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(id).toUri();
        return ResponseEntity.created(location).body(body);
    }
}
