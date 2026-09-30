package com.str.backend.auth.nias;

/**
 * @param scriptUrl adresa skripte FINA navigacijske trake za prijavljenu osobu. Nosi {@code navToken}
 *                  i {@code messageId} njezine prijave — frontend je učitava bez izmjena i ne sprema.
 */
public record NavigationBarResponse(String scriptUrl) {
}
