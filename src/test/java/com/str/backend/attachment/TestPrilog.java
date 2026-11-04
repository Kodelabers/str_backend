package com.str.backend.attachment;

import java.util.UUID;

/** {@link PrilogRepository.PrilogSazetak} za testove (projekcija je sučelje). */
public record TestPrilog(Long id, VrstaVlasnika vrstaVlasnika, UUID vlasnikId, String nazivDatoteke,
                         String contentType, long velicina, String ucitao, Integer egopJop, Integer egopRbr)
        implements PrilogRepository.PrilogSazetak {

    public static TestPrilog vezan(long id, VrstaVlasnika vrsta, UUID vlasnikId, String naziv, String ucitao) {
        return new TestPrilog(id, vrsta, vlasnikId, naziv, "application/pdf", 3, ucitao, null, null);
    }

    public static TestPrilog nevezan(long id, String ucitao) {
        return new TestPrilog(id, null, null, "prilog-" + id + ".pdf", "application/pdf", 3, ucitao, null, null);
    }

    public TestPrilog withVelicina(long novaVelicina) {
        return new TestPrilog(id, vrstaVlasnika, vlasnikId, nazivDatoteke, contentType, novaVelicina, ucitao,
                egopJop, egopRbr);
    }

    public TestPrilog prilozen(int jop, int rbr) {
        return new TestPrilog(id, vrstaVlasnika, vlasnikId, nazivDatoteke, contentType, velicina, ucitao, jop, rbr);
    }

    @Override public Long getId() { return id; }
    @Override public VrstaVlasnika getVrstaVlasnika() { return vrstaVlasnika; }
    @Override public UUID getVlasnikId() { return vlasnikId; }
    @Override public String getNazivDatoteke() { return nazivDatoteke; }
    @Override public String getContentType() { return contentType; }
    @Override public long getVelicina() { return velicina; }
    @Override public String getUcitao() { return ucitao; }
    @Override public Integer getEgopJop() { return egopJop; }
    @Override public Integer getEgopRbr() { return egopRbr; }
}
