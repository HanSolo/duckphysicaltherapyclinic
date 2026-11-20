package eu.hansolo.duckphysicaltherapyclinic.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;


/** The physical therapist treating the ducks (the "Vet" of the duck insurance). */
@Entity
public class Therapist extends PanacheEntity {
    public String name;
    public String specialty;
    public String city;


    public Therapist() {
    }

    public Therapist(final String name, final String specialty, final String city) {
        this.name      = name;
        this.specialty = specialty;
        this.city      = city;
    }
}
