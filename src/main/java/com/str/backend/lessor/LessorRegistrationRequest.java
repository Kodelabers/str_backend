package com.str.backend.lessor;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

@Getter
@Setter
public class LessorRegistrationRequest {

    @NotBlank @Size(max = 128)
    private String ime;

    @NotBlank @Size(max = 128)
    private String prezime;

    @NotNull @Past
    private LocalDate datumRodjenja;

    @NotBlank @Size(max = 64)
    private String porezniBroj;

    @NotNull
    private Integer zemljaPrebivalistaId;

    @NotBlank @Size(max = 500)
    private String stalnaAdresa;

    @NotBlank @Size(max = 32)
    private String vrstaIsprave;

    @NotBlank @Size(max = 64)
    private String brojIsprave;

    @NotBlank @Email @Size(max = 255)
    private String email;

    @Size(max = 32)
    private String telefon;

    @NotBlank @Size(min = 12, max = 128) @NotBreached
    private String password;

    @NotBlank
    private String passwordPotvrda;

    @NotNull
    private MultipartFile ispravaPrednja;

    private MultipartFile ispravaStraznja;
}
