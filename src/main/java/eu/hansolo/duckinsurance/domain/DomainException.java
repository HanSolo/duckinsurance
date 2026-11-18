package eu.hansolo.duckinsurance.domain;

/** Business error carrying the HTTP status the REST layer should answer with. */
public class DomainException extends RuntimeException {

    private final int status;


    public DomainException(int status, String message) {
        super(message);
        this.status = status;
    }


    public int getStatus() {
        return status;
    }

    public static DomainException notFound(String what) {
        return new DomainException(404, what + " not found");
    }

    public static DomainException conflict(String message) {
        return new DomainException(409, message);
    }

    public static DomainException forbidden(String message) {
        return new DomainException(403, message);
    }
}
