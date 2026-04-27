package com.str.backend.lookup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(schema = "str", name = "vrsta_sso")
public class VrstaSsoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_vrste_sso")
    private Long id;

    @Column(name = "naziv", nullable = false)
    private String naziv;

    @Column(name = "rb_dozvoljen", nullable = false)
    private boolean rbDozvoljen;

    protected VrstaSsoEntity() {
    }

    public VrstaSsoEntity(String naziv, boolean rbDozvoljen) {
        this.naziv = naziv;
        this.rbDozvoljen = rbDozvoljen;
    }

    public Long getId() { return id; }
    public String getNaziv() { return naziv; }
    public boolean isRbDozvoljen() { return rbDozvoljen; }
}
