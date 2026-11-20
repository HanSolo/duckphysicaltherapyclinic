package eu.hansolo.duckphysicaltherapyclinic.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;

import java.time.LocalDate;


/** The owner of one or more ducks (the "Customer" of the duck insurance). */
@Entity
public class Owner extends PanacheEntity {
    public String    firstName;
    public String    lastName;
    public String    email;
    public LocalDate birthDate;
    public String    city;


    public Owner() {
    }

    public Owner(final String firstName, final String lastName, final String email, final LocalDate birthDate, final String city) {
        this.firstName = firstName;
        this.lastName  = lastName;
        this.email     = email;
        this.birthDate = birthDate;
        this.city      = city;
    }


    public String fullName() { return firstName + " " + lastName; }
}
