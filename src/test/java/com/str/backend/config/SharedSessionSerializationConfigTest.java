package com.str.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sesiju u {@code session.spring_session} piše i str-internal-api na Spring Securityju 5.7, gdje
 * osnovne klase imaju {@code serialVersionUID} 570 (kod nas 620). Zapis iz 5.7 ovdje se dobiva tako
 * da se u našem zapisu broj zamijeni, točno kao što ga je izmjerila baza na CDU-u.
 */
class SharedSessionSerializationConfigTest {

    private static final long SPRING_SECURITY_57 = 570L;
    private static final long SPRING_SECURITY_6 = 620L;

    private final ConversionService conversionService =
            new SharedSessionSerializationConfig().springSessionConversionService();

    @Test
    void readsSecurityContextWrittenBySpringSecurity57() throws Exception {
        SecurityContextImpl context = context();
        byte[] from57 = asSpringSecurity57(serialize(context));

        // bez tolerancije: upravo greška koju str-internal-api ima s našom sesijom, samo obrnuto
        assertThatThrownBy(() -> new ObjectInputStream(new ByteArrayInputStream(from57)).readObject())
                .isInstanceOf(InvalidClassException.class)
                .hasMessageContaining("serialVersionUID = 570");

        Object read = conversionService.convert(from57, Object.class);

        assertThat(read).isEqualTo(context);
    }

    @Test
    void ordinaryAttributes_roundTripUnchanged() {
        HashMap<String, Object> value = new HashMap<>();
        value.put("legalOib", "33333333360");
        value.put("functions", new java.util.ArrayList<>(List.of("Direktor")));

        byte[] bytes = conversionService.convert(value, byte[].class);

        assertThat(conversionService.convert(bytes, Object.class)).isEqualTo(value);
    }

    @Test
    void ourOwnSecurityContext_roundTripUnchanged() {
        SecurityContextImpl context = context();

        byte[] bytes = conversionService.convert(context, byte[].class);

        assertThat(conversionService.convert(bytes, Object.class)).isEqualTo(context);
    }

    private static SecurityContextImpl context() {
        return new SecurityContextImpl(new UsernamePasswordAuthenticationToken(
                "f373a9ee-e764-490a-9e82-821002b5f08e", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private static byte[] serialize(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }

    /** U opisu svake Spring Security klase s brojem 620 (iza imena klase) upiše 570. */
    private static byte[] asSpringSecurity57(byte[] serialized) {
        byte[] patched = serialized.clone();
        for (String cls : List.of(SecurityContextImpl.class.getName(),
                UsernamePasswordAuthenticationToken.class.getName(), SimpleGrantedAuthority.class.getName())) {
            byte[] name = cls.getBytes(StandardCharsets.UTF_8);
            int at = indexOf(patched, name);
            assertThat(at).as("klasa %s u zapisu", cls).isPositive();
            ByteBuffer uid = ByteBuffer.wrap(patched, at + name.length, Long.BYTES);
            assertThat(uid.duplicate().getLong()).as("broj za %s", cls).isEqualTo(SPRING_SECURITY_6);
            uid.putLong(SPRING_SECURITY_57);
        }
        return patched;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
