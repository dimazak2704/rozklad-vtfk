package ua.vtc.rozklad.scheduler.generation.web;

/** Load of the generator: jobs waiting for a free solver and jobs being solved. */
public record SchedulerStatusDto(long queuedJobs, long runningJobs) {
}
