package com.str.backend.objection;

import com.str.backend.attachment.PrilogDto;
import com.str.backend.domain.RnStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Red povijesti prigovora na detalju RB-a (službenik). Bez sadržaja datoteke — dokument se
 * dohvaća zasebno preko {@code /{id}/file}.
 *
 * <p>{@code lessorTaxNumber} je strani porezni broj non-EU iznajmljivača (on nema OIB);
 * {@code resolvedBy} je prikaz aktera (ime službenika ili {@code SYSTEM}), ne sirovi zapis iz baze.
 *
 * <p>{@code prilozi} su dodatne datoteke prigovora (T17), bez dokumenta prigovora ({@code fileName});
 * preuzimaju se preko {@code href} ({@code GET /api/rn/{rn}/attachments/{id}}).
 *
 * <p>{@code brojeviNaIstojAdresi} (T12): kad je RB povučen, važeći brojevi istog iznajmljivača na istoj
 * adresi — upozorenje službeniku prije uvažavanja prigovora na povlačenje (ne blokira ga). Inače prazna lista.
 *
 * <p>{@code zupanijskiPostupak}: postupak vodi županija (T16, ručna KLASA i URBROJ na aktu na koji podnesak
 * odgovara) — samo to, bez obzira na status prigovora. Takav podnesak ne ide u eGOP; obrađuje ga samo županijski
 * službenik, koji ga urudžbira ({@code POST /{id}/register}) prije uvažavanja ili neuvažavanja.
 * {@code urudzbiranje} su urudžbene oznake podneska kad ih ima — ručno upisane ili, u postupku MINTS-a, vraćene
 * iz eGOP-a; inače {@code null}.
 */
public record SuspensionObjectionDto(
        UUID objectionId,
        String registrationNumber,
        String lessorOib,
        String lessorTaxNumber,
        String lessorEmail,
        RnStatus rnStatus,
        String fileName,
        String contentType,
        long fileSize,
        SuspensionObjectionStatus status,
        Instant submittedAt,
        String resolvedBy,
        Instant resolvedAt,
        String resolutionNote,
        List<PrilogDto> prilozi,
        List<String> brojeviNaIstojAdresi,
        boolean zupanijskiPostupak,
        Urudzbiranje urudzbiranje
) {

    /**
     * Urudžbene oznake podneska ({@code egop_pismeno} s {@code act_ref} = {@code PRIGOVOR:<objection_id>}).
     *
     * @param klasa  KLASA predmeta; u postupku MINTS-a klasa predmeta akata RB-a, može nedostajati
     * @param urBroj URBROJ podneska
     * @param datum  dan urudžbiranja (zapisa pismena), po hrvatskom vremenu
     */
    public record Urudzbiranje(String klasa, String urBroj, LocalDate datum) {
    }

    public static SuspensionObjectionDto of(SuspensionObjectionRepository.ObjectionSummary o, String resolvedBy,
                                            String lessorTaxNumber, List<PrilogDto> prilozi,
                                            List<String> brojeviNaIstojAdresi, boolean zupanijskiPostupak,
                                            Urudzbiranje urudzbiranje) {
        return new SuspensionObjectionDto(
                o.getObjectionId(),
                o.getRn(),
                o.getLessorOib(),
                lessorTaxNumber,
                o.getLessorEmail(),
                o.getRnStatus(),
                o.getFileName(),
                o.getContentType(),
                o.getFileSize(),
                o.getStatus(),
                o.getSubmittedAt(),
                resolvedBy,
                o.getResolvedAt(),
                o.getResolutionNote(),
                prilozi,
                brojeviNaIstojAdresi,
                zupanijskiPostupak,
                urudzbiranje);
    }
}
