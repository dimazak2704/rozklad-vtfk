package ua.vtc.rozklad.scheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Timetable generator (Timefold Solver): consumes {@code generation.requested} with a full problem
 * snapshot and answers with {@code generation.progress} / {@code generation.completed}. Has no
 * access to the core database.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SchedulerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchedulerApplication.class, args);
    }
}
