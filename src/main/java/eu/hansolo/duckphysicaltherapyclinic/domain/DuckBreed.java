package eu.hansolo.duckphysicaltherapyclinic.domain;

public enum DuckBreed {
    MALLARD("Mallard"),
    PEKIN("Pekin"),
    MUSCOVY("Muscovy"),
    CAYUGA("Cayuga"),
    RUNNER("Indian Runner"),
    MANDARIN("Mandarin");

    private final String label;


    DuckBreed(final String label) {
        this.label = label;
    }


    public String getLabel() { return label; }
}
