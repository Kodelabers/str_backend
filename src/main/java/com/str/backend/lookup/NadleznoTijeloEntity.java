package com.str.backend.lookup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(schema = "str", name = "nadlezno_tijelo")
public class NadleznoTijeloEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_nadleznog_tijela")
    private Long id;

    @Column(name = "naziv", nullable = false)
    private String naziv;

    protected NadleznoTijeloEntity() {
    }

    public NadleznoTijeloEntity(String naziv) {
        this.naziv = naziv;
    }

    public Long getId() { return id; }
    public String getNaziv() { return naziv; }
}
