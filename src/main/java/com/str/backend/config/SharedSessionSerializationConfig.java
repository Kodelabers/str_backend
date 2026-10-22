package com.str.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ConfigurableObjectInputStream;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Čitanje sesije koju je možda napisao servis na drugoj verziji Spring Securityja.
 *
 * <p>Tablicu sesija ({@code session.spring_session}) dijelimo sa str-internal-api, koji je na
 * Spring Bootu 2.7, tj. Spring Securityju 5.7. Spring Security osnovnim klasama
 * ({@code SecurityContextImpl}, {@code SimpleGrantedAuthority}, tokeni…) veže
 * {@code serialVersionUID} uz verziju: 570 u 5.7, 620 u 6.x. Građa im je ista, ali JDK serijalizacija
 * na različitom broju odbija čitanje ({@code InvalidClassException}). Zato se za
 * {@code org.springframework.security.*} uzima lokalni opis klase umjesto onog iz zapisa.
 *
 * <p>Kod nas ovo štiti STR ako njihov servis prepiše prijavu u našoj sesiji. Isti bean je i kod njih,
 * i tek tamo rješava 500 na OIB pretrazi, jer oni čitaju sesiju koju piše STR. Pisanje ostaje
 * Springovo, bez izmjena. Kad su svi servisi na istoj glavnoj verziji, ova klasa se može ukloniti.
 *
 * <p>Spring Session JDBC bean uzima po imenu ({@code springSessionConversionService}).
 */
@Configuration(proxyBeanMethods = false)
public class SharedSessionSerializationConfig {

    private static final Logger log = LoggerFactory.getLogger(SharedSessionSerializationConfig.class);

    @Bean("springSessionConversionService")
    public ConversionService springSessionConversionService() {
        ClassLoader classLoader = SharedSessionSerializationConfig.class.getClassLoader();
        GenericConversionService conversionService = new GenericConversionService();
        conversionService.addConverter(Object.class, byte[].class, new SerializingConverter());
        conversionService.addConverter(byte[].class, Object.class, new DeserializingConverter(in -> {
            try (ObjectInputStream ois = new TolerantObjectInputStream(in, classLoader)) {
                return ois.readObject();
            } catch (ClassNotFoundException e) {
                throw new IOException("Klasa iz sesije nije na classpathu", e);
            }
        }));
        return conversionService;
    }

    /** {@link ConfigurableObjectInputStream} (kao Springov default) uz toleranciju za Spring Security klase. */
    static final class TolerantObjectInputStream extends ConfigurableObjectInputStream {

        private static final String TOLERATED_PACKAGE = "org.springframework.security.";
        /** Prvo odstupanje po klasi ide u log, da se na okolini vidi da tolerancija radi; ostala ne. */
        private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

        private final ClassLoader classLoader;

        TolerantObjectInputStream(InputStream in, ClassLoader classLoader) throws IOException {
            super(in, classLoader);
            this.classLoader = classLoader;
        }

        @Override
        protected ObjectStreamClass readClassDescriptor() throws IOException, ClassNotFoundException {
            ObjectStreamClass stream = super.readClassDescriptor();
            if (!stream.getName().startsWith(TOLERATED_PACKAGE)) {
                return stream;
            }
            ObjectStreamClass local = ObjectStreamClass.lookup(ClassUtils.forName(stream.getName(), classLoader));
            if (local == null || local.getSerialVersionUID() == stream.getSerialVersionUID()) {
                return stream;
            }
            if (LOGGED.add(stream.getName())) {
                log.info("shared_session_serial_version_tolerated class={} stream={} local={}",
                        stream.getName(), stream.getSerialVersionUID(), local.getSerialVersionUID());
            }
            return local;
        }
    }
}
