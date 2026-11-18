package eu.hansolo.duckinsurance.audit;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class AuditEntry {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String time;
    private final String actor;
    private final String message;


    public AuditEntry(String actor, String message) {
        this.time = LocalTime.now().format(TIME);
        this.actor = actor;
        this.message = message;
    }


    public String getTime() { return time; }
    public String getActor() { return actor; }
    public String getMessage() { return message; }
}
