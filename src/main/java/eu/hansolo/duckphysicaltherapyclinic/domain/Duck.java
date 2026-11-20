package eu.hansolo.duckphysicaltherapyclinic.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.LocalDate;


@Entity
public class Duck extends PanacheEntity {
    public String    name;
    @Enumerated(EnumType.STRING)
    public DuckBreed breed;
    public LocalDate birthDate;
    /** Plain foreign key to {@link Owner}, like in the duck insurance. */
    public Long      ownerId;


    public Duck() {
    }

    public Duck(final String name, final DuckBreed breed, final LocalDate birthDate, final Long ownerId) {
        this.name      = name;
        this.breed     = breed;
        this.birthDate = birthDate;
        this.ownerId   = ownerId;
    }
}
