package com.str.backend.categorization;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

/**
 * Multipart tijelo uploada skeniranog rješenja. Obavezna je samo datoteka — ostalo su
 * metapodaci s rješenja koje frontend danas ne šalje (v. {@link CategorizationDecisionEntity}).
 */
@Getter
@Setter
public class CategorizationDecisionRequest {

    @NotNull
    private MultipartFile datoteka;

    @Size(max = 255)
    private String nazivObjekta;

    @Size(max = 64)
    private String vrstaSifra;

    @Size(max = 500)
    private String adresa;

    @Size(max = 64)
    private String brojRjesenja;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @PastOrPresent
    private LocalDate datumRjesenja;

    @Positive
    private Integer brKreveta;

    @Size(max = 1000)
    private String napomena;
}
