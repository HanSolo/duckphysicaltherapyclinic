package eu.hansolo.duckphysicaltherapyclinic.service;

/** A business rule was violated or something was not found. The message is meant to be shown to the caller. */
public class ClinicException extends RuntimeException {
    private final boolean notFound;


    public ClinicException(final String message, final boolean notFound) {
        super(message);
        this.notFound = notFound;
    }


    public boolean isNotFound() { return notFound; }

    public static ClinicException notFound(final String what, final Object id) { return new ClinicException(what + " with id " + id + " does not exist", true); }

    public static ClinicException invalid(final String message) { return new ClinicException(message, false); }
}
