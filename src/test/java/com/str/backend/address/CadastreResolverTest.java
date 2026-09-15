package com.str.backend.address;

import com.str.backend.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Katastar se čita iz registra po id-u kućnog broja. Iz zahtjeva se uzima samo čestica, i to
 * samo kad je registar nema.
 */
class CadastreResolverTest {

    private static final long ID = 10051L;
    private static final String ULICA = "Marulićeva";
    private static final String BROJ = "5";

    private HouseNumberRepository repository;
    private CadastreResolver resolver;

    @BeforeEach
    void setUp() {
        repository = mock(HouseNumberRepository.class);
        resolver = new CadastreResolver(repository);
    }

    // ---- adresa nije iz registra ----

    @Test
    void withoutHouseNumberId_takesTypedParcel_andLeavesMunicipalityEmpty() {
        CadastreResolver.Cadastre c = resolver.resolve(null, ULICA, BROJ, " 430/1 ");

        assertThat(c.katOpcinaNaziv()).isNull();
        assertThat(c.kcCestica()).isEqualTo("430/1");
        assertThat(c.sifra()).isNull();
        verify(repository, never()).findCadastreById(anyLong());
    }

    @Test
    void withoutHouseNumberId_blankParcel_isNull() {
        assertThat(resolver.resolve(null, ULICA, BROJ, "   ").kcCestica()).isNull();
    }

    // ---- zaštita od podmetnutog id-a ----

    @Test
    void unknownHouseNumberId_isRejected() {
        when(repository.findCadastreById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(ID, ULICA, BROJ, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.cadastre.houseNumber.unknown");
    }

    /** Id tuđeg kućnog broja: katastar bi bio interno dosljedan, ali za drugu adresu. */
    @Test
    void houseNumberFromAnotherStreet_isRejected() {
        stub("5", "Ilica", "337323|430/1", "SPLIT");

        assertThatThrownBy(() -> resolver.resolve(ID, ULICA, BROJ, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.cadastre.houseNumber.mismatch");
    }

    @Test
    void houseNumberWithAnotherNumber_isRejected() {
        stub("7", ULICA, "337323|430/1", "SPLIT");

        assertThatThrownBy(() -> resolver.resolve(ID, ULICA, BROJ, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.cadastre.houseNumber.mismatch");
    }

    /** Registar ne piše nazive dosljedno — verzal i višestruke bjeline ne smiju rušiti zahtjev. */
    @Test
    void addressComparison_ignoresCaseAndWhitespace() {
        stub(" 5a ", "ULICA  KRALJA   TOMISLAVA", "337323|430/1", "SPLIT");

        CadastreResolver.Cadastre c = resolver.resolve(ID, "Ulica kralja Tomislava", "5A", null);

        assertThat(c.katOpcinaNaziv()).isEqualTo("SPLIT");
    }

    // ---- čestica: registar ima prednost ----

    @Test
    void registryHasParcel_userSentNothing_takesRegistry() {
        stub(BROJ, ULICA, "337323|430/1", "SPLIT");

        CadastreResolver.Cadastre c = resolver.resolve(ID, ULICA, BROJ, null);

        assertThat(c.kcCestica()).isEqualTo("430/1");
    }

    /** Fronta šalje ono što je autocomplete vratio — isti broj, eventualno s bjelinama. */
    @Test
    void registryHasParcel_userSentSameValue_isAccepted() {
        stub(BROJ, ULICA, "337323|430/1", "SPLIT");

        assertThat(resolver.resolve(ID, ULICA, BROJ, "  430/1 ").kcCestica()).isEqualTo("430/1");
    }

    /** Povučeni podaci se ne mijenjaju — za to postoji zahtjev za promjenom (sastanak, t. 3). */
    @Test
    void registryHasParcel_userSentDifferentValue_isRejected() {
        stub(BROJ, ULICA, "337323|430/1", "SPLIT");

        assertThatThrownBy(() -> resolver.resolve(ID, ULICA, BROJ, "999/9"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.cadastre.parcel.mismatch");
    }

    /** Na CDU registar nema česticu za 28,7 % adresa — tada korisnik smije upisati svoju. */
    @Test
    void registryLacksParcel_takesTypedValue() {
        stub(BROJ, ULICA, null, "SPLIT");

        CadastreResolver.Cadastre c = resolver.resolve(ID, ULICA, BROJ, "430/1");

        assertThat(c.katOpcinaNaziv()).isEqualTo("SPLIT");
        assertThat(c.kcCestica()).isEqualTo("430/1");
        assertThat(c.sifra()).isNull();
    }

    @Test
    void registryHasOnlyMunicipalityPrefix_countsAsMissingParcel() {
        stub(BROJ, ULICA, "337323|", "SPLIT");

        assertThat(resolver.resolve(ID, ULICA, BROJ, "430/1").kcCestica()).isEqualTo("430/1");
    }

    // ---- općina i šifra ----

    /** Općina se nikad ne uzima iz zahtjeva — zahtjev je ni ne nosi. */
    @Test
    void municipality_comesFromRegistry() {
        stub(BROJ, ULICA, "337323|430/1", "  SPLIT ");

        assertThat(resolver.resolve(ID, ULICA, BROJ, null).katOpcinaNaziv()).isEqualTo("SPLIT");
    }

    @Test
    void municipality_blankInRegistry_isNull() {
        stub(BROJ, ULICA, "337323|430/1", "  ");

        assertThat(resolver.resolve(ID, ULICA, BROJ, null).katOpcinaNaziv()).isNull();
    }

    /** Šifra ide u {@code house_number_code} kao sirova registarska vrijednost, s prefiksom. */
    @Test
    void sifra_isRawRegistryValue() {
        stub(BROJ, ULICA, "337323|430/1", "SPLIT");

        assertThat(resolver.resolve(ID, ULICA, BROJ, null).sifra()).isEqualTo("337323|430/1");
    }

    /** Lokalni mock prije changeseta 124 drži česticu bez prefiksa. */
    @Test
    void registryValueWithoutPrefix_isParcelAndSifraAlike() {
        stub(BROJ, ULICA, "1201/1", "GRAD ZAGREB");

        CadastreResolver.Cadastre c = resolver.resolve(ID, ULICA, BROJ, null);

        assertThat(c.kcCestica()).isEqualTo("1201/1");
        assertThat(c.sifra()).isEqualTo("1201/1");
    }

    private void stub(String broj, String ulica, String kcBroj, String katOpcina) {
        when(repository.findCadastreById(ID)).thenReturn(Optional.of(new HouseNumberRepository.CadastreRow() {
            @Override public String getBroj() { return broj; }
            @Override public String getNazivUlice() { return ulica; }
            @Override public String getKcBroj() { return kcBroj; }
            @Override public String getKatOpcinaNaziv() { return katOpcina; }
        }));
    }
}
