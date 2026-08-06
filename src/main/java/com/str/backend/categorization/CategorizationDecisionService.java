package com.str.backend.categorization;

import com.str.backend.categorization.CategorizationDecisionEntity.CategorizationDecisionMetadata;
import com.str.backend.exception.BusinessException;
import com.str.backend.lookup.AccommodationTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;

@Service
public class CategorizationDecisionService {

    private static final byte[] MAGIC_PDF = {0x25, 0x50, 0x44, 0x46};                 // %PDF
    private static final byte[] MAGIC_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] MAGIC_PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private final CategorizationDecisionRepository repository;
    private final AccommodationTypeRepository accommodationTypeRepository;

    public CategorizationDecisionService(CategorizationDecisionRepository repository,
                                        AccommodationTypeRepository accommodationTypeRepository) {
        this.repository = repository;
        this.accommodationTypeRepository = accommodationTypeRepository;
    }

    @Transactional
    public CategorizationDecisionResponse upload(String oib, CategorizationDecisionRequest req) {
        MultipartFile file = req.getDatoteka();
        if (file == null || file.isEmpty()) {
            throw new BusinessException("error.categorization.file.empty");
        }
        byte[] content = readBytes(file);
        String contentType = detectContentType(content);

        String typeCode = trimToNull(req.getVrstaSifra());
        if (typeCode != null && accommodationTypeRepository.findByCodeIgnoreCase(typeCode).isEmpty()) {
            throw new BusinessException("error.accommodation.type.unknown");
        }

        CategorizationDecisionEntity entity = CategorizationDecisionEntity.create(
                oib, safeFileName(file.getOriginalFilename()), contentType, content,
                new CategorizationDecisionMetadata(
                        trimToNull(req.getNazivObjekta()),
                        typeCode,
                        trimToNull(req.getAdresa()),
                        trimToNull(req.getBrojRjesenja()),
                        req.getDatumRjesenja(),
                        req.getBrKreveta(),
                        trimToNull(req.getNapomena())));

        return CategorizationDecisionResponse.of(repository.save(entity));
    }

    /**
     * Tip se određuje iz sadržaja, ne iz {@code Content-Type} headera — header postavlja
     * klijent i može lagati, a ovdje pohranjujemo datoteku koju će kasnije otvarati
     * nadležno tijelo. Dopušteni su PDF, JPEG i PNG, isto što frontend nudi u dropzoneu.
     */
    private static String detectContentType(byte[] content) {
        if (startsWith(content, MAGIC_PDF)) return "application/pdf";
        if (startsWith(content, MAGIC_JPEG)) return "image/jpeg";
        if (startsWith(content, MAGIC_PNG)) return "image/png";
        throw new BusinessException("error.categorization.file.type");
    }

    private static boolean startsWith(byte[] content, byte[] magic) {
        return content.length >= magic.length
                && Arrays.equals(content, 0, magic.length, magic, 0, magic.length);
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("error.categorization.file.unreadable");
        }
    }

    /** Klijent može poslati i putanju u {@code filename}; zadržava se samo naziv datoteke. */
    private static String safeFileName(String originalFilename) {
        String name = trimToNull(originalFilename);
        if (name == null) {
            return "rjesenje";
        }
        String bare = Paths.get(name.replace('\\', '/')).getFileName().toString();
        return bare.length() > 255 ? bare.substring(bare.length() - 255) : bare;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
