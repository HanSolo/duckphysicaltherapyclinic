package eu.hansolo.duckphysicaltherapyclinic.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;

import java.math.BigDecimal;


/** A physical therapy treatment that can be booked. */
@Entity
public class Treatment extends PanacheEntity {
    public String     name;
    @Column(length = 500)
    public String     description;
    public int        durationMinutes;
    public BigDecimal price;


    public Treatment() {
    }

    public Treatment(final String name, final String description, final int durationMinutes, final String price) {
        this.name            = name;
        this.description     = description;
        this.durationMinutes = durationMinutes;
        this.price           = new BigDecimal(price);
    }
}
