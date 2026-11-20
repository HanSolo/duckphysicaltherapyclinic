package eu.hansolo.duckphysicaltherapyclinic.config;

import eu.hansolo.duckphysicaltherapyclinic.domain.Duck;
import eu.hansolo.duckphysicaltherapyclinic.domain.DuckBreed;
import eu.hansolo.duckphysicaltherapyclinic.domain.Owner;
import eu.hansolo.duckphysicaltherapyclinic.domain.Therapist;
import eu.hansolo.duckphysicaltherapyclinic.domain.Treatment;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;

import java.time.LocalDate;


/** Seeds demo data into an empty database. Sir Quacksalot, Duchess, their owner Anna Keller and the two therapists are taken from the duck insurance, the other owners and ducks are new. */
@ApplicationScoped
public class DataSeeder {

    @Transactional
    void seed(@Observes final StartupEvent event) {
        if (Treatment.count() > 0) { return; }
        final LocalDate today = LocalDate.now();

        final Owner anna  = new Owner("Anna", "Keller", "anna.keller@example.com", LocalDate.of(1989, 4, 12), "Zurich");
        final Owner jonas = new Owner("Jonas", "Lindqvist", "jonas.lindqvist@example.com", LocalDate.of(1991, 8, 21), "Stockholm");
        final Owner mei   = new Owner("Mei", "Tanaka", "mei.tanaka@example.com", LocalDate.of(1985, 12, 2), "Osaka");
        Owner.persist(anna, jonas, mei);

        // Sir Quacksalot and Duchess come from the duck insurance, the other ducks are new
        Duck.persist(new Duck("Sir Quacksalot", DuckBreed.MALLARD, today.minusYears(3).minusMonths(2), anna.id),
                     new Duck("Duchess", DuckBreed.CAYUGA, today.minusYears(1).minusMonths(4), anna.id),
                     new Duck("Pancake", DuckBreed.PEKIN, today.minusYears(2).minusMonths(5), jonas.id),
                     new Duck("Sir Splashalot", DuckBreed.MUSCOVY, today.minusYears(6), jonas.id),
                     new Duck("Mochi", DuckBreed.MANDARIN, today.minusYears(1).minusMonths(1), mei.id),
                     new Duck("Dash", DuckBreed.RUNNER, today.minusMonths(10), mei.id));

        Therapist.persist(new Therapist("Dr. Dewey Quackenbush", "Avian surgery", "Zurich"),
                          new Therapist("Dr. Mabel Mallard", "Waterfowl internal medicine", "Manchester"));

        Treatment.persist(new Treatment("Hydrotherapy Session", "Guided swimming in the warm therapy pool to strengthen muscles without stressing the joints.", 45, "85.00"),
                          new Treatment("Underwater Treadmill", "Low-impact walking against adjustable water resistance, ideal after leg injuries.", 30, "70.00"),
                          new Treatment("Wing Mobilisation", "Gentle stretching and range-of-motion work for stiff or recovering wings.", 30, "60.00"),
                          new Treatment("Webbed Foot Massage", "Massage of the feet and webbing to improve circulation and relieve pressure sores.", 20, "40.00"),
                          new Treatment("Laser Therapy", "Low-level laser treatment to reduce inflammation and speed up tissue healing.", 15, "55.00"),
                          new Treatment("Balance and Waddle Training", "Coordination exercises on wobble boards and ramps for ducks that lost their waddle.", 40, "65.00"));
    }
}
